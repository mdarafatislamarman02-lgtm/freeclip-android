package app.freeclip

import android.app.Application
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
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

class MainVM(app: Application) : AndroidViewModel(app) {
    val store = Store(app)

    /** 0 Home, 1 Create, 2 My Clips, 3 Templates, 4 Settings */
    var tab by mutableStateOf(0)
    var stage by mutableStateOf(Stage.PICK)
    var linkText by mutableStateOf("")
    var videoUri by mutableStateOf<Uri?>(null)
    var videoName by mutableStateOf("")
    var durationS by mutableStateOf(0.0)
    var videoAspect by mutableStateOf(16f / 9f)
    var status by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var eta by mutableStateOf("")
    var stepIdx by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
    var editIdx by mutableStateOf(-1)

    // analysis window ("Adjust range")
    var rangeA by mutableStateOf(0f)
    var rangeB by mutableStateOf(0f)

    // what to make
    var prompt by mutableStateOf("")
    var lengthIdx by mutableStateOf(store.lengthIdx.coerceIn(0, LENGTHS.size - 1))
    var nClips by mutableStateOf(5)
    var ratio by mutableStateOf(Ratio.values()[store.ratioIdx.coerceIn(0, Ratio.values().size - 1)])
    var layout by mutableStateOf(LayoutMode.FILL)
    var corner by mutableStateOf(4)
    var presetIdx by mutableStateOf(store.presetIdx.coerceIn(0, PRESETS.size - 1))
    var captions by mutableStateOf(store.captions)
    var showTitle by mutableStateOf(false)
    var res by mutableStateOf(store.res)

    val moments = mutableStateListOf<Moment>()
    val results = mutableStateListOf<LibraryClip>()
    val library = mutableStateListOf<LibraryClip>()

    private var analysis: Analysis? = null
    private var job: Job? = null
    private val faceCache = HashMap<String, FaceInfo>()
    private val ctx: Application get() = getApplication<Application>()

    val busy get() = stage == Stage.ANALYZING || stage == Stage.RENDERING || stage == Stage.DOWNLOADING
    val preset get() = PRESETS[presetIdx]

    init {
        library.addAll(store.loadLibrary())
    }

    fun saveDefaults() {
        store.lengthIdx = lengthIdx
        store.ratioIdx = ratio.ordinal
        store.presetIdx = presetIdx
        store.captions = captions
        store.res = res
    }

    fun applyTemplate(t: Template) {
        lengthIdx = t.lengthIdx
        nClips = t.count
        layout = t.layout
        ratio = t.ratio
        presetIdx = t.preset
        prompt = t.prompt
        tab = 1
    }

    // ------------------------------------------------------------ get a video (upload or link)

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
            rangeA = 0f
            // Long videos start with the first 30 minutes (much faster); slide the range to analyse more.
            rangeB = if (durationS > 3600) 1800f else durationS.toFloat()
            analysis = null
            faceCache.clear()
            moments.clear()
            results.clear()
            error = null
            stage = Stage.READY
            tab = 1
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

