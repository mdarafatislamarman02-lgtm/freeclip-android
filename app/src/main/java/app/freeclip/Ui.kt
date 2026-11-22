package app.freeclip

import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

object C {
    val Bg = Color(0xFF0B0A1A)
    val Surface = Color(0xFF15132B)
    val Card = Color(0xFF1C1940)
    val Line = Color(0xFF2E2A5C)
    val Violet = Color(0xFF8B5CF6)
    val Blue = Color(0xFF3B82F6)
    val Cyan = Color(0xFF22D3EE)
    val Pink = Color(0xFFEC4899)
    val Gold = Color(0xFFFDE047)
    val Ink = Color(0xFFF4F3FF)
    val Mute = Color(0xFF9B98C4)
    val Good = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFF87171)
    val Brand = Brush.linearGradient(listOf(Violet, Blue, Cyan))
    val BrandH = Brush.horizontalGradient(listOf(Violet, Blue))
}

val Scheme = darkColorScheme(
    primary = C.Violet,
    onPrimary = Color.White,
    background = C.Bg,
    surface = C.Surface,
    onBackground = C.Ink,
    onSurface = C.Ink,
    error = C.Bad,
    surfaceVariant = C.Card,
    outline = C.Line,
)

fun fmt(sec: Double): String {
    val t = sec.toInt()
    val h = t / 3600
    val m = (t / 60) % 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ---------------------------------------------------------------- logo

/** ClipForge mark: a vertical clip frame with a play triangle, struck by an AI spark. */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 108f
        drawRoundRect(C.Brand, size = this.size, cornerRadius = CornerRadius(26f * u))
        // vertical 9:16 clip frame
        drawRoundRect(
            Color.White,
            topLeft = Offset(36f * u, 26f * u),
            size = Size(32f * u, 56f * u),
            cornerRadius = CornerRadius(6f * u),
            style = Stroke(width = 4.5f * u),
        )
        // play triangle
        val tri = Path().apply {
            moveTo(46f * u, 44f * u)
            lineTo(62f * u, 54f * u)
            lineTo(46f * u, 64f * u)
            close()
        }
        drawPath(tri, Color.White)
        // spark
        val sp = Path().apply {
            moveTo(72f * u, 14f * u)
            cubicTo(73.5f * u, 22f * u, 77f * u, 26.5f * u, 86f * u, 28f * u)
            cubicTo(77f * u, 29.5f * u, 73.5f * u, 34f * u, 72f * u, 42f * u)
            cubicTo(70.5f * u, 34f * u, 67f * u, 29.5f * u, 58f * u, 28f * u)
            cubicTo(67f * u, 26.5f * u, 70.5f * u, 22f * u, 72f * u, 14f * u)
            close()
        }
        drawPath(sp, C.Gold)
    }
}

@Composable
fun BrandName(sizeSp: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = C.Ink)) { append("Clip") }
                withStyle(SpanStyle(color = C.Cyan)) { append("Forge") }
                withStyle(SpanStyle(color = C.Violet)) { append(" AI") }
            },
            fontSize = sizeSp.sp, fontWeight = FontWeight.ExtraBold,
        )
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(C.Surface)
            .border(BorderStroke(1.dp, C.Line), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
fun GradButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) C.BrandH else Brush.horizontalGradient(listOf(C.Line, C.Line)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else C.Mute, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, C.Line), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = C.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) C.Violet.copy(alpha = 0.30f) else C.Card)
            .border(BorderStroke(1.dp, if (selected) C.Violet else C.Line), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) C.Ink else C.Mute, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun Label(text: String) = Text(text, color = C.Mute, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

/** Viral score ring, like Opus: green 80+, amber 60+, else red. */
@Composable
fun ScoreRing(score: Int, size: Dp = 52.dp) {
    val col = if (score >= 80) C.Good else if (score >= 60) C.Warn else C.Bad
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = 5.dp.toPx()
            drawArc(C.Line, 0f, 360f, false, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w))
            drawArc(
                col, -90f, 360f * score / 100f, false,
                topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        Text("$score", color = C.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    }
}

// ---------------------------------------------------------------- live preview (same GL effect as the export)

/**
 * Plays the clip's range through the SAME layout effect the export uses, in a box with the output's aspect ratio,
 * so what you see here is what you get. Captions are drawn on top with the chosen style.
 */
@OptIn(UnstableApi::class)
@Composable
fun ClipPreview(
    uri: Uri,
    startS: Double,
    endS: Double,
    effect: LayoutEffect,
    aspect: Float,
    cues: List<Cue>,
    preset: CaptionPreset,
    captionY: Float,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var posUs by remember { mutableStateOf(0L) }
    val player = remember(uri, startS, endS, effect) {
        ExoPlayer.Builder(ctx).build().apply {
            setVideoEffects(listOf(effect))
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((startS * 1000).toLong())
                .setEndPositionMs((endS * 1000).toLong())
                .build()
            setMediaItem(MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build())
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) {
        while (true) {
            posUs = player.currentPosition * 1000
            delay(70)
        }
    }

    BoxWithConstraints(
        modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { TextureView(it) },
            update = { player.setVideoTextureView(it) },
        )
        val cue = cues.firstOrNull { posUs >= it.startUs && posUs < it.endUs }
        if (cue != null) {
            val fontSp = with(LocalDensity.current) { (maxWidth * (preset.size * 0.85f / 1080f)).toSp() }
            CaptionText(cue, preset, fontSp.value, Modifier.align(BiasAlignment(0f, -captionY)))
        }
    }
}

@Composable
fun CaptionText(cue: Cue, preset: CaptionPreset, sizeSp: Float, modifier: Modifier) {
    val text: AnnotatedString = buildAnnotatedString {
        append(cue.text)
        addStyle(SpanStyle(color = Color(preset.text)), 0, cue.text.length)
        if (cue.hlEnd > cue.hlStart) {
            if (preset.hlBg != 0) addStyle(SpanStyle(background = Color(preset.hlBg)), cue.hlStart, cue.hlEnd)
            else addStyle(SpanStyle(color = Color(preset.hl)), cue.hlStart, cue.hlEnd)
        }
    }
    Text(
        text,
        modifier = modifier
            .padding(horizontal = 12.dp)
            .background(if (preset.bg != 0) Color(preset.bg) else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = sizeSp.sp,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
    )
}
