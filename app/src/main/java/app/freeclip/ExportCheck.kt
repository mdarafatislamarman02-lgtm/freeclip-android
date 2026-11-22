package app.freeclip

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Checks a finished export before it is called "complete": real size, right length, video + audio, not black. */
object ExportCheck {
    class Result(val ok: Boolean, val message: String)

    fun sourceHasAudio(ctx: Context, uri: Uri): Boolean = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } finally {
            r.release()
        }
    } catch (_: Exception) {
        true
    }

    private fun meanLuma(b: Bitmap): Double {
        val w = b.width
        val h = b.height
        var sum = 0.0
        var n = 0
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            sum += 0.299 * r + 0.587 * g + 0.114 * bl
            n++
        }
        return if (n == 0) 0.0 else sum / n
    }

    fun verify(file: File, expectS: Double, expectAudio: Boolean): Result {
        if (!file.exists() || file.length() < 20_000) {
            return Result(false, "The exported file is empty or too small (${file.length() / 1024} KB).")
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val got = ms / 1000.0
            if (got <= 0.0 || abs(got - expectS) > max(2.0, expectS * 0.12)) {
                return Result(false, "The export is incomplete: %.1f s instead of %.1f s.".format(got, expectS))
            }
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                return Result(false, "The export has no video track.")
            }
            if (expectAudio && r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") {
                return Result(false, "The export has no sound, but the original video does.")
            }
            // Black-frame check: only fails when frames CAN be read and ALL are pure black. Not being able to read
            // a thumbnail on some phones is not treated as a broken file.
            var decoded = 0
            var dark = 0
            for (f in listOf(0.2, 0.5, 0.8)) {
                val bmp = try {
                    r.getScaledFrameAtTime((got * f * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 64)
                } catch (_: Exception) {
                    null
                }
                if (bmp != null) {
                    decoded++
                    if (meanLuma(bmp) < 1.0) dark++
                    bmp.recycle()
                }
            }
            if (decoded > 0 && dark == decoded) return Result(false, "The exported video is completely black.")
            return Result(true, "OK")
        } catch (e: Exception) {
            return Result(false, "The exported file cannot be read (${e.message ?: e.javaClass.simpleName}).")
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}
