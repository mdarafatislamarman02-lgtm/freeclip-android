package app.freeclip

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.effect.Presentation
import androidx.media3.effect.OverlaySettings
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
    private val preset: CaptionPreset,
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
        s.setSpan(ForegroundColorSpan(preset.text), 0, s.length, all)
        if (preset.bg != 0) s.setSpan(BackgroundColorSpan(preset.bg), 0, s.length, all)
        if (c.hlEnd > c.hlStart) {
            if (preset.hlBg != 0) s.setSpan(BackgroundColorSpan(preset.hlBg), c.hlStart, c.hlEnd, all)
            else s.setSpan(ForegroundColorSpan(preset.hl), c.hlStart, c.hlEnd, all)
        }
        return s
    }
}

/** Everything that decides how a clip looks. Shared by the preview and the export. */
class ExportSpec(
    val layout: LayoutMode,
    val ratio: Ratio,
    val side: Int, // short side in pixels: 720 or 1080
    val preset: CaptionPreset,
    val captions: Boolean,
    val showTitle: Boolean,
    val face: FaceInfo,
    val corner: Int,
)

object Renderer {
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

    /** Normalised (-1..1, y up) vertical position of the captions. */
    fun captionY(spec: ExportSpec): Float =
        if (spec.layout == LayoutMode.GAMING) 1f - 2f * Faces.split(spec.ratio) else -0.30f

    /** Cuts [startS, endS] from [uri], lays it out and burns in captions/title. */
    @OptIn(UnstableApi::class)
    suspend fun export(
        ctx: Context,
        uri: Uri,
        startS: Double,
        endS: Double,
        words: List<Word>,
        title: String,
        spec: ExportSpec,
        out: File,
        safe: Boolean = false,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((startS * 1000).toLong())
                .setEndPositionMs((endS * 1000).toLong())
                .build()
            val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()

            val layoutFx = LayoutEffect.create(spec.layout, spec.ratio, spec.side, spec.face, spec.corner)
            val scale = layoutFx.outW / 1080f
            val overlays = ArrayList<TextureOverlay>()
            val cues = if (spec.captions) captionCues(words, startS, endS, spec.preset.upper) else emptyList()
            if (cues.isNotEmpty()) {
                val s = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, captionY(spec)).build()
                overlays.add(CueOverlay(cues, (spec.preset.size * 0.85f * scale).toInt(), s, spec.preset))
            }
            if (spec.showTitle && title.isNotBlank()) {
                val s = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, 0.80f).build()
                val t = wrapTitle(title)
                overlays.add(CueOverlay(listOf(Cue(0, 3_000_000, t, 0, 0)), (56 * scale).toInt(), s, PRESETS[0]))
            }

            val videoEffects = ArrayList<Effect>()
            // safe = the plain centre-crop that worked in the first version (no custom GL shader).
            if (safe) videoEffects.add(Presentation.createForWidthAndHeight(layoutFx.outW, layoutFx.outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            else videoEffects.add(layoutFx)
            if (overlays.isNotEmpty()) videoEffects.add(OverlayEffect(ImmutableList.copyOf(overlays)))

            val edited = EditedMediaItem.Builder(item)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val bitrate = if (spec.side >= 1080) 8_000_000 else 5_000_000
            val encoder = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
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

            var lastP = -1f
            var lastT = SystemClock.elapsedRealtime()
            poll = Runnable {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val p = holder.progress / 100f
                    onProgress(p)
                    if (p != lastP) { lastP = p; lastT = SystemClock.elapsedRealtime() }
                }
                if (SystemClock.elapsedRealtime() - lastT > 180_000) {
                    // No progress for 3 minutes: stop instead of showing an endless spinner.
                    transformer.cancel()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("The export stalled and was stopped. Tap Retry."))
                } else {
                    handler.postDelayed(poll, 400)
                }
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
