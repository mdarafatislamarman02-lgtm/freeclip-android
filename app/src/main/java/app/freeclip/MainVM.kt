package app.freeclip

import android.app.Application
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
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

enum class Stage { PICK, READY, ANALYZING, REVIEW, RENDERING, DONE }

class LengthOption(val label: String, val min: Double, val max: Double)

class MainVM(app: Application) : AndroidViewModel(app) {
    var stage by mutableStateOf(Stage.PICK)
    var videoUri by mutableStateOf<Uri?>(null)
    var videoName by mutableStateOf("")
    var durationS by mutableStateOf(0.0)
    var status by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var error by mutableStateOf<String?>(null)

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

    val busy get() = stage == Stage.ANALYZING || stage == Stage.RENDERING

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        error = null
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(ctx, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            if (ms <= 0) throw IllegalStateException("Could not read this video.")
            durationS = ms / 1000.0
            videoName = displayName(uri)
            videoUri = uri
            analysis = null
            moments.clear()
            results.clear()
            stage = Stage.READY
        } catch (e: Exception) {
            error = "Could not open that video: ${e.message}"
        }
    }

    private fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return "video"
    }

    fun analyze() {
        val uri = videoUri ?: return
        error = null
        stage = Stage.ANALYZING
        progress = 0f
        job = viewModelScope.launch {
            try {
                val a = Speech.analyze(ctx, uri, (durationS * 1e6).toLong()) { msg, p ->
                    status = msg
                    progress = p
                }
                analysis = a
                val len = lengths[lengthIdx]
                val found = Moments.find(a.words, a.energy, a.hop, durationS, nClips, len.min, len.max)
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

    fun toggle(i: Int) {
        moments[i] = moments[i].copy(selected = !moments[i].selected)
    }

    fun retitle(i: Int, t: String) {
        moments[i] = moments[i].copy(title = t)
    }

    fun render() {
        val uri = videoUri ?: return
        val words = analysis?.words ?: emptyList()
        val todo = moments.filter { it.selected }
        if (todo.isEmpty()) {
            error = "Tick at least one clip."
            return
        }
        error = null
        stage = Stage.RENDERING
        progress = 0f
        job = viewModelScope.launch {
            try {
                for ((n, m) in todo.withIndex()) {
                    status = "Clip ${n + 1} of ${todo.size}"
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
    }
}
