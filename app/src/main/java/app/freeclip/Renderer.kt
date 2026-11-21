package app.freeclip

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One caption frame: text on screen between [startUs, endUs), with one word highlighted. */
class Cue(val startUs: Long, val endUs: Long, val text: String, val hlStart: Int, val hlEnd: Int)

@OptIn(UnstableApi::class)
class CueOverlay(
    private val cues: List<Cue>,
    private val sizePx: Int,
    private val settings: OverlaySettings,
) : TextOverlay() {
    private val cache = HashMap<Int, SpannableString>()
    private val blank = SpannableString(" ")

    override fun getText(presentationTimeUs: Long): SpannableString {
        val i = cues.indexOfFirst { presentationTimeUs >= it.startUs && presentationTimeUs < it.endUs }
        if (i < 0) return blank
        return cache.getOrPut(i) { build(cues[i]) }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    private fun build(c: Cue): SpannableString {
        val s = SpannableString(c.text)
        val all = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        s.setSpan(AbsoluteSizeSpan(sizePx), 0, s.length, all)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, all)
        s.setSpan(ForegroundColorSpan(Color.WHITE), 0, s.length, all)
        s.setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, s.length, all)
        if (c.hlEnd > c.hlStart) {
            s.setSpan(ForegroundColorSpan(0xFFFFD21F.toInt()), c.hlStart, c.hlEnd, all)
        }
        return s
    }
}

object Renderer {
    const val OUT_W = 1080
    const val OUT_H = 1920

    /** Word-by-word caption cues, 1-3 words at a time, the active word highlighted. */
    fun captionCues(words: List<Word>, startS: Double, endS: Double, upper: Boolean): List<Cue> {
        val ws = words.filter { it.e > startS && it.s < endS }
            .map { Word(if (upper) it.w.uppercase() else it.w, maxOf(it.s - startS, 0.0), minOf(it.e, endS) - startS) }
        val chunks = ArrayList<List<Word>>()
        var cur = ArrayList<Word>()
        for (w in ws) {
            if (cur.isNotEmpty()) {
                val tooLong = (cur + w).joinToString(" ") { it.w }.length > 20
                if (cur.size >= 3 || tooLong || w.s - cur.last().e > 0.6) {
                    chunks.add(cur)
                    cur = ArrayList()
                }
            }
            cur.add(w)
        }
        if (cur.isNotEmpty()) chunks.add(cur)

        val cues = ArrayList<Cue>()
        for ((ci, chunk) in chunks.withIndex()) {
            val nextStart = if (ci + 1 < chunks.size) chunks[ci + 1][0].s else endS - startS
            val text = chunk.joinToString(" ") { it.w }
            var offset = 0
            for ((k, w) in chunk.withIndex()) {
                val s = w.s
                val e = if (k + 1 < chunk.size) chunk[k + 1].s else minOf(w.e + 0.2, nextStart)
                if (e > s) {
                    cues.add(Cue((s * 1e6).toLong(), (e * 1e6).toLong(), text, offset, offset + w.w.length))
                }
                offset += w.w.length + 1
            }
        }
        return cues
    }

    private fun wrapTitle(t: String): String {
        val words = t.trim().split(Regex("\\s+"))
        val lines = ArrayList<String>()
        var line = ""
        for (w in words) {
            if (line.isNotEmpty() && (line + " " + w).length > 24) {
                lines.add(line)
                line = w
            } else {
                line = if (line.isEmpty()) w else "$line $w"
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        return lines.take(2).joinToString("\n")
    }

    /** Cuts [startS, endS] from [uri], crops to 9:16 (1080x1920) and burns in captions/title. */
    @OptIn(UnstableApi::class)
    suspend fun export(
        ctx: Context,
        uri: Uri,
        startS: Double,
        endS: Double,
        words: List<Word>,
        title: String,
        showTitle: Boolean,
        uppercase: Boolean,
        out: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((startS * 1000).toLong())
                .setEndPositionMs((endS * 1000).toLong())
                .build()
            val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()

            val overlays = ArrayList<TextureOverlay>()
            val cues = captionCues(words, startS, endS, uppercase)
            if (cues.isNotEmpty()) {
                val s = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.28f).build()
                overlays.add(CueOverlay(cues, 66, s))
            }
            if (showTitle && title.isNotBlank()) {
                val s = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, 0.72f).build()
                val t = wrapTitle(title)
                overlays.add(CueOverlay(listOf(Cue(0, 3_000_000, t, 0, 0)), 56, s))
            }

            val videoEffects = ArrayList<Effect>()
            videoEffects.add(Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            if (overlays.isNotEmpty()) videoEffects.add(OverlayEffect(ImmutableList.copyOf(overlays)))

            val edited = EditedMediaItem.Builder(item)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val encoder = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(8_000_000).build())
                .build()

            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            lateinit var poll: Runnable

            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()

            poll = Runnable {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                handler.postDelayed(poll, 400)
            }

            if (out.exists()) out.delete()
            transformer.start(edited, out.absolutePath)
            handler.postDelayed(poll, 400)

            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                }
            }
        }
    }
}
