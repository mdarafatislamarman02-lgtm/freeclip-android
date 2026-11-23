package app.freeclip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything needed to bring the current job back after the app (or the whole process) was closed. */
class Saved(
    val url: String,
    val title: String,
    val durationS: Double,
    val aspect: Float,
    val videoUri: String,
    val audioUri: String,
    val videoReady: Boolean,
    val stage: String,
    val prompt: String,
    val lengthIdx: Int,
    val nClips: Int,
    val ratioIdx: Int,
    val layoutIdx: Int,
    val corner: Int,
    val presetIdx: Int,
    val captions: Boolean,
    val showTitle: Boolean,
    val res: Int,
    val rangeA: Float,
    val rangeB: Float,
    val analysisA: Float,
    val analysisB: Float,
    val moments: List<Moment>,
    val exported: Map<Int, String>,
    val resultIds: List<String>,
    val error: String,
    val timings: Map<String, Long>,
    /** What was running when this was saved: "", "download", "analyze" or "export". */
    val inFlight: String,
)

/** Writes the job and the analysis cache to the app's private storage (atomic: temp file, then rename). */
object JobStore {
    private fun jobFile(ctx: Context) = File(ctx.filesDir, "job.json")
    private fun anaFile(ctx: Context) = File(ctx.filesDir, "analysis.json")

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun save(ctx: Context, s: Saved) {
        try {
            val m = JSONArray()
            for (x in s.moments) {
                m.put(
                    JSONObject().put("id", x.id).put("start", x.start).put("end", x.end).put("score", x.score)
                        .put("title", x.title).put("text", x.text).put("reasons", JSONArray(x.reasons))
                        .put("hook", x.hook).put("engagement", x.engagement).put("selected", x.selected)
                        .put("desc", x.desc).put("tags", x.tags)
                )
            }
            val ex = JSONObject()
            for ((k, v) in s.exported) ex.put(k.toString(), v)
            val tm = JSONObject()
            for ((k, v) in s.timings) tm.put(k, v)
            val o = JSONObject()
                .put("url", s.url).put("title", s.title).put("durationS", s.durationS).put("aspect", s.aspect.toDouble())
                .put("videoUri", s.videoUri).put("audioUri", s.audioUri).put("videoReady", s.videoReady)
                .put("stage", s.stage).put("prompt", s.prompt).put("lengthIdx", s.lengthIdx).put("nClips", s.nClips)
                .put("ratioIdx", s.ratioIdx).put("layoutIdx", s.layoutIdx).put("corner", s.corner)
                .put("presetIdx", s.presetIdx).put("captions", s.captions).put("showTitle", s.showTitle)
                .put("res", s.res).put("rangeA", s.rangeA.toDouble()).put("rangeB", s.rangeB.toDouble())
                .put("analysisA", s.analysisA.toDouble()).put("analysisB", s.analysisB.toDouble())
                .put("moments", m).put("exported", ex).put("resultIds", JSONArray(s.resultIds))
                .put("error", s.error).put("timings", tm).put("inFlight", s.inFlight)
            write(jobFile(ctx), o.toString())
        } catch (_: Exception) {
        }
    }

    fun load(ctx: Context): Saved? = try {
        val f = jobFile(ctx)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val ma = o.getJSONArray("moments")
            val moments = (0 until ma.length()).map {
                val x = ma.getJSONObject(it)
                val r = x.getJSONArray("reasons")
                Moment(
                    id = x.getInt("id"), start = x.getDouble("start"), end = x.getDouble("end"), score = x.getInt("score"),
                    title = x.getString("title"), text = x.getString("text"),
                    reasons = (0 until r.length()).map { i -> r.getString(i) },
                    hook = x.optInt("hook", 0), engagement = x.optInt("engagement", 0),
                    selected = x.optBoolean("selected", true),
                    desc = x.optString("desc", ""), tags = x.optString("tags", ""),
                )
            }
            val ex = HashMap<Int, String>()
            val eo = o.optJSONObject("exported")
            eo?.keys()?.forEach { k -> ex[k.toInt()] = eo.getString(k) }
            val tm = HashMap<String, Long>()
            val to = o.optJSONObject("timings")
            to?.keys()?.forEach { k -> tm[k] = to.getLong(k) }
            val ri = o.optJSONArray("resultIds")
            Saved(
                url = o.optString("url"), title = o.optString("title"), durationS = o.optDouble("durationS", 0.0),
                aspect = o.optDouble("aspect", 1.7778).toFloat(),
                videoUri = o.optString("videoUri"), audioUri = o.optString("audioUri"),
                videoReady = o.optBoolean("videoReady", true), stage = o.optString("stage", "PICK"),
                prompt = o.optString("prompt"), lengthIdx = o.optInt("lengthIdx", 0), nClips = o.optInt("nClips", 5),
                ratioIdx = o.optInt("ratioIdx", 0), layoutIdx = o.optInt("layoutIdx", 0), corner = o.optInt("corner", 4),
                presetIdx = o.optInt("presetIdx", 0), captions = o.optBoolean("captions", true),
                showTitle = o.optBoolean("showTitle", false), res = o.optInt("res", 720),
                rangeA = o.optDouble("rangeA", 0.0).toFloat(), rangeB = o.optDouble("rangeB", 0.0).toFloat(),
                analysisA = o.optDouble("analysisA", -1.0).toFloat(), analysisB = o.optDouble("analysisB", -1.0).toFloat(),
                moments = moments, exported = ex,
                resultIds = if (ri == null) emptyList() else (0 until ri.length()).map { ri.getString(it) },
                error = o.optString("error"), timings = tm, inFlight = o.optString("inFlight"),
            )
        }
    } catch (_: Exception) {
        null
    }

    fun clear(ctx: Context) {
        jobFile(ctx).delete()
        anaFile(ctx).delete()
    }

    /** The transcript and loudness curve, so a retry or a re-pick never transcribes the same audio twice. */
    fun saveAnalysis(ctx: Context, a: Analysis) {
        try {
            val w = JSONArray()
            for (x in a.words) w.put(JSONArray().put(x.w).put(x.s).put(x.e))
            val e = JSONArray()
            for (v in a.energy) e.put(Math.round(v * 1000f) / 1000.0)
            write(anaFile(ctx), JSONObject().put("hop", a.hop).put("words", w).put("energy", e).toString())
        } catch (_: Exception) {
        }
    }

    fun loadAnalysis(ctx: Context): Analysis? = try {
        val f = anaFile(ctx)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val wa = o.getJSONArray("words")
            val words = (0 until wa.length()).map {
                val x = wa.getJSONArray(it)
                Word(x.getString(0), x.getDouble(1), x.getDouble(2))
            }
            val ea = o.getJSONArray("energy")
            val energy = FloatArray(ea.length()) { ea.getDouble(it).toFloat() }
            Analysis(words, energy, o.getDouble("hop"))
        }
    } catch (_: Exception) {
        null
    }
}
