package app.freeclip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional cloud AI, used only when the person pastes their OWN key in Settings (so the free limit is theirs):
 *  - transcription: Groq Whisper or Deepgram Nova-3 (word timestamps, much faster than the phone)
 *  - better titles: Groq Llama 3.3 70B or Gemini
 * Anything that fails falls back to the offline path; nothing here is required.
 */
object Cloud {
    enum class Stt(val label: String) { OFFLINE("Offline"), GROQ("Groq Whisper"), DEEPGRAM("Deepgram Nova-3") }

    class Fail(msg: String) : Exception(msg)

    private const val GROQ_STT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val GROQ_CHAT = "https://api.groq.com/openai/v1/chat/completions"
    private const val DEEPGRAM = "https://api.deepgram.com/v1/listen?model=nova-3&language=en&punctuate=false&smart_format=false"
    // Model names change over time; if a title request is rejected the app just keeps its own titles.
    private const val GEMINI_MODEL = "gemini-3.8-flash"
    private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"

    /** Raw 16 kHz mono 16-bit PCM -> a WAV file (header + the same bytes). */
    fun wav(pcm: File, out: File) {
        val n = pcm.length()
        val rate = AudioDecoder.RATE
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + n).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(n.toInt())
        FileOutputStream(out).use { o ->
            o.write(h.array())
            FileInputStream(pcm).use { it.copyTo(o) }
        }
    }

    private fun friendly(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).let { j -> j.optJSONObject("error")?.optString("message") ?: j.optString("err_msg").ifEmpty { j.optString("message") } }
        } catch (_: Exception) { "" }.ifEmpty { body.take(160) }
        return when (code) {
            401, 403 -> "The key was rejected (check that it is copied correctly). $detail"
            413 -> "The audio file was too large for this service. $detail"
            429 -> "The free limit was reached for now. $detail"
            else -> "Service error $code. $detail"
        }.trim()
    }

    private fun readAll(c: HttpURLConnection): String {
        val s = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: return "")
        return s.bufferedReader().use { it.readText() }
    }

    /** Transcribes one WAV chunk; returns words with times relative to the start of that chunk. */
    fun transcribe(stt: Stt, key: String, wav: File): List<Word> {
        return when (stt) {
            Stt.GROQ -> groq(key, wav)
            Stt.DEEPGRAM -> deepgram(key, wav)
            Stt.OFFLINE -> throw Fail("No cloud service selected.")
        }
    }

    private fun groq(key: String, wav: File): List<Word> {
        val b = "----clipforge" + System.nanoTime()
        val c = URL(GROQ_STT).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 240_000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            c.setChunkedStreamingMode(64 * 1024)
            c.outputStream.use { o ->
                fun field(n: String, v: String) = o.write("--$b\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
                field("model", "whisper-large-v3-turbo")
                field("response_format", "verbose_json")
                field("timestamp_granularities[]", "word")
                field("language", "en")
                field("temperature", "0")
                o.write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                FileInputStream(wav).use { it.copyTo(o) }
                o.write("\r\n--$b--\r\n".toByteArray())
            }
            val body = readAll(c)
            if (c.responseCode != 200) throw Fail(friendly(c.responseCode, body))
            val arr = JSONObject(body).optJSONArray("words") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Word(o.getString("word").trim(), o.getDouble("start"), o.getDouble("end"))
            }
        } catch (e: IOException) {
            throw Fail("No connection to Groq (${e.message ?: e.javaClass.simpleName}).")
        } finally {
            c.disconnect()
        }
    }

    private fun deepgram(key: String, wav: File): List<Word> {
        val c = URL(DEEPGRAM).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 240_000
            c.setRequestProperty("Authorization", "Token $key")
            c.setRequestProperty("Content-Type", "audio/wav")
            c.setFixedLengthStreamingMode(wav.length())
            c.outputStream.use { o -> FileInputStream(wav).use { it.copyTo(o) } }
            val body = readAll(c)
            if (c.responseCode != 200) throw Fail(friendly(c.responseCode, body))
            val arr = JSONObject(body).getJSONObject("results").getJSONArray("channels").getJSONObject(0)
                .getJSONArray("alternatives").getJSONObject(0).optJSONArray("words") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Word(o.getString("word").trim(), o.getDouble("start"), o.getDouble("end"))
            }
        } catch (e: IOException) {
            throw Fail("No connection to Deepgram (${e.message ?: e.javaClass.simpleName}).")
        } finally {
            c.disconnect()
        }
    }

    /** Settings "Test key": returns "OK" or a plain explanation. */
    fun test(service: String, key: String): String {
        if (key.isBlank()) return "Paste a key first."
        val (url, auth) = when (service) {
            "groq" -> "https://api.groq.com/openai/v1/models" to ("Authorization" to "Bearer $key")
            "deepgram" -> "https://api.deepgram.com/v1/projects" to ("Authorization" to "Token $key")
            else -> "https://generativelanguage.googleapis.com/v1beta/models" to ("x-goog-api-key" to key)
        }
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty(auth.first, auth.second)
            val body = readAll(c)
            if (c.responseCode == 200) "OK" else friendly(c.responseCode, body)
        } catch (e: Exception) {
            "Could not reach the service: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            c.disconnect()
        }
    }

    /** Asks the chosen AI for a punchy title for each clip. On any problem the original moments are returned unchanged. */
    fun improveTitles(ctx: Context, store: Store, moments: List<Moment>): List<Moment> {
        val useGroq = store.titleAi == 1 && store.groqKey.isNotBlank()
        val useGemini = store.titleAi == 2 && store.geminiKey.isNotBlank()
        if (!useGroq && !useGemini) return moments
        return try {
            val prompt = StringBuilder(
                "You write titles for short vertical videos (TikTok / Shorts). For each numbered transcript below write ONE punchy, " +
                    "curiosity-driven title of at most 8 words that matches what is really said. No quotes, no hashtags, no emojis. " +
                    "Return only JSON: {\"titles\":[\"...\", ...]} with exactly ${moments.size} titles in the same order.\n\n"
            )
            moments.forEachIndexed { i, m -> prompt.append("${i + 1}. ").append(m.text.take(400)).append("\n") }
            val text = if (useGroq) groqChat(store.groqKey, prompt.toString()) else gemini(store.geminiKey, prompt.toString())
            val arr = JSONObject(text).getJSONArray("titles")
            if (arr.length() != moments.size) throw Fail("The AI returned ${arr.length()} titles for ${moments.size} clips.")
            moments.mapIndexed { i, m ->
                val t = arr.getString(i).replace(Regex("[\"\\n]"), "").trim().take(70)
                if (t.length >= 3) m.copy(title = t) else m
            }
        } catch (e: Exception) {
            Diag.log(ctx, "TITLES failed (kept the built-in titles)", e)
            moments
        }
    }

    private fun postJson(url: String, headers: Map<String, String>, body: JSONObject): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 40_000
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = readAll(c)
            if (c.responseCode != 200) throw Fail(friendly(c.responseCode, resp))
            return resp
        } finally {
            c.disconnect()
        }
    }

    private fun groqChat(key: String, prompt: String): String {
        val body = JSONObject()
            .put("model", "llama-3.3-70b-versatile")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.7)
        val r = postJson(GROQ_CHAT, mapOf("Authorization" to "Bearer $key"), body)
        return JSONObject(r).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun gemini(key: String, prompt: String): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val r = postJson(GEMINI, mapOf("x-goog-api-key" to key), body)
        return JSONObject(r).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
    }
}
