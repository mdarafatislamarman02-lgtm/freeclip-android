package app.freeclip

data class Word(val w: String, val s: Double, val e: Double)

data class Moment(
    val id: Int,
    val start: Double,
    val end: Double,
    val score: Int,
    val title: String,
    val text: String,
    val reasons: List<String>,
    val selected: Boolean = true,
)

data class ClipResult(val title: String, val uri: String, val seconds: Double)

/** Speech + loudness for the whole video. [energy] is 0..1 per [hop] seconds. */
class Analysis(val words: List<Word>, val energy: FloatArray, val hop: Double)
