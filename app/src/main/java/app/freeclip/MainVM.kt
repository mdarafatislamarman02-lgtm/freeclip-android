package app.freeclip

import android.app.Application
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Stage { PICK, DOWNLOADING, READY, ANALYZING, REVIEW, RENDERING, DONE }

class LengthOption(val label: String, val min: Double, val max: Double)

class MainVM(app: Application) : AndroidViewModel(app) {
    var stage by mutableStateOf(Stage.PICK)
    var linkText by mutableStateOf("")
    var videoUri by mutableStateOf<Uri?>(null)
    var videoName by mutableStateOf("")
    var durationS by mutableStateOf(0.0)
    var videoAspect by mutableStateOf(16f / 9f)
    var status by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var stepIdx by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
    var previewIdx by mutableStateOf(-1)

    val lengths = listOf(
        LengthOption("Short 15-30s", 15.0, 30.0),
        LengthOption("Medium 25-50s", 25.0, 50.0),
        LengthOption("Long 45-90s", 45.0, 90.0),
    )
    var lengthIdx by mutableStateOf(1)
    var nClips by mutableStateOf(5)
    var captions by mutableStateOf(true)
    var uppercase by mutableStateOf(true)
    var showTitle by mutableStateOf(false)

    val moments = mutableStateListOf<Moment>()
    val results = mutableStateListOf<ClipResult>()

    private var analysis: Analysis? = null
    private var job: Job? = null
    private val ctx: Application get() = getApplication<Application>()

    val busy get() = stage == Stage.ANALYZING || stage == Stage.RENDERING || stage == Stage.DOWNLOADING

    // ------------------------------------------------------------ get a video (upload or link)

    /** Opens a video that is already on the phone. Returns false (and sets [error]) if it can't be read. */
    private fun openLocal(uri: Uri, name: String): Boolean {
        return try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                if (ms <= 0) throw IllegalStateException("Could not read this video.")
                durationS = ms / 1000.0
                videoAspect = if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f
            } finally {
                r.release()
            }
            videoName = name
            videoUri = uri
            analysis = null
            moments.clear()
            results.clear()
            error = null
            stage = Stage.READY
            true
        } catch (e: Exception) {
            error = "Could not open that video: ${e.message}"
            stage = Stage.PICK
            false
        }
    }

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        var name = "video"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i)
        }
        openLocal(uri, name)
    }

    /** "Get clips in 1 click": download the link, then analyze straight away. */
    fun getClips() {
        val url = Links.extractUrl(linkText.trim())
        if (url == null) {
            error = "Paste a full link that starts with https://"
            return
        }
        error = null
        stage = Stage.DOWNLOADING
        stepIdx = 0
        progress = 0f
        status = "Getting the video..."
        job = viewModelScope.launch {
            try {
                val r = Links.fetch(ctx, url) { msg, p ->
                    status = msg
                    progress = p
                }
                if (openLocal(Uri.fromFile(r.file), r.title)) analyze()
            } catch (e: CancellationException) {
                stage = Stage.PICK
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
                stage = Stage.PICK
            }
        }
    }

    // ------------------------------------------------------------ analyze: transcript -> moments -> ranking

    fun analyze() {
        val uri = videoUri ?: return
        error = null
        stage = Stage.ANALYZING
        stepIdx = 1
        progress = 0f
        job = viewModelScope.launch {
            try {
                val a = Speech.analyze(ctx, uri, (durationS * 1e6).toLong()) { msg, p ->
                    status = msg
                    progress = p
                }
                analysis = a
                stepIdx = 2
                status = "Finding interesting moments..."
                val len = lengths[lengthIdx]
                val found = Moments.find(a.words, a.energy, a.hop, durationS, nClips, len.min, len.max)
                stepIdx = 3
                status = "Ranking the clips..."
                moments.clear()
                moments.addAll(found)
                if (found.isEmpty()) {
                    error = "No clear speech was found in this video. Try a video with talking, in English."
                    stage = Stage.READY
                } else {
                    stage = Stage.REVIEW
                }
            } catch (e: CancellationException) {
                stage = Stage.READY
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
                stage = Stage.READY
            }
        }
    }

    // ------------------------------------------------------------ edit & preview

    fun toggle(i: Int) {
        moments[i] = moments[i].copy(selected = !moments[i].selected)
    }

    fun retitle(i: Int, t: String) {
        moments[i] = moments[i].copy(title = t)
    }

    /** Nudge a clip's start/end by whole seconds, keeping it inside the video and at least 5 s long. */
    fun trim(i: Int, dStart: Double, dEnd: Double) {
        val m = moments[i]
        val s = (m.start + dStart).coerceIn(0.0, durationS)
        val e = (m.end + dEnd).coerceIn(0.0, durationS)
        if (e - s >= 5.0) moments[i] = m.copy(start = s, end = e)
    }

    // ------------------------------------------------------------ export

    fun render() {
        val uri = videoUri ?: return
        val words = analysis?.words ?: emptyList()
        val todo = moments.filter { it.selected }
        if (todo.isEmpty()) {
            error = "Tick at least one clip."
            return
        }
        error = null
        previewIdx = -1
        stage = Stage.RENDERING
        progress = 0f
        job = viewModelScope.launch {
            try {
                for ((n, m) in todo.withIndex()) {
                    status = "Clip ${n + 1} of ${todo.size}: crop 9:16, captions, MP4"
                    val tmp = File(ctx.cacheDir, "clip_${m.id}.mp4")
                    Renderer.export(
                        ctx, uri, m.start, m.end,
                        if (captions) words else emptyList(),
                        m.title, showTitle, uppercase, tmp,
                    ) { p -> progress = (n + p) / todo.size }
                    val saved = withContext(Dispatchers.IO) { saveToGallery(tmp, m.title) }
                    results.add(ClipResult(m.title, saved.toString(), m.end - m.start))
                    tmp.delete()
                }
                progress = 1f
                stage = Stage.DONE
            } catch (e: CancellationException) {
                stage = Stage.REVIEW
                throw e
            } catch (e: Throwable) {
                error = "Export failed: ${e.message ?: e}"
                stage = if (results.isEmpty()) Stage.REVIEW else Stage.DONE
            }
        }
    }

    private fun saveToGallery(file: File, title: String): Uri {
        val resolver = ctx.contentResolver
        val safe = title.replace(Regex("[^A-Za-z0-9 ]"), "").trim().take(30).ifEmpty { "clip" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "FreeClip_${System.currentTimeMillis() / 1000}_$safe.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FreeClip")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not save to the Gallery.")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun cancel() {
        job?.cancel()
    }

    fun backToReview() {
        stage = Stage.REVIEW
    }

    fun startOver() {
        stage = Stage.PICK
        videoUri = null
        moments.clear()
        results.clear()
        analysis = null
        error = null
        previewIdx = -1
    }
}
