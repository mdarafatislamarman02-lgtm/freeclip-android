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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

    /** The real step the job is in: Fetching video, Downloading video, Analyzing video, Detecting viral moments, Generating clips, Rendering clips, Saving clips, Completed, Failed. */
    var phase by mutableStateOf("")
    /** SystemClock.elapsedRealtime() when the current job started; the screen shows the elapsed time from it. */
    var jobStartMs by mutableStateOf(0L)
    var notice by mutableStateOf<String?>(null)
    var libraryMsg by mutableStateOf<String?>(null)

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
    private var lastProgressMs = 0L
    private var lastSig = ""
    private var stallMsg: String? = null
    private var watchdog: Job? = null
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
        refreshLibrary()
    }

    // ------------------------------------------------------------ background service + notification

    private fun begin() {
        if (active == 0) {
            jobStartMs = SystemClock.elapsedRealtime()
            lastProgressMs = jobStartMs
            lastSig = ""
            stallMsg = null
            startWatchdog()
        }
        active++
        queued = false
        ClipService.start(ctx)
    }

    private fun end() {
        active = maxOf(0, active - 1)
        if (active == 0) {
            watchdog?.cancel()
            ClipService.stop(ctx)
        }
    }

    private fun report(msg: String, p: Float) {
        status = msg
        progress = p
        touch("$msg|${(p * 10000).toInt()}")
        ClipService.update(ctx, msg, (p * 100).toInt())
    }

    /** Remembers that something really changed (used to detect a job that has stopped making progress). */
    private fun touch(sig: String) {
        if (sig != lastSig) {
            lastSig = sig
            lastProgressMs = SystemClock.elapsedRealtime()
        }
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (true) {
                delay(10_000)
                if (active == 0) break
                val idle = SystemClock.elapsedRealtime() - lastProgressMs
                val bgDownload = dlActive && stage != Stage.ANALYZING && stage != Stage.RENDERING
                if (idle > (if (bgDownload) DL_STALL_MS else STALL_MS)) {
                    onStall(idle)
                    break
                }
            }
        }
    }

    /** No change for several minutes: stop the step, say so, and offer Retry (finished work is kept). */
    private var dlRestarts = 0

    private fun onStall(idleMs: Long) {
        // The video download alone stalled while the clips are already found: restart just the download once, keep everything else.
        if (dlActive && (stage == Stage.READY || stage == Stage.REVIEW) && audioUri != null && !videoReady && dlRestarts < 1) {
            dlRestarts++
            Diag.log(ctx, "STALL in background video download, restarting it (clips are kept)")
            val old = dlJob
            notice = "The video download stalled, so it was restarted. Your clips are kept."
            scope.launch {
                old?.cancelAndJoin()
                downloadVideoOnly()
            }
            return
        }
        val onlyVideo = audioUri != null && !videoReady && (stage == Stage.READY || stage == Stage.REVIEW)
        val msg = if (onlyVideo) "The video download stopped (no progress for ${idleMs / 60000} minutes). Your clips are kept. Tap Retry to download the video again, then export."
        else "No progress for ${idleMs / 60000} minutes during \"${phase.ifEmpty { status }}\", so this step was stopped. Tap Retry (finished work is kept)."
        Diag.log(ctx, "STALL phase=$phase status=$status stage=$stage")
        stallMsg = msg
        val a = when {
            stage == Stage.RENDERING -> Resume.EXPORT
            stage == Stage.ANALYZING -> Resume.ANALYZE
            else -> Resume.DOWNLOAD
        }
        job?.cancel()
        dlJob?.cancel()
        // Make the screen correct even if a native call ignores the cancel.
        error = msg
        failed = true
        paused = false
        resumeAction = a
        stage = when (stage) {
            Stage.RENDERING -> if (results.isEmpty()) Stage.REVIEW else Stage.DONE
            Stage.ANALYZING -> Stage.READY
            Stage.DOWNLOADING -> if (audioUri != null) Stage.READY else Stage.PICK
            else -> stage
        }
        phase = "Failed"
        dlActive = false
        active = 0
        ClipService.stop(ctx)
        ClipService.done(ctx, "Stopped: no progress", "Open ClipForge AI and tap Retry.")
        persist()
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
                if (audioUri != null && !videoReady && jobUrl.isNotEmpty()) downloadVideoOnly()
                else {
                    linkText = jobUrl.ifEmpty { linkText }
                    getClips(force = true)
                }
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
        dlRestarts = 0
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
        phase = "Fetching video"
        notice = null
        dlProgress = 0f
        queued = true
        val t0 = SystemClock.elapsedRealtime()
        dlActive = true
        dlJob = scope.launch {
            begin()
            try {
                Diag.log(ctx, "FETCH start $url")
                var r: Links.Result? = null
                for (attempt in 1..2) {
                    try {
                        r = Links.fetch(
                            ctx, url,
                            { msg, p ->
                                if (msg.contains("ownload", ignoreCase = true)) phase = "Downloading video"
                                report(msg, p)
                            },
                            early = { f, title, dur -> phase = "Downloading video"; scope.launch { openAudioFirst(f, title, dur) } },
                            onVideo = {
                                dlProgress = it
                                touch("v${(it * 10000).toInt()}")
                                ClipService.update(ctx, "Downloading the video...", (it * 100).toInt())
                            },
                        )
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        Diag.log(ctx, "FETCH attempt $attempt failed", e)
                        if (attempt == 2) throw e
                        report("Connection problem, trying again...", 0f)
                        delay(3000)
                    }
                }
                val got = r!!
                timings["download"] = SystemClock.elapsedRealtime() - t0
                Diag.log(ctx, "FETCH done ${got.file.length() / 1024} KB in ${timings["download"]} ms")
                if (audioUri != null && !videoReady) attachVideo(got.file) else openLocal(Uri.fromFile(got.file), got.title)
                phase = "Ready"
                persist()
            } catch (e: CancellationException) {
                if (stallMsg == null) {
                    if (audioUri == null) stage = Stage.PICK
                    phase = ""
                }
                throw e
            } catch (e: Throwable) {
                Diag.log(ctx, "FETCH failed", e)
                error = (e.message ?: e.toString()) + if (audioUri != null) " The video download stopped; tap Resume to try again." else ""
                failed = true
                phase = "Failed"
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

    /** Downloads only the video again (after a stall or failure) without touching the transcript or the clips that were found. */
    private fun downloadVideoOnly() {
        if (dlActive) return
        error = null
        failed = false
        paused = false
        phase = "Downloading video"
        status = "Downloading the video again..."
        dlProgress = 0f
        dlActive = true
        dlJob = scope.launch {
            begin()
            try {
                Diag.log(ctx, "VIDEO retry start")
                val got = Links.fetch(ctx, jobUrl, { msg, p -> phase = "Downloading video"; report(msg, p) })
                attachVideo(got.file)
                Diag.log(ctx, "VIDEO retry done ${got.file.length() / 1024} KB")
                phase = if (stage == Stage.REVIEW) "Clips ready to review" else "Ready"
                notice = "The video is downloaded. You can export now."
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diag.log(ctx, "VIDEO retry failed", e)
                error = "The video could not be downloaded: ${e.message ?: e.javaClass.simpleName}. Your clips are kept. Tap Retry."
                failed = true
                phase = "Failed"
                resumeAction = Resume.DOWNLOAD
                persist()
            } finally {
                dlActive = false
                end()
            }
        }
    }

    /** The audio of a link is ready: show the options now; the video keeps downloading in the background. */
    private fun openAudioFirst(f: File, title: String, durS: Double) {
        Diag.log(ctx, "AUDIO ready ${durS.toInt()}s")
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
        Diag.log(ctx, "VIDEO ready ${f.length() / 1024} KB")
        videoUri = Uri.fromFile(f)
        videoReady = true
        paused = false
        failed = false
        scope.launch { maybeAutoExport() }
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
        phase = "Analyzing video"
        notice = null
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
                    val onStatus: (String, Float) -> Unit = { msg, p ->
                        report(msg, p)
                        if (p > 0.32f) eta = etaText(t0, p)
                    }
                    val cfg = when (store.stt) {
                        1 -> store.groqKey.takeIf { it.isNotBlank() }?.let { Speech.CloudCfg(Cloud.Stt.GROQ, it, store.lang, store.captionEnglish && store.lang != "en") }
                        2 -> store.deepgramKey.takeIf { it.isNotBlank() }?.let { Speech.CloudCfg(Cloud.Stt.DEEPGRAM, it, store.lang, false) }
                        else -> null
                    }
                    Diag.log(ctx, "ANALYZE start stt=${cfg?.stt?.label ?: "Offline"} lang=${store.lang} translateToEnglish=${cfg?.translate == true} range=${rangeA.toInt()}-${rangeB.toInt()}s")
                    if (cfg == null && store.lang != "en") notice = "Offline mode understands English only, so it cannot detect other languages. For automatic language detection add a free Groq key in Settings > Faster, better results."
                    val cloudTr: Analysis? = if (cfg == null) null else try {
                        Speech.analyze(ctx, uri, durationS, rangeA.toDouble(), rangeB.toDouble(), onStatus, cloud = cfg)
                            .also {
                                notice = "Transcript made with ${cfg.stt.label}" + (if (cfg.translate) ", captions translated to English." else ".")
                                Diag.log(ctx, "ANALYZE cloud done, language=${Cloud.lastLang.ifEmpty { "n/a" }}")
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Diag.log(ctx, "CLOUD STT failed, using offline", e)
                        notice = "${cfg.stt.label} failed (${e.message ?: e.javaClass.simpleName}), so the offline method was used."
                        report("Cloud failed, using the offline method...", 0f)
                        null
                    }
                    val tr = cloudTr ?: try {
                        Speech.analyze(ctx, uri, durationS, rangeA.toDouble(), rangeB.toDouble(), onStatus)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // The parallel path failed on this phone: use the plain single-pass transcription instead.
                        Diag.log(ctx, "ANALYZE parallel failed, retrying single part", e)
                        report("Retrying with the simple method...", 0f)
                        Speech.analyze(ctx, uri, durationS, rangeA.toDouble(), rangeB.toDouble(), onStatus, maxParts = 1)
                    }
                    if (cloudTr == null && store.lang != "en" && tr.words.size >= 20 && tr.meanConf < 0.8) {
                        // Heuristic (threshold not verified on real devices): the English-only model is unsure about most words.
                        Diag.log(ctx, "LOWCONF offline meanConf=${"%.2f".format(tr.meanConf)} words=${tr.words.size}")
                        notice = "This video does not sound like English, so the captions and titles may be wrong. For Hindi and other languages add a free Groq key in Settings > Faster, better results, then analyse again."
                    }
                    tr.also {
                        analysis = it
                        analysisA = rangeA
                        analysisB = rangeB
                        timings["analyze"] = SystemClock.elapsedRealtime() - t0
                        withContext(Dispatchers.IO) { JobStore.saveAnalysis(ctx, it) }
                    }
                }
                stepIdx = 2
                phase = "Detecting viral moments"
                report("Finding interesting moments...", 1f)
                val len = LENGTHS[lengthIdx]
                var found = Moments.find(a.words, a.energy, a.hop, durationS, nClips, len.min, len.max, Moments.keywords(prompt))
                stepIdx = 3
                phase = "Generating clips"
                report("Ranking the clips...", 1f)
                if (found.isNotEmpty() && store.titleAi != 0) {
                    report("Writing better titles with AI...", 1f)
                    found = withContext(Dispatchers.IO) { Cloud.improveTitles(ctx, store, found) }
                }
                exportStatus.clear()
                results.clear()
                moments.clear()
                moments.addAll(found)
                Diag.log(ctx, "ANALYZE done words=${a.words.size} moments=${found.size} in ${(SystemClock.elapsedRealtime() - t0) / 1000}s")
                if (found.isEmpty()) {
                    error = "No clear speech was found in this part of the video (${a.words.size} words heard). Try a different range, or a video with talking in English."
                    phase = "Failed"
                    stage = Stage.READY
                } else {
                    phase = "Clips ready to review"
                    stage = Stage.REVIEW
                    scope.launch { maybeAutoExport() }
                    ClipService.done(ctx, "Clips found", "${found.size} clips are ready to review.")
                }
                persist()
            } catch (e: CancellationException) {
                if (stallMsg == null) {
                    stage = Stage.READY
                    phase = ""
                    persist()
                }
                throw e
            } catch (e: Throwable) {
                Diag.log(ctx, "ANALYZE failed", e)
                phase = "Failed"
                error = "Analysis failed: ${e.javaClass.simpleName}: ${e.message ?: e}"
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

    fun redesc(i: Int, d: String) {
        moments[i] = moments[i].copy(desc = d)
    }

    fun retags(i: Int, t: String) {
        moments[i] = moments[i].copy(tags = t)
    }

    /** Copies "title, description, hashtags" to the clipboard, ready to paste into TikTok / Shorts / Reels. */
    fun copyPost(title: String, desc: String, tags: String) {
        val txt = listOf(title, desc, tags).filter { it.isNotBlank() }.joinToString("\n\n")
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ClipForge post", txt))
        android.widget.Toast.makeText(ctx, "Copied title, description and hashtags.", android.widget.Toast.LENGTH_SHORT).show()
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
        phase = "Rendering clips"
        notice = null
        progress = 0f
        eta = ""
        queued = true
        persist()
        val t0 = SystemClock.elapsedRealtime()
        job = scope.launch {
            begin()
            try {
                var simpleCount = 0
                var noCam = 0
                val hasAudio = withContext(Dispatchers.IO) { ExportCheck.sourceHasAudio(ctx, uri) }
                for ((n, m) in todo.withIndex()) {
                    report("Clip ${n + 1} of ${todo.size}: ${layout.label}, ${ratio.label}, ${res}p", n.toFloat() / todo.size)
                    var ok = false
                    var lastErr = ""
                    for (attempt in 1..2) {
                        val tmp = File(ctx.cacheDir, "clip_${m.id}.mp4")
                        try {
                            tmp.delete()
                            phase = "Rendering clips"
                            val face = try {
                                if (layout == LayoutMode.FILL || layout.needsCam) faceFor(m) else FaceInfo.NONE
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Diag.log(ctx, "FACE failed (using centre crop)", e)
                                FaceInfo.NONE
                            }
                            if ((layout == LayoutMode.GAMING || layout == LayoutMode.FACECAM) && Faces.effective(layout, face, corner) == LayoutMode.FILL) {
                                noCam++
                                Diag.log(ctx, "LAYOUT clip ${m.id}: no facecam found, using the full frame")
                            }
                            // Attempt 1: the full layout. Attempt 2: the plain centre-crop path that worked in the first version.
                            val simple = attempt == 2
                            Renderer.export(ctx, uri, m.start, m.end, words, m.title, specFor(face), tmp, safe = simple) { p ->
                                val all = (n + p) / todo.size
                                report("Clip ${n + 1} of ${todo.size}: ${layout.label}, ${ratio.label}, ${res}p", all)
                                if (all > 0.05f) eta = etaText(t0, all)
                            }
                            report("Checking clip ${n + 1} of ${todo.size}...", (n + 1f) / todo.size)
                            val chk = withContext(Dispatchers.IO) { ExportCheck.verify(tmp, m.end - m.start, hasAudio) }
                            if (!chk.ok) throw IllegalStateException(chk.message)
                            phase = "Saving clips"
                            report("Saving clip ${n + 1} of ${todo.size}...", (n + 1f) / todo.size)
                            val saved = withContext(Dispatchers.IO) { saveToGallery(tmp, m.title) }
                            if (simple) simpleCount++
                            // Exporting the same part again replaces the old copy instead of adding another one.
                            val same = library.filter { it.source == videoName && it.startS >= 0 && kotlin.math.abs(it.startS - m.start) < 1.0 && kotlin.math.abs(it.endS - m.end) < 1.0 }
                            for (o in same) { deleteQuiet(o.uri); library.remove(o) }
                            val clip = LibraryClip(
                                id = System.currentTimeMillis().toString() + "_" + m.id,
                                title = m.title, uri = saved.toString(), seconds = m.end - m.start,
                                created = System.currentTimeMillis(), layout = layout.label, score = m.score, source = videoName, startS = m.start, endS = m.end,
                                desc = m.desc, tags = m.tags,
                            )
                            results.add(clip)
                            library.add(0, clip)
                            store.saveLibrary(library.toList())
                            exportStatus[m.id] = "ok"
                            ok = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            lastErr = "${e.javaClass.simpleName}: ${e.message ?: ""}".trim().trimEnd(':')
                            Diag.log(ctx, "EXPORT clip ${m.id} attempt $attempt failed", e)
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
                Diag.log(ctx, "EXPORT done ok=${todo.size - bad} failed=$bad simple=$simpleCount")
                if (noCam > 0) notice = "$noCam clip(s) have no separate facecam, so they use the full frame instead of showing the same picture twice. If your video has a facecam, pick its corner under \"Where is the facecam\"."
                if (simpleCount > 0) notice = "$simpleCount clip(s) were made with the simple centre crop because the full layout could not be rendered on this phone."
                if (bad == 0) {
                    phase = "Completed"
                    stage = Stage.DONE
                    ClipService.done(ctx, "Clips ready", "${todo.size} clips were exported and checked.")
                } else {
                    phase = "Failed"
                    error = "$bad of ${todo.size} clips could not be exported. Tap Retry to try them again."
                    failed = true
                    resumeAction = Resume.EXPORT
                    stage = if (results.isEmpty()) Stage.REVIEW else Stage.DONE
                    ClipService.done(ctx, "Some clips failed", "Open ClipForge AI and tap Retry.")
                }
                persist()
            } catch (e: CancellationException) {
                if (stallMsg == null) {
                    stage = Stage.REVIEW
                    paused = true
                    phase = ""
                    resumeAction = Resume.EXPORT
                    persist()
                }
                throw e
            } catch (e: Throwable) {
                Diag.log(ctx, "EXPORT failed", e)
                phase = "Failed"
                error = "Export failed: ${e.javaClass.simpleName}: ${e.message ?: e}"
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

    /** Copies the checked file into Movies/ClipForge and confirms the saved copy is really there and not truncated. */
    private fun saveToGallery(file: File, title: String): Uri =
        saveViaMediaStore(file, title, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/ClipForge", "ClipForge")

    private fun downloadExists(name: String): Boolean = try {
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "Download/ClipForge%"), null,
        )?.use { it.moveToFirst() } ?: false
    } catch (_: Exception) { false }

    private fun saveViaMediaStore(file: File, title: String, collection: Uri, folder: String, prefix: String, fixedName: String? = null): Uri {
        val resolver = ctx.contentResolver
        val safe = title.replace(Regex("[^A-Za-z0-9 ]"), "").trim().take(30).ifEmpty { "clip" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fixedName ?: "${prefix}_${System.currentTimeMillis() / 1000}_$safe.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("The phone refused to create the file in $folder (storage full or blocked).")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Could not open the destination file.")
            out.use { o -> file.inputStream().use { it.copyTo(o) }; o.flush() }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            // The copy must exist and not be shorter than the source. Some phones report the size late or as 0,
            // so a real read of the file is used as the final proof instead of trusting one number.
            val stat = try { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L } catch (_: Exception) { -1L }
            var q = -1L
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) q = c.getLong(0)
            }
            val reported = maxOf(stat, q)
            if (reported in 1 until file.length()) {
                throw IllegalStateException("The saved file is incomplete ($reported of ${file.length()} bytes). Check free storage.")
            }
            if (reported <= 0L) {
                val n = resolver.openInputStream(uri)?.use { it.read(ByteArray(4096)) } ?: -1
                if (n <= 0) throw IllegalStateException("The saved file could not be read back.")
            }
            Diag.log(ctx, "SAVED ${file.length() / 1024} KB to $folder as $uri")
            return uri
        } catch (e: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    /** "Save to Device": an extra copy of a library clip in Downloads/ClipForge, checked before it says Saved. */
    fun saveCopy(c: LibraryClip) {
        libraryMsg = "Saving a copy of \"${c.title}\"..."
        scope.launch {
            try {
                val msg = withContext(Dispatchers.IO) {
                    val tmp = File(ctx.cacheDir, "copy_${c.id}.mp4")
                    val dlName = "ClipForge_${c.id}.mp4"
                    if (downloadExists(dlName)) return@withContext "Already saved in Downloads/ClipForge. (The Gallery also shows the copy in Movies/ClipForge, so one clip can appear twice there.)"
                    try {
                        ctx.contentResolver.openInputStream(Uri.parse(c.uri))?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                            ?: throw IllegalStateException("The clip file could not be opened. It may have been deleted from the Gallery.")
                        if (tmp.length() < 20_000) throw IllegalStateException("The clip file is empty or damaged.")
                        saveViaMediaStore(tmp, c.title, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/ClipForge", "ClipForge", dlName)
                        "Saved a copy to Downloads/ClipForge (${tmp.length() / 1024} KB). The Gallery also shows the Movies/ClipForge copy."
                    } finally {
                        tmp.delete()
                    }
                }
                libraryMsg = msg
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                Diag.log(ctx, "SAVE COPY failed", e)
                libraryMsg = "Could not save: ${e.message ?: e.javaClass.simpleName}. Tap the download icon to try again."
                android.widget.Toast.makeText(ctx, libraryMsg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Loads clips that are in Movies/ClipForge but missing from the list, and drops entries whose file was deleted. */
    fun refreshLibrary() {
        scope.launch {
            val found = withContext(Dispatchers.IO) { scanGallery() }
            val known = library.map { it.uri }.toSet()
            var changed = false
            for (c in found) if (c.uri !in known) { library.add(c); changed = true }
            val gone = withContext(Dispatchers.IO) { library.filter { !fileExists(it.uri) } }
            if (gone.isNotEmpty()) { library.removeAll(gone.toSet()); changed = true }
            if (changed) {
                library.sortByDescending { it.created }
                store.saveLibrary(library.toList())
            }
        }
    }

    private fun fileExists(uri: String): Boolean {
        return try {
            ctx.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: true
        } catch (_: java.io.FileNotFoundException) {
            false
        } catch (_: Exception) {
            true // unknown: keep the entry rather than lose a clip
        }
    }

    private fun scanGallery(): List<LibraryClip> {
        val out = ArrayList<LibraryClip>()
        try {
            val proj = arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED,
            )
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("Movies/ClipForge%", "ClipForge_%"), "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "clip"
                    val title = name.removePrefix("ClipForge_").substringAfter('_', name).removeSuffix(".mp4").ifBlank { "Clip" }
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    out.add(LibraryClip("gal_$id", title, uri.toString(), c.getLong(2) / 1000.0, c.getLong(3) * 1000, "Saved", 0))
                }
            }
        } catch (e: Exception) {
            Diag.log(ctx, "GALLERY scan failed", e)
        }
        return out
    }

    private var autoExportedFor = ""

    /** Like Opus: once the clips are found AND the video is here, export them without waiting for a tap. */
    private fun maybeAutoExport() {
        if (!store.autoExport) return
        if (stage != Stage.REVIEW || !videoReady || moments.isEmpty()) return
        if (results.isNotEmpty() || exportStatus.isNotEmpty()) return
        val key = "$jobUrl|${moments.size}|${moments.first().start.toInt()}"
        if (key == autoExportedFor) return
        autoExportedFor = key
        Diag.log(ctx, "AUTO-EXPORT ${moments.count { it.selected }} clips")
        render()
        if (stage == Stage.RENDERING) notice = "Your clips are being exported automatically. They will appear in My Clips."
    }

    private fun deleteQuiet(uri: String) {
        try { ctx.contentResolver.delete(Uri.parse(uri), null, null) } catch (_: Exception) {}
    }

    private fun dupKey(c: LibraryClip) = "${c.source}|${c.title}|${Math.round(c.seconds)}"

    /** Clips that are the same clip saved more than once (same video, title and length). The newest of each stays. */
    fun duplicates(): List<LibraryClip> =
        library.groupBy { dupKey(it) }.values.flatMap { g -> g.sortedByDescending { it.created }.drop(1) }

    fun removeDuplicates() {
        val d = duplicates()
        for (c in d) { deleteQuiet(c.uri); library.remove(c) }
        store.saveLibrary(library.toList())
        libraryMsg = if (d.isEmpty()) "No duplicates found." else "Removed ${d.size} duplicate clip(s). The newest copy of each is kept."
    }

    fun removeFromLibrary(c: LibraryClip, deleteFile: Boolean = false) {
        if (deleteFile) {
            try {
                ctx.contentResolver.delete(Uri.parse(c.uri), null, null)
            } catch (e: Exception) {
                Diag.log(ctx, "DELETE file failed", e)
                libraryMsg = "Could not delete the video file (${e.javaClass.simpleName}). It was removed from the list only."
            }
        }
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
        phase = ""
        notice = null
        JobStore.clear(ctx)
    }

    companion object {
        /** A step that shows no change at all for this long is treated as stalled. */
        const val STALL_MS = 4 * 60 * 1000L
        /** The background video download gets longer (big files, slow phones, muxing). */
        const val DL_STALL_MS = 8 * 60 * 1000L
    }
}
