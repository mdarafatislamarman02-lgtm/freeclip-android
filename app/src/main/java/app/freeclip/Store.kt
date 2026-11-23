package app.freeclip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small on-device storage: default settings and the list of finished clips. Nothing here leaves the phone. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("clipforge", Context.MODE_PRIVATE)
    private val libFile = File(ctx.filesDir, "library.json")

    var lengthIdx: Int
        get() = sp.getInt("lengthIdx", 0)
        set(v) { sp.edit().putInt("lengthIdx", v).apply() }

    var ratioIdx: Int
        get() = sp.getInt("ratioIdx", 0)
        set(v) { sp.edit().putInt("ratioIdx", v).apply() }

    var res: Int
        get() = sp.getInt("res", 720)
        set(v) { sp.edit().putInt("res", v).apply() }

    var presetIdx: Int
        get() = sp.getInt("presetIdx", 0)
        set(v) { sp.edit().putInt("presetIdx", v).apply() }

    var captions: Boolean
        get() = sp.getBoolean("captions", true)
        set(v) { sp.edit().putBoolean("captions", v).apply() }

    var creator: String
        get() = sp.getString("creator", "") ?: ""
        set(v) { sp.edit().putString("creator", v).apply() }

    /** 0 = offline, 1 = Groq Whisper, 2 = Deepgram Nova-3 (needs the person's own key). */
    var stt: Int
        get() = sp.getInt("stt", 0)
        set(v) { sp.edit().putInt("stt", v).apply() }
    /** 0 = built-in titles, 1 = Groq Llama, 2 = Gemini. */
    var titleAi: Int
        get() = sp.getInt("titleAi", 0)
        set(v) { sp.edit().putInt("titleAi", v).apply() }
    /** Spoken language of the videos: "auto" (detect) or an ISO code like "en", "bn", "hi". */
    var lang: String
        get() = sp.getString("lang", "auto") ?: "auto"
        set(v) { sp.edit().putString("lang", v).apply() }
    /** Captions in English even when the video is in another language (needs Groq Whisper). */
    var captionEnglish: Boolean
        get() = sp.getBoolean("captionEnglish", true)
        set(v) { sp.edit().putBoolean("captionEnglish", v).apply() }
    /** Export the found clips automatically (like Opus) so they appear in My Clips without extra taps. */
    var autoExport: Boolean
        get() = sp.getBoolean("autoExport", true)
        set(v) { sp.edit().putBoolean("autoExport", v).apply() }
    var groqKey: String
        get() = sp.getString("groqKey", "") ?: ""
        set(v) { sp.edit().putString("groqKey", v.trim()).apply() }
    var deepgramKey: String
        get() = sp.getString("deepgramKey", "") ?: ""
        set(v) { sp.edit().putString("deepgramKey", v.trim()).apply() }
    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) { sp.edit().putString("geminiKey", v.trim()).apply() }

    fun loadLibrary(): List<LibraryClip> = try {
        if (!libFile.exists()) emptyList() else {
            val arr = JSONArray(libFile.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                LibraryClip(
                    o.getString("id"), o.getString("title"), o.getString("uri"),
                    o.getDouble("seconds"), o.getLong("created"),
                    o.optString("layout", ""), o.optInt("score", 0), o.optString("source", ""), o.optDouble("startS", -1.0), o.optDouble("endS", -1.0),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveLibrary(list: List<LibraryClip>) {
        try {
            val arr = JSONArray()
            for (c in list) {
                arr.put(
                    JSONObject().put("id", c.id).put("title", c.title).put("uri", c.uri)
                        .put("seconds", c.seconds).put("created", c.created)
                        .put("layout", c.layout).put("score", c.score).put("source", c.source).put("startS", c.startS).put("endS", c.endS)
                )
            }
            libFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
