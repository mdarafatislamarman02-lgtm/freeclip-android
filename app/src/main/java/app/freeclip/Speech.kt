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
     * Transcribes [startS, endS] of the video. The range is cut into parts and each part is decoded AND transcribed
     * on its own core at the same time. Progress: model 0-10%, then 10-100%.
     * Word times and the loudness curve are in VIDEO time.
     */
    suspend fun analyze(
        ctx: Context,
        uri: Uri,
        durationS: Double,
        startS: Double,
        endS: Double,
        onStatus: (String, Float) -> Unit,
        maxParts: Int = 6,
    ): Analysis = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()

        onStatus("Getting the speech model (first time only)...", 0f)
        val dir = ensureModel(ctx) { onStatus("Downloading speech model (first time only)...", it * 0.1f) }
        job.ensureActive()

        onStatus("Loading the speech model...", 0.1f)
        val model = Model(dir.absolutePath)
        val hop = AudioDecoder.HOP / AudioDecoder.RATE.toDouble()
        val span = max(endS - startS, 1.0)
        val parts = if (span < 120 || maxParts <= 1) 1 else (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, maxParts.coerceAtLeast(2))
        val segLen = span / parts
        val prog = DoubleArray(parts)
        fun report() = onStatus("Listening to the video...", 0.1f + 0.9f * (prog.sum() / parts).toFloat())

        class Part(val words: List<Word>, val baseS: Double, val rms: FloatArray)

        val results = try {
            coroutineScope {
                (0 until parts).map { k ->
                    async(Dispatchers.Default) {
                        val keepLo = startS + k * segLen
                        val keepHi = if (k == parts - 1) endS + 1.0 else startS + (k + 1) * segLen
                        val from = max(startS, keepLo - 1.5)
                        val to = min(endS, keepHi + 0.5)
                        val pcm = File(ctx.cacheDir, "audio16k_$k.pcm")
                        try {
                            val dec = AudioDecoder.decodeToFile(
                                ctx, uri, (from * 1e6).toLong(), (to * 1e6).toLong(), ((to - from) * 1e6).toLong(), pcm,
                                { prog[k] = 0.3 * it; report() },
                                { ensureActive() },
                            )
                            val out = ArrayList<Word>()
                            val rec = Recognizer(model, AudioDecoder.RATE.toFloat())
                            rec.setWords(true)
                            fun collect(json: String) {
                                val arr = JSONObject(json).optJSONArray("result") ?: return
                                for (i in 0 until arr.length()) {
                                    val o = arr.getJSONObject(i)
                                    val s = o.getDouble("start") + dec.baseS
                                    val e = o.getDouble("end") + dec.baseS
                                    val mid = (s + e) / 2
                                    if (mid >= keepLo && mid < keepHi) out.add(Word(o.getString("word"), s, e))
                                }
                            }
                            try {
                                val total = max(1L, pcm.length())
                                RandomAccessFile(pcm, "r").use { f ->
                                    val buf = ByteArray(8000)
                                    var read = 0L
                                    while (true) {
                                        ensureActive()
                                        val n = f.read(buf)
                                        if (n <= 0) break
                                        if (rec.acceptWaveForm(buf, n)) collect(rec.result)
                                        read += n
                                        prog[k] = 0.3 + 0.7 * (read.toDouble() / total)
                                        if (k == 0) report()
                                    }
                                }
                                collect(rec.finalResult)
                            } finally {
                                rec.close()
                            }
                            prog[k] = 1.0
                            Part(out, dec.baseS, dec.rms)
                        } finally {
                            pcm.delete()
                        }
                    }
                }.awaitAll()
            }
        } finally {
            model.close()
        }

        val words = results.flatMap { it.words }.sortedBy { it.s }

        // Loudness curve in VIDEO time (index = time / hop).
        val n = (durationS / hop).toInt() + 2
        val energy = FloatArray(n)
        val all = ArrayList<Float>()
        for (p in results) for (v in p.rms) all.add(v)
        all.sort()
        val ref = if (all.isEmpty()) 1f else max(all[((all.size - 1) * 0.95).toInt()], 1e-6f)
        for (p in results) {
            val off = (p.baseS / hop).toInt()
            for (i in p.rms.indices) {
                val j = off + i
                if (j in 0 until n) energy[j] = min(p.rms[i] / ref, 1.5f) / 1.5f
            }
        }
        Analysis(words, energy, hop)
    }
}
