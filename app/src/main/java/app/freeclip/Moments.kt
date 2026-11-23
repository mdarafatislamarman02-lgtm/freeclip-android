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

    private val STOP = setOf(
        "find", "the", "best", "most", "moments", "moment", "clips", "clip", "with", "and", "that", "for", "all",
        "make", "suitable", "strong", "first", "seconds", "second", "a", "an", "of", "in", "to", "on", "from",
        "this", "video", "good", "great", "any", "some", "me", "my", "show", "give", "get", "where", "when",
    )

    /** Words from the user's prompt that a clip should mention (e.g. "funny", "money", "crash"). */
    fun keywords(prompt: String): Set<String> =
        WORD.findAll(prompt.lowercase()).map { it.value }.filter { it.length >= 4 && it !in STOP }.map { it.take(6) }.toSet()

    /** A short headline: the most hook-like sentence of the clip, at most 8 words. */
    private fun makeTitle(win: List<Sentence>): String {
        val best = win.maxByOrNull { s ->
            val l = s.text.lowercase()
            (if (HOOK_STARTS.any { l.startsWith(it) }) 3 else 0) +
                WORD.findAll(l).count { it.value in EMOTIVE } * 2 +
                (if (s.words.size in 4..14) 1 else 0)
        } ?: win.first()
        val ws = best.words.map { it.w }
        val t = ws.take(8).joinToString(" ").replaceFirstChar { it.uppercase() }
        return if (ws.size > 8) "$t..." else t
    }

    /** Offline description (first sentences) and hashtags (frequent keywords + #shorts). The AI versions are better; this is the fallback. */
    fun describe(text: String): Pair<String, String> {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        val desc = if (clean.length <= 180) clean else clean.take(180).substringBeforeLast(' ') + "..."
        val freq = HashMap<String, Int>()
        for (m in WORD.findAll(clean.lowercase())) {
            val w = m.value
            if (w.length >= 5 && w !in STOP && w.all { it.isLetter() }) freq[w] = (freq[w] ?: 0) + 1
        }
        val tags = freq.entries.sortedByDescending { it.value }.take(5).map { "#" + it.key } + listOf("#shorts", "#viral")
        return desc to tags.joinToString(" ")
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

    private fun score(win: List<Sentence>, energy: FloatArray, hop: Double, kw: Set<String>): Cand {
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

        var score = WEIGHTS.entries.sumOf { (k, w) -> f.getValue(k) * w } * 100
        if (kw.isNotEmpty()) {
            val hits = WORD.findAll(low).map { it.value }.toSet().count { w -> kw.any { k -> w.startsWith(k) } }
            val m = clip01(hits / max(1.0, min(3.0, kw.size.toDouble())))
            f["match"] = m
            score = score * 0.7 + m * 30
        }
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
        kw: Set<String> = emptySet(),
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
                if (j >= 0) cands.add(score(sents.subList(i, j + 1), energy, hop, kw))
            }
        }
        if (cands.isEmpty()) {
            // Video shorter than the minimum length: offer the speech as one clip.
            var j = 0
            while (j + 1 < sents.size && sents[j + 1].end - sents[0].start <= maxLen) j++
            if (sents[j].end - sents[0].start >= 5) cands.add(score(sents.subList(0, j + 1), energy, hop, kw))
        }
        cands.sortByDescending { it.score }

        val chosen = ArrayList<Moment>()
        val chosenToks = ArrayList<Set<String>>()
        for (c in cands) {
            val a = c.win.first().start
            val b = c.win.last().end
            // Never pick two clips that overlap in time, or that say almost the same words (a repeated line / looping scene).
            val overlaps = chosen.any { min(b, it.end + 1.0) - max(a, it.start - 1.0) > 0.0 }
            if (overlaps) continue
            val toks = c.win.flatMap { it.text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")) }.filter { it.length > 2 }.toSet()
            val similar = chosenToks.any { o ->
                val inter = toks.count { it in o }
                val uni = toks.size + o.size - inter
                uni > 0 && inter.toDouble() / uni > 0.5
            }
            if (similar) continue
            chosenToks.add(toks)
            val top = c.feats.entries.filter { it.key != "match" }.sortedByDescending { it.value }.take(3)
                .filter { it.value >= 0.5 }.map { REASONS.getValue(it.key) }.toMutableList()
            if ((c.feats["match"] ?: 0.0) >= 0.5) top.add(0, "matches your prompt")
            val f = c.feats
            chosen.add(
                Moment(
                    id = chosen.size,
                    start = max(a - 0.25, 0.0),
                    end = min(b + 0.45, durationS),
                    score = c.score.toInt().coerceIn(0, 99),
                    title = makeTitle(c.win),
                    text = c.win.joinToString(" ") { it.text },
                    desc = describe(c.win.joinToString(" ") { it.text }).first,
                    tags = describe(c.win.joinToString(" ") { it.text }).second,
                    reasons = top.ifEmpty { listOf("steady, complete passage") },
                    hook = (f.getValue("hook") * 100).toInt().coerceIn(0, 99),
                    engagement = ((f.getValue("emotion") + f.getValue("energy") + f.getValue("pace")) / 3 * 100).toInt().coerceIn(0, 99),
                )
            )
            if (chosen.size >= nClips) break
        }
        return chosen
    }
}
