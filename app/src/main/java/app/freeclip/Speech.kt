package app.freeclip

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

/** Offline speech-to-text (Vosk) with word timestamps. The audio is split into parts that are transcribed in parallel. */
object Speech {
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "models/$MODEL_NAME")

    /** Downloads the ~40 MB model once. Returns its folder. */
    private fun ensureModel(ctx: Context, onProgress: (Float) -> Unit): File {
        val dir = modelDir(ctx)
        if (File(dir, "am/final.mdl").exists()) return dir

        val zip = File(ctx.cacheDir, "$MODEL_NAME.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) {
                throw IllegalStateException("Could not download the speech model (HTTP ${conn.responseCode}). Check your internet connection.")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val target = File(ctx.filesDir, "models").apply { mkdirs() }
        val targetPath = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(target, e.name)
                if (!out.canonicalPath.startsWith(targetPath)) continue // zip-slip guard
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                }
            }
        }
        zip.delete()
        if (!File(dir, "am/final.mdl").exists()) {
            throw IllegalStateException("The speech model downloaded but looks incomplete. Try again.")
        }
        return dir
    }

    /**
     * Transcribes [startS, endS] of the video. Progress: model 0-10%, audio decode 10-30%, speech 30-100%.
     * Word times and the loudness curve are in VIDEO time.
     */
    suspend fun analyze(
        ctx: Context,
        uri: Uri,
        durationS: Double,
        startS: Double,
        endS: Double,
        onStatus: (String, Float) -> Unit,
    ): Analysis = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()

        onStatus("Getting the speech model (first time only)...", 0f)
        val dir = ensureModel(ctx) { onStatus("Downloading speech model (first time only)...", it * 0.1f) }
        job.ensureActive()

        val pcm = File(ctx.cacheDir, "audio16k.pcm")
        onStatus("Reading the audio...", 0.1f)
        val dec = AudioDecoder.decodeToFile(
            ctx, uri,
            (startS * 1e6).toLong(), (endS * 1e6).toLong(), ((endS - startS) * 1e6).toLong(),
            pcm,
            { onStatus("Reading the audio...", 0.1f + 0.2f * it) },
            { job.ensureActive() },
        )

        onStatus("Loading the speech model...", 0.3f)
        val model = Model(dir.absolutePath)
        val words = ArrayList<Word>()
        try {
            val totalBytes = dec.samples * 2
            val parts = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            val partBytes = ((totalBytes / parts) / 2) * 2
            val overlapBytes = AudioDecoder.RATE * 2L * 2 // 2 s of overlap so words on a cut are not lost
            val done = AtomicLong(0)

            val results = coroutineScope {
                (0 until parts).map { k ->
                    async(Dispatchers.Default) {
                        val keepLo = k * partBytes
                        val keepHi = if (k == parts - 1) totalBytes else (k + 1) * partBytes
                        val from = max(0L, keepLo - overlapBytes) / 2 * 2
                        val to = min(totalBytes, keepHi + overlapBytes / 2) / 2 * 2
                        val out = ArrayList<Word>()
                        val rec = Recognizer(model, AudioDecoder.RATE.toFloat())
                        rec.setWords(true)
                        fun collect(json: String) {
                            val arr = JSONObject(json).optJSONArray("result") ?: return
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                val s = o.getDouble("start") + from / 2.0 / AudioDecoder.RATE + dec.baseS
                                val e = o.getDouble("end") + from / 2.0 / AudioDecoder.RATE + dec.baseS
                                val mid = ((s - dec.baseS + e - dec.baseS) / 2 * AudioDecoder.RATE * 2).toLong()
                                if (mid >= keepLo && mid < keepHi) out.add(Word(o.getString("word"), s, e))
                            }
                        }
                        try {
                            RandomAccessFile(pcm, "r").use { f ->
                                f.seek(from)
                                var left = to - from
                                val buf = ByteArray(8000)
                                while (left > 0) {
                                    ensureActive()
                                    val n = f.read(buf, 0, min(left, buf.size.toLong()).toInt())
                                    if (n <= 0) break
                                    if (rec.acceptWaveForm(buf, n)) collect(rec.result)
                                    left -= n
                                    val d = done.addAndGet(n.toLong())
                                    if (k == 0) {
                                        val frac = (d.toFloat() / max(1L, totalBytes + overlapBytes * parts / 2)).coerceIn(0f, 1f)
                                        onStatus("Listening to the video...", 0.3f + 0.7f * frac)
                                    }
                                }
                            }
                            collect(rec.finalResult)
                        } finally {
                            rec.close()
                        }
                        out
                    }
                }.awaitAll()
            }
            for (r in results) words.addAll(r)
        } finally {
            model.close()
            pcm.delete()
        }
        words.sortBy { it.s }

        // Loudness curve in VIDEO time (index = time / hop).
        val hop = AudioDecoder.HOP / AudioDecoder.RATE.toDouble()
        val n = (durationS / hop).toInt() + 2
        val energy = FloatArray(n)
        val sorted = dec.rms.sorted()
        val ref = if (sorted.isEmpty()) 1f else max(sorted[((sorted.size - 1) * 0.95).toInt()], 1e-6f)
        val off = (dec.baseS / hop).toInt()
        for (i in dec.rms.indices) {
            val j = off + i
            if (j in 0 until n) energy[j] = (min(dec.rms[i] / ref, 1.5f) / 1.5f)
        }
        Analysis(words, energy, hop)
    }
}
