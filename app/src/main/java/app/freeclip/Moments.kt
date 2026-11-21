package app.freeclip

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds clip-worthy moments from the transcript and loudness (the same scoring
 * as the desktop FreeClip): strong opening line, emotional language, speaking pace,
 * audio energy, little dead air, clean start.
 */
object Moments {
    private val HOOK_STARTS = listOf(
        "here's", "here is", "the secret", "the truth", "nobody", "no one", "stop", "never",
        "why", "how", "what if", "the biggest", "the best", "the worst", "i can't believe",
        "i cannot believe", "let me tell you", "listen", "imagine", "you need to", "you should",
        "don't", "do not", "this is why", "the reason", "the problem", "most people", "everyone",
        "the first", "one thing", "what's", "what is", "the number one", "i learned", "i realized",
    )
    private val QUESTION_STARTS = listOf("why", "how", "what", "who", "do you", "have you", "did you")
    private val EMOTIVE = setOf(
        "crazy", "insane", "wow", "amazing", "incredible", "unbelievable", "shocking", "hate",
        "love", "worst", "best", "mistake", "truth", "wrong", "secret", "literally", "never",
        "always", "terrible", "awesome", "huge", "massive", "disaster", "genius", "brilliant",
        "scary", "dangerous", "impossible", "obsessed", "painful", "hilarious", "funny",
        "nightmare", "dream", "fail", "failed", "win", "won", "lost", "money", "free", "viral",
        "kill", "die",
    )
    private val BAD_STARTS = listOf("and ", "but ", "so ", "because ", "which ", "or ", "then ", "also ", "that ")
    private val LAUGH = Regex("\\b(ha){2,}|\\bhaha|\\blol\\b|\\blmao\\b")
    private val WORD = Regex("[a-z']+")

    private val REASONS = mapOf(
        "hook" to "strong opening line",
        "emotion" to "emotional or punchy language",
        "pace" to "energetic speaking pace",
        "energy" to "loud, animated delivery",
        "density" to "little dead air",
        "clean" to "starts cleanly",
    )
    private val WEIGHTS = mapOf(
        "hook" to 0.24, "emotion" to 0.18, "pace" to 0.10,
        "energy" to 0.20, "density" to 0.10, "clean" to 0.18,
    )

    class Sentence(val words: List<Word>) {
        val start get() = words.first().s
        val end get() = words.last().e
        val text: String = words.joinToString(" ") { it.w }
    }

    private class Cand(val score: Double, val feats: Map<String, Double>, val win: List<Sentence>)

    /** Vosk gives no punctuation, so sentences are split on pauses. */
    fun sentences(words: List<Word>, gap: Double = 0.7, maxWords: Int = 24): List<Sentence> {
        val out = ArrayList<Sentence>()
        var cur = ArrayList<Word>()
        for (w in words) {
            if (cur.isNotEmpty() && (w.s - cur.last().e > gap || cur.size >= maxWords)) {
                out.add(Sentence(cur))
                cur = ArrayList()
            }
            cur.add(w)
        }
        if (cur.isNotEmpty()) out.add(Sentence(cur))
        return out
    }

    private fun clip01(x: Double) = max(0.0, min(1.0, x))

    private fun energyBetween(energy: FloatArray, hop: Double, a: Double, b: Double): Pair<Double, Double> {
        if (energy.isEmpty()) return 0.0 to 0.0
        val i0 = min((a / hop).toInt(), energy.size - 1)
        val i1 = min(max((b / hop).toInt(), i0 + 1), energy.size)
        val seg = energy.slice(i0 until i1)
        if (seg.isEmpty()) return 0.0 to 0.0
        val mean = seg.average()
        val sorted = seg.sorted()
        val p90 = sorted[((sorted.size - 1) * 0.9).toInt()].toDouble()
        return mean to p90
    }

    private fun score(win: List<Sentence>, energy: FloatArray, hop: Double): Cand {
        val words = win.flatMap { it.words }
        val low = win.joinToString(" ") { it.text }.lowercase()
        val dur = win.last().end - win.first().start
        val n = max(words.size, 1)
        val first = win.first().text.lowercase()

        val f = HashMap<String, Double>()
        var hook = 0.0
        if (QUESTION_STARTS.any { first.startsWith(it) }) hook += 0.5
        if (HOOK_STARTS.any { first.startsWith(it) }) hook += 0.5
        if (first.any { it.isDigit() }) hook += 0.2
        if (win.first().words.size <= 14) hook += 0.1
        f["hook"] = clip01(hook)

        val emo = WORD.findAll(low).count { it.value in EMOTIVE } + 2 * LAUGH.findAll(low).count()
        f["emotion"] = clip01(emo.toDouble() / n * 12)

        val wps = n / max(dur, 1.0)
        f["pace"] = clip01(1 - abs(wps - 3.3) / 2.5)

        val (meanE, peakE) = energyBetween(energy, hop, win.first().start, win.last().end)
        f["energy"] = clip01(0.6 * meanE + 0.4 * peakE)

        val spoken = words.sumOf { it.e - it.s }
        f["density"] = clip01((spoken / max(dur, 1.0) - 0.45) / 0.4)

        f["clean"] = if (BAD_STARTS.any { low.startsWith(it) }) 0.5 else 1.0

        val score = WEIGHTS.entries.sumOf { (k, w) -> f.getValue(k) * w } * 100
        return Cand(score, f, win)
    }

    fun find(
        words: List<Word>,
        energy: FloatArray,
        hop: Double,
        durationS: Double,
        nClips: Int,
        minLen: Double,
        maxLen: Double,
    ): List<Moment> {
        val sents = sentences(words)
        if (sents.isEmpty()) return emptyList()

        val cands = ArrayList<Cand>()
        for (i in sents.indices) {
            var jShort = -1
            var jLong = -1
            for (j in i until sents.size) {
                val dur = sents[j].end - sents[i].start
                if (dur > maxLen) break
                if (jShort < 0 && dur >= minLen) jShort = j
                if (jLong < 0 && dur >= (minLen + maxLen) / 2) jLong = j
            }
            for (j in listOf(jShort, jLong).distinct()) {
                if (j >= 0) cands.add(score(sents.subList(i, j + 1), energy, hop))
            }
        }
        if (cands.isEmpty()) {
            // Video shorter than the minimum length: offer the speech as one clip.
            var j = 0
            while (j + 1 < sents.size && sents[j + 1].end - sents[0].start <= maxLen) j++
            if (sents[j].end - sents[0].start >= 5) cands.add(score(sents.subList(0, j + 1), energy, hop))
        }
        cands.sortByDescending { it.score }

        val chosen = ArrayList<Moment>()
        for (c in cands) {
            val a = c.win.first().start
            val b = c.win.last().end
            val overlaps = chosen.any { min(b, it.end) - max(a, it.start) > 0.15 * (b - a) }
            if (overlaps) continue
            val top = c.feats.entries.sortedByDescending { it.value }.take(3)
                .filter { it.value >= 0.5 }.map { REASONS.getValue(it.key) }
            val first = c.win.first().text
            chosen.add(
                Moment(
                    id = chosen.size,
                    start = max(a - 0.25, 0.0),
                    end = min(b + 0.45, durationS),
                    score = c.score.toInt().coerceIn(0, 99),
                    title = first.replaceFirstChar { it.uppercase() }.take(60),
                    text = c.win.joinToString(" ") { it.text },
                    reasons = top.ifEmpty { listOf("steady, complete passage") },
                )
            )
            if (chosen.size >= nClips) break
        }
        return chosen
    }
}
