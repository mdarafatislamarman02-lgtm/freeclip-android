package app.freeclip

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat as NpFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.min

/** Minimal HTTP client that NewPipeExtractor needs to talk to YouTube. */
class AppDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        conn.requestMethod = request.httpMethod()
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", Links.UA)
        for ((k, v) in request.headers()) {
            if (k.equals("Accept-Encoding", ignoreCase = true)) continue // we do not decompress
            conn.setRequestProperty(k, v.joinToString(", "))
        }
        val data = request.dataToSend()
        if (data != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(data) }
        }
        val code = conn.responseCode
        if (code == 429) {
            conn.disconnect()
            throw ReCaptchaException("YouTube asked for a captcha", request.url())
        }
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val headers = conn.headerFields.entries.filter { it.key != null }.associate { it.key!! to it.value }
        val latest = conn.url.toString()
        val message = conn.responseMessage ?: ""
        conn.disconnect()
        return Response(code, message, headers, body, latest)
    }
}

/** Gets a video onto the phone from a pasted link: YouTube, Google Drive, Dropbox or a direct file link. */
object Links {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private const val CHUNK = 10L * 1024 * 1024
    private const val MAX_YT_SECONDS = 90 * 60

    class Result(val file: File, val title: String)

    private var npReady = false

    fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ']')

    private fun host(u: String): String = runCatching { Uri.parse(u).host?.lowercase() }.getOrNull() ?: ""

    fun isYouTube(u: String): Boolean {
        val h = host(u)
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com")
    }

    /** Turns share links (Drive, Dropbox) into direct-download links. */
    fun normalize(u: String): String {
        val uri = Uri.parse(u)
        val h = host(u)
        if (h == "drive.google.com") {
            val id = Regex("/file/d/([^/]+)").find(uri.path ?: "")?.groupValues?.get(1)
                ?: uri.getQueryParameter("id")
            if (id != null) return "https://drive.google.com/uc?export=download&id=$id"
        }
        if (h.endsWith("dropbox.com")) {
            val b = uri.buildUpon().clearQuery()
            for (n in uri.queryParameterNames) {
                if (n != "dl") b.appendQueryParameter(n, uri.getQueryParameter(n))
            }
            b.appendQueryParameter("dl", "1")
            return b.build().toString()
        }
        return u
    }

    suspend fun fetch(ctx: Context, rawUrl: String, onStatus: (String, Float) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val cc = currentCoroutineContext()
            val check = { cc.ensureActive() }
            val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // keep only the current video
            if (!rawUrl.startsWith("http")) throw IllegalArgumentException("Paste a full link that starts with https://")

            if (isYouTube(rawUrl)) youtube(rawUrl, dir, onStatus, check) else direct(normalize(rawUrl), dir, onStatus, check)
        }

    // ------------------------------------------------------------------ direct links

    private fun direct(url: String, dir: File, onStatus: (String, Float) -> Unit, check: () -> Unit): Result {
        onStatus("Downloading the video...", 0f)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("The link answered with an error (HTTP ${conn.responseCode}). Check that it is public.")
            }
            val type = conn.contentType ?: ""
            if (type.startsWith("text/html")) {
                throw IllegalStateException(
                    "That link opens a web page, not a video file. For Google Drive or Dropbox, " +
                        "share it as 'Anyone with the link'. Very large Drive files can't be fetched; use Upload instead."
                )
            }
            val name = Uri.parse(url).lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') } ?: "video.mp4"
            val file = File(dir, "link_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60))
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onStatus("Downloading the video...", done.toFloat() / total)
                    }
                }
            }
            return Result(file, name.substringBeforeLast('.'))
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ YouTube

    private fun youtube(url: String, dir: File, onStatus: (String, Float) -> Unit, check: () -> Unit): Result {
        onStatus("Reading the YouTube link...", 0f)
        if (!npReady) {
            NewPipe.init(AppDownloader())
            npReady = true
        }
        val info = try {
            StreamInfo.getInfo(url)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Could not read that YouTube link (${e.message ?: e.javaClass.simpleName}). " +
                    "It may be private, age-restricted or blocked. Download it another way and use Upload."
            )
        }
        if (info.streamType != StreamType.VIDEO_STREAM) throw IllegalStateException("Live streams can't be imported. Use a finished video.")
        if (info.duration > MAX_YT_SECONDS) throw IllegalStateException("That video is over 90 minutes, which is too long for a phone. Try a shorter one.")

        val prog = DeliveryMethod.PROGRESSIVE_HTTP
        val vo = info.videoOnlyStreams
            .filter { it.deliveryMethod == prog && it.format == NpFormat.MPEG_4 && it.height in 1..1080 }
            .maxByOrNull { it.height }
        val au = info.audioStreams
            .filter { it.deliveryMethod == prog && it.format == NpFormat.M4A }
            .maxByOrNull { it.averageBitrate }
        val title = info.name ?: "YouTube video"

        if (vo != null && au != null) {
            val v = File(dir, "yt_video.mp4")
            val a = File(dir, "yt_audio.m4a")
            val out = File(dir, "yt_${System.currentTimeMillis() / 1000}.mp4")
            onStatus("Downloading video (${vo.height}p)...", 0f)
            fetchFile(vo.content, v, check) { onStatus("Downloading video (${vo.height}p)...", it * 0.8f) }
            onStatus("Downloading audio...", 0.8f)
            fetchFile(au.content, a, check) { onStatus("Downloading audio...", 0.8f + it * 0.15f) }
            onStatus("Putting video and audio together...", 0.95f)
            mux(v, a, out)
            v.delete()
            a.delete()
            return Result(out, title)
        }

        val single = info.videoStreams.filter { it.deliveryMethod == prog }.maxByOrNull { it.height }
            ?: throw IllegalStateException("YouTube did not offer a downloadable stream for this video. Download it another way and use Upload.")
        val out = File(dir, "yt_${System.currentTimeMillis() / 1000}.mp4")
        onStatus("Downloading video (${single.height}p)...", 0f)
        fetchFile(single.content, out, check) { onStatus("Downloading video (${single.height}p)...", it) }
        return Result(out, title)
    }

    /** Downloads a googlevideo URL in 10 MB pieces (avoids slow throttled single requests). */
    private fun fetchFile(url: String, dest: File, check: () -> Unit, onProgress: (Float) -> Unit) {
        val clen = Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()
        var done = 0L
        FileOutputStream(dest).use { out ->
            if (clen == null || clen <= 0) {
                get(url, out, check) { done += it }
            } else {
                var pos = 0L
                while (pos < clen) {
                    val end = min(pos + CHUNK - 1, clen - 1)
                    get("$url&range=$pos-$end", out, check) {
                        done += it
                        onProgress((done.toFloat() / clen).coerceIn(0f, 1f))
                    }
                    pos = end + 1
                }
            }
        }
    }

    private fun get(url: String, out: OutputStream, check: () -> Unit, onBytes: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("YouTube refused the download (HTTP ${conn.responseCode}). Try another video or use Upload.")
            }
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    check()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onBytes(n)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ muxing (no re-encode)

    private fun firstTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        throw IllegalStateException("Downloaded file has no $prefix track.")
    }

    private fun copyTrack(ex: MediaExtractor, muxer: MediaMuxer, idx: Int) {
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            info.offset = 0
            info.size = n
            info.presentationTimeUs = ex.sampleTime
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(idx, buf, info)
            ex.advance()
        }
    }

    private fun mux(video: File, audio: File, out: File) {
        val ve = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val ae = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vt = firstTrack(ve, "video/")
            val at = firstTrack(ae, "audio/")
            ve.selectTrack(vt)
            ae.selectTrack(at)
            val vi = muxer.addTrack(ve.getTrackFormat(vt))
            val ai = muxer.addTrack(ae.getTrackFormat(at))
            muxer.start()
            copyTrack(ve, muxer, vi)
            copyTrack(ae, muxer, ai)
            muxer.stop()
        } finally {
            muxer.release()
            ve.release()
            ae.release()
        }
    }
}
