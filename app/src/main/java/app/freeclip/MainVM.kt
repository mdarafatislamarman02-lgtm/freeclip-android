package app.freeclip

import android.app.Application
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Stage { PICK, DOWNLOADING, READY, ANALYZING, REVIEW, RENDERING, DONE }

enum class JobState(val label: String) {
    IDLE(""), QUEUED("Queued"), PROCESSING("Processing"), BACKGROUND("Background Processing"),
    PAUSED("Paused"), FAILED("Failed"), COMPLETED("Completed"),
}

/** What "Resume" / "Retry" does for an interrupted or failed job. */
enum class Resume { NONE, DOWNLOAD, ANALYZE, EXPORT }

/**
 * One instance for the whole process (see [ClipApp]). Work runs in [scope], not in the screen, and a foreground
 * service keeps the process alive, so jobs go on while the app is minimised or the screen is off.
 * The job is saved to disk and restored if the process was killed.
 */
class MainVM(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 0 Home, 1 Create, 2 My Clips, 3 Templates, 4 Settings */
    var tab by mutableStateOf(0)
    var stage by mutableStateOf(Stage.PICK)
    var linkText by mutableStateOf("")
    var videoUri by mutableStateOf<Uri?>(null)
    /** For YouTube links the audio arrives first: analysis starts from it while the video is still downloading. */
    var audioUri by mutableStateOf<Uri?>(null)
    var videoReady by mutableStateOf(true)
    var dlProgress by mutableStateOf(0f)
    var dlActive by mutableStateOf(false)
    var videoName by mutableStateOf("")
    var durationS by mutableStateOf(0.0)
    var videoAspect by mutableStateOf(16f / 9f)
    var status by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var eta by mutableStateOf("")
    var stepIdx by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
    var editIdx by mutableStateOf(-1)

    // job state
    var queued by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var resumeAction by mutableStateOf(Resume.NONE)
    var inBackground by mutableStateOf(false)
    val exportStatus = mutableStateMapOf<Int, String>() // moment id -> "ok" | "fail: reason"
    val timings = mutableStateMapOf<String, Long>() // "download" | "analyze" | "export" -> milliseconds

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
    private var analysisA = -1f
    private var analysisB = -1f
    private var job: Job? = null
    private var dlJob: Job? = null
    private var active = 0
    private var jobUrl = ""
    private val faceCache = HashMap<String, FaceInfo>()
    private val ctx: Application get() = getApplication<Application>()

    val busy get() = stage == Stage.ANALYZING || stage == Stage.RENDERING || stage == Stage.DOWNLOADING
    val working get() = busy || dlActive
    val preset get() = PRESETS[presetIdx]

    val jobState: JobState
        get() = when {
            queued -> JobState.QUEUED
            working -> if (inBackground) JobState.BACKGROUND else JobState.PROCESSING
            paused -> JobState.PAUSED
            failed -> JobState.FAILED
            stage == Stage.DONE -> JobState.COMPLETED
            else -> JobState.IDLE
        }

    init {
        library.addAll(store.loadLibrary())
        restore()
    }

    // ------------------------------------------------------------ background service + notification

    private fun begin() {
        active++
        queued = false
        ClipService.start(ctx)
    }

    private fun end() {
        active = maxOf(0, active - 1)
        if (active == 0) ClipService.stop(ctx)
    }

    private fun report(msg: String, p: Float) {
        status = msg
        progress = p
        ClipService.update(ctx, msg, (p * 100).toInt())
    }

    private fun etaText(startMs: Long, p: Float): String {
        val el = (SystemClock.elapsedRealtime() - startMs) / 1000.0
        val left = el * (1 - p) / p
        return if (left < 60) "about ${left.toInt() + 1} s left" else "about ${(left / 60).toInt() + 1} min left"
    }

    // ------------------------------------------------------------ save / restore

    fun persist() {
        if (stage == Stage.PICK && videoUri == null && audioUri == null) return
        val inFlight = when {
            stage == Stage.RENDERING -> "export"
            stage == Stage.ANALYZING -> "analyze"
            stage == Stage.DOWNLOADING || dlActive -> "download"
            else -> ""
        }
        JobStore.save(
            ctx,
            Saved(
                url = jobUrl, title = videoName, durationS = durationS, aspect = videoAspect,
                videoUri = videoUri?.toString() ?: "", audioUri = audioUri?.toString() ?: "", videoReady = videoReady,
                stage = stage.name, prompt = prompt, lengthIdx = lengthIdx, nClips = nClips, ratioIdx = ratio.ordinal,
                layoutIdx = layout.ordinal, corner = corner, presetIdx = presetIdx, captions = captions,
                showTitle = showTitle, res = res, rangeA = rangeA, rangeB = rangeB,
                analysisA = analysisA, analysisB = analysisB,
                moments = moments.toList(), exported = exportStatus.toMap(), resultIds = results.map { it.id },
                error = error ?: "", timings = timings.toMap(), inFlight = inFlight,
            )
        )
    }

    private fun readable(u: Uri?): Boolean {
        if (u == null) return false
        return try {
            ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Brings back the job that was running or finished when the app was closed or killed. */
    private fun restore() {
        val s = JobStore.load(ctx) ?: return
        jobUrl = s.url
        linkText = s.url
        videoName = s.title
        durationS = s.durationS
        videoAspect = s.aspect
        prompt = s.prompt
        lengthIdx = s.lengthIdx.coerceIn(0, LENGTHS.size - 1)
        nClips = s.nClips
        ratio = Ratio.values()[s.ratioIdx.coerceIn(0, Ratio.values().size - 1)]
        layout = LayoutMode.values()[s.layoutIdx.coerceIn(0, LayoutMode.values().size - 1)]
        corner = s.corner
        presetIdx = s.presetIdx.coerceIn(0, PRESETS.size - 1)
        captions = s.captions
        showTitle = s.showTitle
        res = s.res
        rangeA = s.rangeA
        rangeB = s.rangeB
        moments.addAll(s.moments)
        exportStatus.putAll(s.exported)
        timings.putAll(s.timings)
        results.addAll(library.filter { it.id in s.resultIds })
        val vu = s.videoUri.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        val au = s.audioUri.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        val haveVideo = readable(vu)
        val haveAudio = readable(au)
        videoUri = if (haveVideo) vu else null
        audioUri = if (haveAudio) au else null
        videoReady = haveVideo
        analysis = JobStore.loadAnalysis(ctx)
        analysisA = if (analysis != null) s.analysisA else -1f
        analysisB = if (analysis != null) s.analysisB else -1f

        if (!haveVideo && !haveAudio) {
            // The video file is gone (cleared storage, permission lost). Keep the link so one tap fetches it again.
            moments.clear()
            stage = Stage.PICK
            if (s.url.isNotEmpty()) {
                error = "Your last video is no longer on the phone. Tap \"Get clips in 1 click\" to download it again."
            } else {
                error = "Your last video is no longer available. Please pick it again."
            }
            return
        }

        val interrupted = s.inFlight.isNotEmpty()
        stage = when (s.stage) {
            "ANALYZING" -> Stage.READY
            "RENDERING" -> if (moments.isEmpty()) Stage.READY else Stage.REVIEW
            "DOWNLOADING" -> Stage.READY
            else -> runCatching { Stage.valueOf(s.stage) }.getOrDefault(Stage.READY)
        }
        if (stage == Stage.PICK) stage = Stage.READY
        if (interrupted || !haveVideo) {
            paused = true
            resumeAction = when {
                s.inFlight == "export" -> Resume.EXPORT
                s.inFlight == "analyze" -> Resume.ANALYZE
                else -> Resume.DOWNLOAD
            }
            if (!haveVideo && s.url.isEmpty()) {
                // Picked from the phone: no link to download again.
                paused = false
                resumeAction = Resume.NONE
                error = "The video could not be opened again. Please pick it again."
            }
        } else if (s.error.isNotEmpty() && exportStatus.values.any { it.startsWith("fail") }) {
            failed = true
            resumeAction = Resume.EXPORT
            error = s.error
        }
        if (stage == Stage.REVIEW || stage == Stage.DONE || stage == Stage.READY) tab = 1
    }

    /** Resume / Retry: continues the interrupted step and reuses everything already done. */
    fun resume() {
        error = null
        val a = resumeAction
        paused = false
        failed = false
        resumeAction = Resume.NONE
        when (a) {
            Resume.DOWNLOAD -> {
                linkText = jobUrl.ifEmpty { linkText }
                getClips(force = true)
            }
            Resume.ANALYZE -> analyze()
            Resume.EXPORT -> render(resume = true)
            Resume.NONE -> {}
        }
    }

    fun saveDefaults() {
        store.lengthIdx = lengthIdx
        store.ratioIdx = ratio.ordinal
        store.presetIdx = presetIdx
        store.captions = captions
        store.res = res
        persist()
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
            audioUri = null
            videoReady = true
            rangeA = 0f
            // Long videos start with the first 30 minutes (much faster); slide the range to analyse more.
            rangeB = if (durationS > 3600) 1800f else durationS.toFloat()
            resetResults()
            error = null
            stage = Stage.READY
            tab = 1
            persist()
            true
        } catch (e: Exception) {
            error = "Could not open that video: ${e.message}"
            stage = Stage.PICK
            false
        }
    }

    private fun resetResults() {
        analysis = null
        analysisA = -1f
        analysisB = -1f
        faceCache.clear()
        moments.clear()
        results.clear()
        exportStatus.clear()
        paused = false
        failed = false
        resumeAction = Resume.NONE
    }

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        if (working) {
            error = "A clipping job is still running. Wait for it to finish or cancel it first."
            return
        }
        var name = "video"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i)
        }
        jobUrl = ""
        openLocal(uri, name)
    }

    /** "Generate clips": download the link, then wait on the options screen. */
    fun getClips(force: Boolean = false) {
        val url = Links.extractUrl(linkText.trim())
        if (url == null) {
            error = "Paste a full link that starts with https://"
            return
        }
        if (working) {
            error = "A clipping job is already running."
            return
        }
        if (!force && url == jobUrl && (videoUri != null || audioUri != null) && stage != Stage.PICK) {
            // Same video again: do not download or process it twice.
            error = null
            tab = 1
            return
        }
        error = null
        failed = false
        paused = false
        tab = 1
        jobUrl = url
        resetResults()
        videoUri = null
        audioUri = null
        videoReady = true
        stage = Stage.DOWNLOADING
        stepIdx = 0
        progress = 0f
        eta = ""
        status = "Getting the video..."
        dlProgress = 0f
        queued = true
        val t0 = SystemClock.elapsedRealtime()
        dlActive = true
        dlJob = scope.launch {
            begin()
            try {
                val r = Links.fetch(
                    ctx, url,
                    { msg, p -> report(msg, p) },
                    early = { f, title, dur -> scope.launch { openAudioFirst(f, title, dur) } },
                    onVideo = { dlProgress = it; ClipService.update(ctx, "Downloading the video...", (it * 100).toInt()) },
                )
                timings["download"] = SystemClock.elapsedRealtime() - t0
                if (audioUri != null && !videoReady) attachVideo(r.file) else openLocal(Uri.fromFile(r.file), r.title)
                persist()
            } catch (e: CancellationException) {
                if (audioUri == null) stage = Stage.PICK
                throw e
            } catch (e: Throwable) {
                error = (e.message ?: e.toString()) + if (audioUri != null) " The video download stopped; tap Resume to try again." else ""
                failed = true
                resumeAction = Resume.DOWNLOAD
                if (audioUri == null) stage = Stage.PICK
                persist()
                ClipService.done(ctx, "Download failed", "Open ClipForge AI and tap Retry.")
            } finally {
                dlActive = false
                end()
            }
        }
    }

    /** The audio of a link is ready: show the options now; the video keeps downloading in the background. */
    private fun openAudioFirst(f: File, title: String, durS: Double) {
        videoName = title
        durationS = durS
        videoUri = null
        audioUri = Uri.fromFile(f)
        videoReady = false
        dlProgress = 0f
        rangeA = 0f
        rangeB = if (durS > 3600) 1800f else durS.toFloat()
        resetResults()
        error = null
        stage = Stage.READY
        tab = 1
        persist()
    }

    private fun attachVideo(f: File) {
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, Uri.fromFile(f))
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                if (w > 0 && h > 0) videoAspect = w.toFloat() / h
            } finally {
                r.release()
            }
        } catch (_: Exception) {
        }
        videoUri = Uri.fromFile(f)
        videoReady = true
        paused = false
        failed = false
    }

    // ------------------------------------------------------------ analyze: transcript -> moments -> ranking

    fun analyze() {
        val uri = videoUri ?: audioUri ?: return
        if (stage == Stage.ANALYZING || stage == Stage.RENDERING) return
        error = null
        failed = false
        paused = false
        resumeAction = Resume.NONE
        stage = Stage.ANALYZING
        stepIdx = 1
        progress = 0f
        eta = ""
        queued = true
        persist()
        val t0 = SystemClock.elapsedRealtime()
        job = scope.launch {
            begin()
            try {
                val cached = analysis != null && analysisA == rangeA && analysisB == rangeB
                val a = if (cached) {
                    report("Using the transcript you already have...", 1f)
                    analysis!!
                } else {
                    Speech.analyze(ctx, uri, durationS, rangeA.toDouble(), rangeB.toDouble()) { msg, p ->
                        report(msg, p)
                        if (p > 0.32f) eta = etaText(t0, p)
                    }.also {
                        analysis = it
                        analysisA = rangeA
                        analysisB = rangeB
                        timings["analyze"] = SystemClock.elapsedRealtime() - t0
                        withContext(Dispatchers.IO) { JobStore.saveAnalysis(ctx, it) }
                    }
                }
                stepIdx = 2
                report("Finding interesting moments...", 1f)
                val len = LENGTHS[lengthIdx]
                val found = Moments.find(a.words, a.energy, a.hop, durationS, nClips, len.min, len.max, Moments.keywords(prompt))
                stepIdx = 3
                report("Ranking the clips...", 1f)
                exportStatus.clear()
                results.clear()
                moments.clear()
                moments.addAll(found)
                if (found.isEmpty()) {
                    error = "No clear speech was found in this part of the video. Try a different range, or a video with talking in English."
                    stage = Stage.READY
                } else {
                    stage = Stage.REVIEW
                    ClipService.done(ctx, "Clips found", "${found.size} clips are ready to review.")
                }
                persist()
            } catch (e: CancellationException) {
                stage = Stage.READY
                persist()
                throw e
            } catch (e: Throwable) {
                error = "Analysis failed: ${e.message ?: e}"
                failed = true
                resumeAction = Resume.ANALYZE
                stage = Stage.READY
                persist()
                ClipService.done(ctx, "Analysis failed", "Open ClipForge AI and tap Retry.")
            } finally {
                end()
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

    // ------------------------------------------------------------ export (each clip is checked before it counts as done)

    /** [resume] = only the clips that are not exported yet (after an interruption or a failure). */
    fun render(resume: Boolean = false) {
        val uri = videoUri
        if (uri == null || !videoReady) {
            error = "The video is still downloading (${(dlProgress * 100).toInt()}%). Try again in a moment."
            return
        }
        if (stage == Stage.RENDERING) return
        val words = analysis?.words ?: emptyList()
        val selected = moments.filter { it.selected }
        if (selected.isEmpty()) {
            error = "Tick at least one clip."
            return
        }
        val todo = if (resume) selected.filter { exportStatus[it.id] != "ok" } else selected
        if (todo.isEmpty()) {
            error = null
            stage = Stage.DONE
            return
        }
        if (!resume) {
            for (m in selected) exportStatus.remove(m.id)
            results.clear()
        }
        error = null
        failed = false
        paused = false
        resumeAction = Resume.NONE
        editIdx = -1
        stage = Stage.RENDERING
        progress = 0f
        eta = ""
        queued = true
        persist()
        val t0 = SystemClock.elapsedRealtime()
        job = scope.launch {
            begin()
            try {
                val hasAudio = withContext(Dispatchers.IO) { ExportCheck.sourceHasAudio(ctx, uri) }
                for ((n, m) in todo.withIndex()) {
                    report("Clip ${n + 1} of ${todo.size}: ${layout.label}, ${ratio.label}, ${res}p", n.toFloat() / todo.size)
                    var ok = false
                    var lastErr = ""
                    for (attempt in 1..2) {
                        val tmp = File(ctx.cacheDir, "clip_${m.id}.mp4")
                        try {
                            tmp.delete()
                            val face = if (layout == LayoutMode.FILL || layout.needsCam) faceFor(m) else FaceInfo.NONE
                            Renderer.export(ctx, uri, m.start, m.end, words, m.title, specFor(face), tmp) { p ->
                                val all = (n + p) / todo.size
                                report("Clip ${n + 1} of ${todo.size}: ${layout.label}, ${ratio.label}, ${res}p", all)
                                if (all > 0.05f) eta = etaText(t0, all)
                            }
                            report("Checking clip ${n + 1} of ${todo.size}...", (n + 1f) / todo.size)
                            val chk = withContext(Dispatchers.IO) { ExportCheck.verify(tmp, m.end - m.start, hasAudio) }
                            if (!chk.ok) throw IllegalStateException(chk.message)
                            val saved = withContext(Dispatchers.IO) { saveToGallery(tmp, m.title) }
                            val clip = LibraryClip(
                                id = System.currentTimeMillis().toString() + "_" + m.id,
                                title = m.title, uri = saved.toString(), seconds = m.end - m.start,
                                created = System.currentTimeMillis(), layout = layout.label, score = m.score,
                            )
                            results.add(clip)
                            library.add(0, clip)
                            store.saveLibrary(library.toList())
                            exportStatus[m.id] = "ok"
                            ok = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            lastErr = e.message ?: e.toString()
                        } finally {
                            tmp.delete()
                        }
                        if (ok) break
                    }
                    if (!ok) exportStatus[m.id] = "fail: $lastErr"
                    persist()
                }
                timings["export"] = (SystemClock.elapsedRealtime() - t0) / todo.size
                val bad = todo.count { exportStatus[it.id]?.startsWith("fail") == true }
                progress = 1f
                if (bad == 0) {
                    stage = Stage.DONE
                    ClipService.done(ctx, "Clips ready", "${todo.size} clips were exported and checked.")
                } else {
                    error = "$bad of ${todo.size} clips could not be exported. Tap Retry to try them again."
                    failed = true
                    resumeAction = Resume.EXPORT
                    stage = if (results.isEmpty()) Stage.REVIEW else Stage.DONE
                    ClipService.done(ctx, "Some clips failed", "Open ClipForge AI and tap Retry.")
                }
                persist()
            } catch (e: CancellationException) {
                stage = Stage.REVIEW
                paused = true
                resumeAction = Resume.EXPORT
                persist()
                throw e
            } catch (e: Throwable) {
                error = "Export failed: ${e.message ?: e}"
                failed = true
                resumeAction = Resume.EXPORT
                stage = if (results.isEmpty()) Stage.REVIEW else Stage.DONE
                persist()
                ClipService.done(ctx, "Export failed", "Open ClipForge AI and tap Retry.")
            } finally {
                end()
            }
        }
    }

    /** Copies the checked file into Movies/ClipForge and confirms the saved copy has the same size. */
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
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val saved = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            if (saved != file.length()) {
                throw IllegalStateException("Saving to the Gallery failed (the saved file is incomplete). Check free storage.")
            }
            return uri
        } catch (e: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    fun removeFromLibrary(c: LibraryClip) {
        library.remove(c)
        store.saveLibrary(library.toList())
    }

    fun clearCache(): String {
        var n = 0L
        val keep = listOfNotNull(videoUri?.path, audioUri?.path)
        for (d in listOf(ctx.cacheDir, File(ctx.filesDir, "downloads"))) {
            d.listFiles()?.forEach { f ->
                if (f.absolutePath !in keep) { n += f.length(); f.deleteRecursively() }
            }
        }
        return "Freed ${n / (1024 * 1024)} MB (the video of your current job was kept)"
    }

    /** Stops the running step. The job stays saved so it can be resumed. */
    fun cancel() {
        job?.cancel()
        if (stage == Stage.DOWNLOADING) dlJob?.cancel()
    }

    fun backToReview() {
        stage = Stage.REVIEW
        persist()
    }

    fun startOver() {
        job?.cancel()
        dlJob?.cancel()
        stage = Stage.PICK
        videoUri = null
        audioUri = null
        videoReady = true
        jobUrl = ""
        resetResults()
        error = null
        editIdx = -1
        timings.clear()
        JobStore.clear(ctx)
    }
}
