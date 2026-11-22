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
    val hook: Int,
    val engagement: Int,
    val selected: Boolean = true,
    val captionEdit: String? = null,
)

/** A finished clip saved in the phone's Gallery and listed in My Clips. */
data class LibraryClip(
    val id: String,
    val title: String,
    val uri: String,
    val seconds: Double,
    val created: Long,
    val layout: String,
    val score: Int,
)

/** Speech + loudness for the analysed part of the video. [energy] is 0..1 per [hop] seconds, aligned to video time. */
class Analysis(val words: List<Word>, val energy: FloatArray, val hop: Double)

enum class LayoutMode(val label: String, val hint: String, val shader: Int, val needsCam: Boolean) {
    FILL("Fill", "Crop to fill the screen", 0, false),
    FIT("Fit", "Whole frame, blurred sides", 1, false),
    GAMING("Gaming", "Facecam on top, gameplay below", 2, true),
    FACECAM("Facecam", "Only the facecam, full screen", 3, true),
}

enum class Ratio(val label: String, val ow: Int, val oh: Int) {
    R916("9:16", 9, 16),
    R11("1:1", 1, 1),
    R169("16:9", 16, 9);

    val aspect: Float get() = ow.toFloat() / oh

    /** Output size for a given short side in pixels (1080 -> 1080x1920 for 9:16). */
    fun size(side: Int): Pair<Int, Int> = when (this) {
        R916 -> side to side * 16 / 9
        R11 -> side to side
        R169 -> side * 16 / 9 to side
    }
}

class Len(val label: String, val min: Double, val max: Double)

val LENGTHS = listOf(
    Len("Auto", 20.0, 55.0),
    Len("15s", 10.0, 20.0),
    Len("30s", 22.0, 38.0),
    Len("45s", 35.0, 55.0),
    Len("60s", 50.0, 70.0),
    Len("90s", 75.0, 100.0),
)

/** Colours are ARGB ints. [hlBg] != 0 draws the active word on a coloured box instead of recolouring it. */
class CaptionPreset(
    val name: String,
    val text: Int,
    val hl: Int,
    val bg: Int,
    val hlBg: Int,
    val size: Int,
    val upper: Boolean,
)

val PRESETS = listOf(
    CaptionPreset("Viral Bold", 0xFFFFFFFF.toInt(), 0xFFFFD21F.toInt(), 0x99000000.toInt(), 0, 84, true),
    CaptionPreset("Minimal White", 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x66000000, 0, 58, false),
    CaptionPreset("Gaming Neon", 0xFF00F0FF.toInt(), 0xFFFF2BD6.toInt(), 0xCC000000.toInt(), 0, 80, true),
    CaptionPreset("Podcast Clean", 0xFFFFFFFF.toInt(), 0xFF7CC4FF.toInt(), 0xCC111111.toInt(), 0, 60, false),
    CaptionPreset("Word Highlight", 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0, 0xFF7C3AED.toInt(), 76, true),
)

class Template(
    val name: String,
    val desc: String,
    val lengthIdx: Int,
    val count: Int,
    val layout: LayoutMode,
    val ratio: Ratio,
    val preset: Int,
    val prompt: String,
)

val TEMPLATES = listOf(
    Template("Podcast Clips", "Strong hooks from long conversations", 0, 5, LayoutMode.FILL, Ratio.R916, 3, "Find the best podcast moments with a strong hook in the first 3 seconds"),
    Template("Gaming Clips", "Facecam on top, gameplay below, neon captions", 2, 5, LayoutMode.GAMING, Ratio.R916, 2, "Find the best gaming kills, clutch plays, crashes and big reactions"),
    Template("Funny Moments", "Laughs, jokes and silly reactions", 2, 5, LayoutMode.FILL, Ratio.R916, 0, "Find the funniest moments"),
    Template("Interview Highlights", "Surprising and valuable answers", 3, 4, LayoutMode.FILL, Ratio.R916, 3, "Find surprising moments and valuable answers"),
    Template("Motivational Clips", "Inspiring lines that hook fast", 2, 5, LayoutMode.FILL, Ratio.R916, 0, "Find the most inspiring moments with a strong hook in the first 3 seconds"),
    Template("Educational Clips", "Useful tips people want to save", 4, 4, LayoutMode.FIT, Ratio.R916, 1, "Find the most useful tips and lessons"),
    Template("Reaction Clips", "Big emotions, short and punchy", 1, 6, LayoutMode.FILL, Ratio.R916, 4, "Find emotional reactions"),
    Template("YouTube Shorts", "Up to 60 seconds, vertical", 3, 5, LayoutMode.FILL, Ratio.R916, 0, "Make clips suitable for YouTube Shorts"),
    Template("TikTok", "Fast, bold captions", 2, 5, LayoutMode.FILL, Ratio.R916, 4, "Find the most viral moments for TikTok"),
    Template("Instagram Reels", "Clean look, 9:16", 2, 5, LayoutMode.FILL, Ratio.R916, 1, "Find the most shareable moments for Instagram Reels"),
)
