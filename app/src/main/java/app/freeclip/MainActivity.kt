package app.freeclip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog

private val Yellow = Color(0xFFFFD21F)
private val Bg = Color(0xFF0F1115)
private val Card1 = Color(0xFF171A21)
private val Mute = Color(0xFF8D95A6)
private val Ink = Color(0xFF1A1500)

private val Scheme = darkColorScheme(
    primary = Yellow,
    onPrimary = Ink,
    background = Bg,
    surface = Card1,
    onBackground = Color(0xFFE8EAF0),
    onSurface = Color(0xFFE8EAF0),
    error = Color(0xFFFF6B6B),
)

class MainActivity : ComponentActivity() {
    private val vm: MainVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent {
            MaterialTheme(colorScheme = Scheme) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A link shared from YouTube (or any app) lands in the link box. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val url = i.getStringExtra(Intent.EXTRA_TEXT)?.let { Links.extractUrl(it) } ?: return
        if (!vm.busy) {
            if (vm.stage != Stage.PICK) vm.startOver()
            vm.linkText = url
        }
    }
}

private fun fmt(sec: Double): String {
    val t = sec.toInt()
    val h = t / 3600
    val m = (t / 60) % 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun App(vm: MainVM) {
    val view = LocalView.current
    DisposableEffect(vm.busy) {
        view.keepScreenOn = vm.busy
        onDispose { view.keepScreenOn = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { vm.onPicked(it) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Free", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Clip", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Yellow)
        }
        Text("Long video in, vertical clips out. Free, no watermark.", color = Mute, fontSize = 14.sp)

        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (vm.stage) {
            Stage.PICK -> {
                HomeCard(vm) { picker.launch("video/*") }
                HowItWorks()
            }
            Stage.READY -> ReadyCard(vm) { picker.launch("video/*") }
            Stage.DOWNLOADING, Stage.ANALYZING, Stage.RENDERING -> BusyCard(vm)
            Stage.REVIEW -> ReviewCard(vm)
            Stage.DONE -> DoneCard(vm)
        }
    }
    PreviewDialog(vm)
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Card1), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

// ---------------------------------------------------------------- 1. Link / Upload

@Composable
private fun HomeCard(vm: MainVM, onPick: () -> Unit) = Panel {
    Text("Clip long video", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    OutlinedTextField(
        value = vm.linkText,
        onValueChange = { vm.linkText = it },
        placeholder = { Text("Drop a YouTube or video link") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { vm.getClips() }),
    )
    Button(
        onClick = { vm.getClips() },
        enabled = vm.linkText.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Get clips in 1 click") }
    Text("or", color = Mute, fontSize = 13.sp)
    OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Upload from phone or Google Drive") }
    Text(
        "Works with YouTube, Google Drive and Dropbox links, and direct video file links. " +
            "You can also share a YouTube video to FreeClip from the YouTube app. " +
            "Only use videos you own or have permission to clip.",
        color = Mute,
        fontSize = 12.sp,
    )
}

@Composable
private fun HowItWorks() = Panel {
    Text("How it works", fontWeight = FontWeight.SemiBold)
    listOf(
        "Upload a video or paste a link",
        "AI listens to the video and audio",
        "A transcript is made",
        "Interesting moments are found",
        "Clips are made and ranked by score",
        "Crop to 9:16 vertical",
        "Auto captions are added",
        "Edit, trim and preview",
        "Export as MP4 to your Gallery",
    ).forEachIndexed { i, s ->
        Text("${i + 1}.  $s", color = Mute, fontSize = 13.sp)
    }
}

@Composable
private fun ReadyCard(vm: MainVM, onPick: () -> Unit) {
    Panel {
        Text(vm.videoName, fontWeight = FontWeight.SemiBold)
        Text("${fmt(vm.durationS)} long", color = Mute, fontSize = 13.sp)
        Text("Clip length", color = Mute, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.lengths.forEachIndexed { i, l ->
                val sel = vm.lengthIdx == i
                if (sel) Button(onClick = { vm.lengthIdx = i }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(l.label, fontSize = 12.sp) }
                else OutlinedButton(onClick = { vm.lengthIdx = i }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(l.label, fontSize = 12.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("How many clips: ${vm.nClips}", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (vm.nClips > 1) vm.nClips-- }) { Text("-") }
            OutlinedButton(onClick = { if (vm.nClips < 12) vm.nClips++ }) { Text("+") }
        }
        Button(onClick = { vm.analyze() }, modifier = Modifier.fillMaxWidth()) { Text("Find the best clips") }
        OutlinedButton(onClick = { vm.startOver() }, modifier = Modifier.fillMaxWidth()) { Text("Choose a different video") }
        Text("The first time, a 40 MB speech model downloads (needs internet once). After that analysis works offline.", color = Mute, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- progress

@Composable
private fun StepList(steps: List<String>, current: Int) {
    steps.forEachIndexed { i, s ->
        val done = i < current
        val now = i == current
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (done) "✓" else if (now) "●" else "○", color = if (done || now) Yellow else Mute)
            Text(
                s,
                color = if (now) Color.White else if (done) Color(0xFFB8BEC9) else Mute,
                fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun BusyCard(vm: MainVM) = Panel {
    Text(vm.status.ifEmpty { "Working..." }, fontWeight = FontWeight.SemiBold)
    LinearProgressIndicator(progress = { vm.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Yellow)
    Text("${(vm.progress * 100).toInt()}%  -  keep the app open and the screen on.", color = Mute, fontSize = 13.sp)
    if (vm.stage != Stage.RENDERING) {
        StepList(
            listOf("Get the video", "Analyze audio and make the transcript", "Find interesting moments", "Rank the clips"),
            vm.stepIdx,
        )
    }
    OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
}

// ---------------------------------------------------------------- 2. Edit & preview

@Composable
private fun TrimRow(label: String, value: Double, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label ${fmt(value)}", color = Mute, fontSize = 13.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("-1s") }
        OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+1s") }
    }
}

@Composable
private fun ReviewCard(vm: MainVM) {
    Text("Pick, trim and preview your clips", fontWeight = FontWeight.SemiBold)
    vm.moments.forEachIndexed { i, m ->
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(checked = m.selected, onCheckedChange = { vm.toggle(i) })
                Text(
                    "${m.score}",
                    color = Ink,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.background(Yellow, MaterialTheme.shapes.small).padding(horizontal = 9.dp, vertical = 3.dp),
                )
                Text("${(m.end - m.start).toInt()} seconds", color = Mute, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Button(onClick = { vm.previewIdx = i }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Preview") }
            }
            OutlinedTextField(
                value = m.title,
                onValueChange = { vm.retitle(i, it.take(90)) },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            TrimRow("Start", m.start, { vm.trim(i, -1.0, 0.0) }, { vm.trim(i, 1.0, 0.0) })
            TrimRow("End", m.end, { vm.trim(i, 0.0, -1.0) }, { vm.trim(i, 0.0, 1.0) })
            Text(m.reasons.joinToString("  |  "), color = Mute, fontSize = 12.sp)
            Text(m.text.take(220) + if (m.text.length > 220) "..." else "", color = Mute, fontSize = 13.sp)
        }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Word-by-word captions", modifier = Modifier.weight(1f))
            Switch(checked = vm.captions, onCheckedChange = { vm.captions = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ALL CAPS captions", modifier = Modifier.weight(1f))
            Switch(checked = vm.uppercase, onCheckedChange = { vm.uppercase = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show title for 3 seconds", modifier = Modifier.weight(1f))
            Switch(checked = vm.showTitle, onCheckedChange = { vm.showTitle = it })
        }
        Button(onClick = { vm.render() }, modifier = Modifier.fillMaxWidth()) { Text("Export selected clips (MP4)") }
        OutlinedButton(onClick = { vm.startOver() }, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
    }
}

/** Plays the clip's range in a 9:16 window, cropped the same way the export will be. Loops. */
@Composable
private fun PreviewDialog(vm: MainVM) {
    val i = vm.previewIdx
    if (i < 0 || i >= vm.moments.size) return
    val uri = vm.videoUri ?: return
    val m = vm.moments[i]
    val startMs = (m.start * 1000).toInt()
    val endMs = (m.end * 1000).toInt()

    Dialog(onDismissRequest = { vm.previewIdx = -1 }) {
        Card(colors = CardDefaults.cardColors(containerColor = Card1)) {
            Column(
                Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(m.title, fontWeight = FontWeight.SemiBold)
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .aspectRatio(9f / 16f)
                        .clipToBounds()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val h = maxHeight
                    val w = h * vm.videoAspect
                    AndroidView(
                        modifier = Modifier.requiredSize(w, h),
                        factory = { c ->
                            VideoView(c).apply {
                                val handler = Handler(Looper.getMainLooper())
                                val tick = object : Runnable {
                                    override fun run() {
                                        if (currentPosition >= endMs) seekTo(startMs)
                                        handler.postDelayed(this, 250)
                                    }
                                }
                                setVideoURI(uri)
                                setOnPreparedListener {
                                    seekTo(startMs)
                                    start()
                                    handler.postDelayed(tick, 500)
                                }
                                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                    override fun onViewAttachedToWindow(v: View) {}
                                    override fun onViewDetachedFromWindow(v: View) {
                                        handler.removeCallbacks(tick)
                                        stopPlayback()
                                    }
                                })
                            }
                        },
                    )
                }
                Text("${fmt(m.start)} - ${fmt(m.end)}. Captions and the title are added when you export.", color = Mute, fontSize = 12.sp)
                OutlinedButton(onClick = { vm.previewIdx = -1 }) { Text("Close") }
            }
        }
    }
}

// ---------------------------------------------------------------- 3. Export result

@Composable
private fun DoneCard(vm: MainVM) {
    val ctx = LocalContext.current
    Text("Your clips", fontWeight = FontWeight.SemiBold)
    Text("Saved to your Gallery in Movies/FreeClip.", color = Mute, fontSize = 13.sp)
    vm.results.forEach { r ->
        Panel {
            Text(r.title, fontWeight = FontWeight.SemiBold)
            Text("${r.seconds.toInt()} seconds, 1080x1920", color = Mute, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(r.uri), "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                }) { Text("Play") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(r.uri))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, "Share clip"))
                }) { Text("Share") }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Button(onClick = { vm.backToReview() }, modifier = Modifier.fillMaxWidth()) { Text("Make more from this video") }
    OutlinedButton(onClick = { vm.startOver() }, modifier = Modifier.fillMaxWidth()) { Text("New video") }
}
