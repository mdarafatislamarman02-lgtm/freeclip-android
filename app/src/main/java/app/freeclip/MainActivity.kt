package app.freeclip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Yellow = Color(0xFFFFD21F)
private val Bg = Color(0xFF0F1115)
private val Card1 = Color(0xFF171A21)
private val Mute = Color(0xFF8D95A6)

private val Scheme = darkColorScheme(
    primary = Yellow,
    onPrimary = Color(0xFF1A1500),
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
        setContent {
            MaterialTheme(colorScheme = Scheme) { App(vm) }
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
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Free", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Clip", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Yellow)
        }
        Text("Long video in, vertical clips out. Works offline on your phone.", color = Mute, fontSize = 14.sp)

        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (vm.stage) {
            Stage.PICK -> PickCard { picker.launch("video/*") }
            Stage.READY -> ReadyCard(vm) { picker.launch("video/*") }
            Stage.ANALYZING, Stage.RENDERING -> BusyCard(vm)
            Stage.REVIEW -> ReviewCard(vm)
            Stage.DONE -> DoneCard(vm)
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Card1), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun PickCard(onPick: () -> Unit) = Panel {
    Text("1. Choose a video", fontWeight = FontWeight.SemiBold)
    Text("Podcasts, interviews, lectures and talking videos work best. Speech is recognised in English.", color = Mute, fontSize = 13.sp)
    Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Pick a video from this phone") }
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
                if (sel) Button(onClick = { vm.lengthIdx = i }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { Text(l.label, fontSize = 12.sp) }
                else OutlinedButton(onClick = { vm.lengthIdx = i }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { Text(l.label, fontSize = 12.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("How many clips: ${vm.nClips}", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (vm.nClips > 1) vm.nClips-- }) { Text("-") }
            OutlinedButton(onClick = { if (vm.nClips < 12) vm.nClips++ }) { Text("+") }
        }
        Button(onClick = { vm.analyze() }, modifier = Modifier.fillMaxWidth()) { Text("Find the best clips") }
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Choose a different video") }
        Text("The first time, a 40 MB speech model downloads (needs internet once). After that it works offline.", color = Mute, fontSize = 12.sp)
    }
}

@Composable
private fun BusyCard(vm: MainVM) = Panel {
    Text(vm.status.ifEmpty { "Working..." }, fontWeight = FontWeight.SemiBold)
    LinearProgressIndicator(progress = { vm.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Yellow)
    Text("${(vm.progress * 100).toInt()}%  -  keep the app open and the screen on.", color = Mute, fontSize = 13.sp)
    OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
}

@Composable
private fun ReviewCard(vm: MainVM) {
    Text("2. Pick your clips", fontWeight = FontWeight.SemiBold)
    vm.moments.forEachIndexed { i, m ->
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(checked = m.selected, onCheckedChange = { vm.toggle(i) })
                Text(
                    "${m.score}",
                    color = Color(0xFF1A1500),
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.background(Yellow, MaterialTheme.shapes.small).padding(horizontal = 9.dp, vertical = 3.dp),
                )
                Text("${fmt(m.start)} - ${fmt(m.end)}  (${(m.end - m.start).toInt()}s)", color = Mute, fontSize = 13.sp)
            }
            OutlinedTextField(
                value = m.title,
                onValueChange = { vm.retitle(i, it.take(90)) },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
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
        Button(onClick = { vm.render() }, modifier = Modifier.fillMaxWidth()) { Text("Make selected clips") }
        OutlinedButton(onClick = { vm.startOver() }, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
    }
}

@Composable
private fun DoneCard(vm: MainVM) {
    val ctx = LocalContext.current
    Text("3. Your clips", fontWeight = FontWeight.SemiBold)
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
