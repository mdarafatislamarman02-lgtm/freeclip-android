package app.freeclip

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.sqrt

/** Offline speech-to-text (Vosk) with word timestamps, plus a loudness curve. */
object Speech {
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val HOP_SAMPLES = 8000 // 0.5 s at 16 kHz

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

    suspend fun analyze(
        ctx: Context,
        uri: Uri,
        durationUs: Long,
        onStatus: (String, Float) -> Unit,
    ): Analysis = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()

        onStatus("Getting the speech model (first time only)...", 0f)
        val dir = ensureModel(ctx) { onStatus("Downloading speech model...", it * 0.15f) }
        job.ensureActive()

        onStatus("Loading speech model...", 0.15f)
        val model = Model(dir.absolutePath)
        val rec = Recognizer(model, AudioDecoder.RATE.toFloat())
        rec.setWords(true)

        val words = ArrayList<Word>()
        val rms = ArrayList<Double>()
        var acc = 0.0
        var cnt = 0

        fun collect(json: String) {
            val arr = JSONObject(json).optJSONArray("result") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                words.add(Word(o.getString("word"), o.getDouble("start"), o.getDouble("end")))
            }
        }

        try {
            AudioDecoder.decode(
                ctx, uri, durationUs,
                onPcm = { pcm ->
                    for (x in pcm) {
                        val f = x / 32768.0
                        acc += f * f
                        cnt++
                        if (cnt == HOP_SAMPLES) {
                            rms.add(sqrt(acc / cnt))
                            acc = 0.0
                            cnt = 0
                        }
                    }
                    if (rec.acceptWaveForm(pcm, pcm.size)) collect(rec.result)
                },
                onProgress = { onStatus("Listening to the video...", 0.15f + 0.85f * it) },
                checkActive = { job.ensureActive() },
            )
            collect(rec.finalResult)
        } finally {
            rec.close()
            model.close()
        }

        val sorted = rms.sorted()
        val ref = if (sorted.isEmpty()) 1.0 else max(sorted[((sorted.size - 1) * 0.95).toInt()], 1e-6)
        val energy = FloatArray(rms.size) { (minOf(rms[it] / ref, 1.5) / 1.5).toFloat() }
        Analysis(words, energy, HOP_SAMPLES / AudioDecoder.RATE.toDouble())
    }
}