    /** "Generate clips": download the link, then wait on the options screen. */
    fun getClips() {
        val url = Links.extractUrl(linkText.trim())
        if (url == null) {
            error = "Paste a full link that starts with https://"
            return
        }
        error = null
        tab = 1
        stage = Stage.DOWNLOADING
        stepIdx = 0
        progress = 0f
        eta = ""
        status = "Getting the video..."
        job = viewModelScope.launch {
            try {
                val r = Links.fetch(ctx, url) { msg, p ->
                    status = msg
                    progress = p
                }
                openLocal(Uri.fromFile(r.file), r.title)
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
        eta = ""
        val t0 = SystemClock.elapsedRealtime()
        job = viewModelScope.launch {
            try {
                val a = Speech.analyze(ctx, uri, durationS, rangeA.toDouble(), rangeB.toDouble()) { msg, p ->
                    status = msg
                    progress = p
                    if (p > 0.32f) {
                        val el = (SystemClock.elapsedRealtime() - t0) / 1000.0
                        val left = el * (1 - p) / p
                        eta = if (left < 60) "about ${left.toInt() + 1} s left" else "about ${(left / 60).toInt() + 1} min left"
                    }
                }
                analysis = a
                stepIdx = 2
                status = "Finding interesting moments..."
                val len = LENGTHS[lengthIdx]
                val found = Moments.find(a.words, a.energy, a.hop, durationS, nClips, len.min, len.max, Moments.keywords(prompt))
                stepIdx = 3
                status = "Ranking the clips..."
                moments.clear()
                moments.addAll(found)
                if (found.isEmpty()) {
                    error = "No clear speech was found in this part of the video. Try a different range, or a video with talking in English."
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

    /** Nudge a clip's start/end by seconds, keeping it inside the video and at least 5 s long. */
    fun trim(i: Int, dStart: Double, dEnd: Double) {
        val m = moments[i]
        val s = (m.start + dStart).coerceIn(0.0, durationS)
        val e = (m.end + dEnd).coerceIn(0.0, durationS)
        if (e - s >= 5.0) moments[i] = m.copy(start = s, end = e)
    }

    fun wordsFor(m: Moment): List<Word> = analysis?.words ?: emptyList()

    /** Where the speaker / facecam is in this clip (cached; runs off the main thread). */
    suspend fun faceFor(m: Moment): FaceInfo {
        val uri = videoUri ?: return FaceInfo.NONE
        val key = "${m.id}_${m.start.toInt()}_${m.end.toInt()}"
        faceCache[key]?.let { return it }
        val f = withContext(Dispatchers.IO) { Faces.analyze(ctx, uri, m.start, m.end, videoAspect) }
        faceCache[key] = f
        return f
    }

    fun specFor(face: FaceInfo) = ExportSpec(layout, ratio, res, preset, captions, showTitle, face, corner)

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
        editIdx = -1
        stage = Stage.RENDERING
        progress = 0f
        eta = ""
        results.clear()
        val t0 = SystemClock.elapsedRealtime()
        job = viewModelScope.launch {
            try {
                for ((n, m) in todo.withIndex()) {
                    status = "Clip ${n + 1} of ${todo.size}: ${layout.label} layout, ${ratio.label}, captions"
                    val face = if (layout == LayoutMode.FILL || layout.needsCam) faceFor(m) else FaceInfo.NONE
                    val tmp = File(ctx.cacheDir, "clip_${m.id}.mp4")
                    Renderer.export(ctx, uri, m.start, m.end, words, m.title, specFor(face), tmp) { p ->
                        progress = (n + p) / todo.size
                        if (progress > 0.05f) {
                            val el = (SystemClock.elapsedRealtime() - t0) / 1000.0
                            val left = el * (1 - progress) / progress
                            eta = if (left < 60) "about ${left.toInt() + 1} s left" else "about ${(left / 60).toInt() + 1} min left"
                        }
                    }
                    val saved = withContext(Dispatchers.IO) { saveToGallery(tmp, m.title) }
                    val clip = LibraryClip(
                        id = System.currentTimeMillis().toString() + "_" + m.id,
                        title = m.title, uri = saved.toString(), seconds = m.end - m.start,
                        created = System.currentTimeMillis(), layout = layout.label, score = m.score,
                    )
                    results.add(clip)
                    library.add(0, clip)
                    store.saveLibrary(library.toList())
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
            put(MediaStore.Video.Media.DISPLAY_NAME, "ClipForge_${System.currentTimeMillis() / 1000}_$safe.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipForge")
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

    fun removeFromLibrary(c: LibraryClip) {
        library.remove(c)
        store.saveLibrary(library.toList())
    }

    fun clearCache(): String {
        var n = 0L
        for (d in listOf(ctx.cacheDir, File(ctx.filesDir, "downloads"))) {
            d.listFiles()?.forEach { f -> n += f.length(); f.deleteRecursively() }
        }
        return "Freed ${n / (1024 * 1024)} MB"
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
        faceCache.clear()
        error = null
        editIdx = -1
    }
}
