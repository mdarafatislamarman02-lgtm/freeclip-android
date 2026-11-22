package app.freeclip

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.Violet, unfocusedBorderColor = C.Line,
    focusedTextColor = C.Ink, unfocusedTextColor = C.Ink,
    cursorColor = C.Cyan,
    focusedLabelColor = C.Cyan, unfocusedLabelColor = C.Mute,
)

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Ink, modifier = Modifier.weight(1f))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = C.Violet, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

// ================================================================ HOME

@Composable
fun HomeScreen(vm: MainVM, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LogoMark(46.dp)
            Column {
                BrandName(24)
                Text("Long videos in. Viral shorts out. Free.", color = C.Mute, fontSize = 12.sp)
            }
        }
        vm.error?.let { Text(it, color = C.Bad, fontSize = 13.sp) }
        JobBanner(vm)

        if ((vm.videoUri != null || vm.audioUri != null) && vm.stage != Stage.PICK) {
            Panel {
                Text("Continue with", color = C.Mute, fontSize = 12.sp)
                Text(vm.videoName, fontWeight = FontWeight.Bold, color = C.Ink)
                GradButton("Open", Modifier.fillMaxWidth()) { vm.tab = 1 }
            }
        } else {
            SourceCard(vm, onPick)
        }

        Text("Start from a template", fontWeight = FontWeight.Bold, color = C.Ink)
        ChipRow {
            TEMPLATES.take(6).forEach { t ->
                Column(
                    Modifier.width(150.dp).clip(RoundedCornerShape(16.dp)).background(C.Card)
                        .border(1.dp, C.Line, RoundedCornerShape(16.dp))
                        .clickable { vm.applyTemplate(t) }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(t.name, fontWeight = FontWeight.Bold, color = C.Ink, fontSize = 14.sp)
                    Text(t.desc, color = C.Mute, fontSize = 11.sp, maxLines = 3)
                }
            }
        }

        if (vm.library.isNotEmpty()) {
            Text("Recent clips", fontWeight = FontWeight.Bold, color = C.Ink)
            vm.library.take(3).forEach { ClipRow(it, vm, false) }
        }

        Panel {
            Text("How it works", fontWeight = FontWeight.Bold, color = C.Ink)
            listOf(
                "Paste a link or upload a video",
                "AI listens and writes a transcript",
                "Finds the most interesting moments",
                "Ranks clips with a viral score",
                "Crops to 9:16 with face tracking",
                "Adds word-by-word captions",
                "Edit, preview, export MP4",
            ).forEachIndexed { i, s -> Text("${i + 1}.  $s", color = C.Mute, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SourceCard(vm: MainVM, onPick: () -> Unit) = Panel {
    Text("Clip a long video", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = C.Ink)
    OutlinedTextField(
        value = vm.linkText,
        onValueChange = { vm.linkText = it },
        placeholder = { Text("Drop a YouTube or video link", color = C.Mute) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors(),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { vm.getClips() }),
    )
    GradButton("Get clips in 1 click", Modifier.fillMaxWidth(), enabled = vm.linkText.isNotBlank()) { vm.getClips() }
    GhostButton("Upload from phone or Drive", Modifier.fillMaxWidth(), onPick)
    Text(
        "YouTube, Google Drive, Dropbox and direct video links work. You can also share a YouTube video to ClipForge AI. " +
            "Only use videos you own or have permission to clip.",
        color = C.Mute, fontSize = 11.sp,
    )
}

// ================================================================ CREATE

@Composable
fun CreateScreen(vm: MainVM, onPick: () -> Unit) {
    if (vm.editIdx >= 0 && vm.editIdx < vm.moments.size) {
        EditorScreen(vm)
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Create", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = C.Ink)
        vm.error?.let { Text(it, color = C.Bad, fontSize = 13.sp) }
        JobBanner(vm)
        when (vm.stage) {
            Stage.PICK -> SourceCard(vm, onPick)
            Stage.READY -> ReadyCard(vm, onPick)
            Stage.DOWNLOADING, Stage.ANALYZING, Stage.RENDERING -> BusyCard(vm)
            Stage.REVIEW -> ReviewCard(vm)
            Stage.DONE -> DoneCard(vm)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Shows the job state (Queued / Processing / Background Processing / Paused / Failed / Completed) and Resume / Retry. */
@Composable
fun JobBanner(vm: MainVM) {
    val st = vm.jobState
    if (st == JobState.IDLE) return
    val col = when (st) {
        JobState.FAILED -> C.Bad
        JobState.PAUSED -> C.Warn
        JobState.COMPLETED -> C.Good
        else -> C.Cyan
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                st.label, color = col, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Text(vm.videoName, color = C.Mute, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
        if (st == JobState.PAUSED) Text("The app was closed while this was running. Nothing already finished is redone.", color = C.Mute, fontSize = 12.sp)
        if (st == JobState.BACKGROUND || st == JobState.PROCESSING) {
            Text("You can minimise the app or turn the screen off. Progress shows in the notification.", color = C.Mute, fontSize = 12.sp)
        }
        if ((st == JobState.PAUSED || st == JobState.FAILED) && vm.resumeAction != Resume.NONE) {
            GradButton(if (st == JobState.PAUSED) "Resume" else "Retry", Modifier.fillMaxWidth()) { vm.resume() }
        }
    }
}

@Composable
private fun DownloadBanner(vm: MainVM) {
    if (vm.videoReady || !vm.dlActive) return
    Panel {
        Text("Video downloading in the background: ${(vm.dlProgress * 100).toInt()}%", color = C.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(
            progress = { vm.dlProgress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
            color = C.Cyan, trackColor = C.Line,
        )
        Text("You can find clips now. Preview and export unlock when it finishes.", color = C.Mute, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyCard(vm: MainVM, onPick: () -> Unit) {
    DownloadBanner(vm)
    Panel {
        Text(vm.videoName, fontWeight = FontWeight.Bold, color = C.Ink)
        Text("${fmt(vm.durationS)} long", color = C.Mute, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Adjust range", color = C.Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Faster on a shorter range", color = C.Gold, fontSize = 12.sp)
        }
        RangeSlider(
            value = vm.rangeA..vm.rangeB,
            onValueChange = {
                var a = it.start
                var b = it.endInclusive
                val max = 3 * 3600f // one analysis covers at most 3 hours
                if (b - a > max) { if (a != vm.rangeA) b = a + max else a = b - max }
                vm.rangeA = a; vm.rangeB = b
            },
            valueRange = 0f..vm.durationS.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = C.Violet, inactiveTrackColor = C.Line),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(vm.rangeA.toDouble()), color = C.Ink, fontSize = 13.sp)
            Text("${fmt((vm.rangeB - vm.rangeA).toDouble())} to analyse", color = C.Mute, fontSize = 13.sp)
            Text(fmt(vm.rangeB.toDouble()), color = C.Ink, fontSize = 13.sp)
        }
        if (vm.rangeB - vm.rangeA > 25 * 60) {
            Text("Long video: analysing 20-30 minutes at a time is fastest. Run it again with another range for more clips (max 3 hours per run).", color = C.Warn, fontSize = 12.sp)
        }
    }

    Panel {
        Text("Moments to include (optional)", color = C.Ink, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = vm.prompt,
            onValueChange = { vm.prompt = it.take(200) },
            placeholder = { Text("Example: find the funniest moments", color = C.Mute) },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
            shape = RoundedCornerShape(14.dp),
            minLines = 2,
        )
        ChipRow {
            listOf("Funniest moments", "Strongest hooks", "Big reactions", "Useful tips", "Money talk").forEach { s ->
                Chip(s, false) { vm.prompt = s }
            }
        }
        Label("Clip length")
        ChipRow { LENGTHS.forEachIndexed { i, l -> Chip(l.label, vm.lengthIdx == i) { vm.lengthIdx = i } } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Number of clips: ${vm.nClips}", color = C.Ink, modifier = Modifier.weight(1f))
            GhostButton("-") { if (vm.nClips > 1) vm.nClips-- }
            GhostButton("+") { if (vm.nClips < 12) vm.nClips++ }
        }
    }

    Panel {
        Label("Aspect ratio")
        ChipRow { Ratio.values().forEach { r -> Chip(r.label, vm.ratio == r) { vm.ratio = r } } }
        Label("Layout")
        ChipRow { LayoutMode.values().forEach { l -> Chip(l.label, vm.layout == l) { vm.layout = l } } }
        Text(vm.layout.hint, color = C.Mute, fontSize = 12.sp)
        if (vm.layout.needsCam) CornerPicker(vm)
        Label("Captions")
        Toggle("Word-by-word captions", vm.captions) { vm.captions = it }
        if (vm.captions) ChipRow { PRESETS.forEachIndexed { i, p -> Chip(p.name, vm.presetIdx == i) { vm.presetIdx = i } } }
    }

    GradButton("Find the best clips", Modifier.fillMaxWidth()) { vm.saveDefaults(); vm.analyze() }
    GhostButton("Choose a different video", Modifier.fillMaxWidth()) { vm.startOver() }
    Text(
        "First time only: a 40 MB speech model downloads. After that analysis works offline.",
        color = C.Mute, fontSize = 11.sp,
    )
}

@Composable
private fun CornerPicker(vm: MainVM) {
    Label("Where is the facecam in your video?")
    ChipRow {
        listOf("Auto", "Top left", "Top right", "Bottom left", "Bottom right").forEachIndexed { i, s ->
            val idx = if (i == 0) 4 else i - 1
            Chip(s, vm.corner == idx) { vm.corner = idx }
        }
    }
}

@Composable
private fun StepList(steps: List<String>, current: Int) {
    steps.forEachIndexed { i, s ->
        val done = i < current
        val now = i == current
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (done) "✓" else if (now) "●" else "○", color = if (done || now) C.Cyan else C.Mute)
            Text(
                s,
                color = if (now) Color.White else if (done) C.Ink else C.Mute,
                fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun BusyCard(vm: MainVM) {
    if (vm.stage == Stage.ANALYZING) DownloadBanner(vm)
    BusyPanel(vm)
}

@Composable
private fun BusyPanel(vm: MainVM) = Panel {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val el = if (vm.jobStartMs > 0) ((now - vm.jobStartMs) / 1000).toInt().coerceAtLeast(0) else 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(vm.phase.ifEmpty { "Working" }, color = C.Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("%d:%02d".format(el / 60, el % 60), color = C.Mute, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Text(vm.status.ifEmpty { "Working..." }, fontWeight = FontWeight.Bold, color = C.Ink)
    val known = vm.progress > 0.001f && vm.progress < 0.999f
    if (known) {
        LinearProgressIndicator(
            progress = { vm.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
            color = C.Violet, trackColor = C.Line,
        )
    } else {
        // No exact progress for this step: show activity, not an invented percentage.
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
            color = C.Violet, trackColor = C.Line,
        )
    }
    Text(
        (if (known) "${(vm.progress * 100).toInt()}%" else "In progress") +
            (if (vm.eta.isNotEmpty()) "  -  ${vm.eta}" else "") + "  -  keeps going if you leave the app.",
        color = C.Mute, fontSize = 13.sp,
    )
    if (vm.stage != Stage.RENDERING) {
        StepList(
            listOf("Get the video", "Listen and write the transcript", "Find interesting moments", "Rank the clips"),
            vm.stepIdx,
        )
    }
    GhostButton("Cancel") { vm.cancel() }
}

@Composable
private fun ReviewCard(vm: MainVM) {
    DownloadBanner(vm)
    Text("${vm.moments.size} clips found, best first", color = C.Mute, fontSize = 13.sp)
    vm.moments.forEachIndexed { i, m ->
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreRing(m.score)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(m.title, color = C.Ink, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text("${(m.end - m.start).toInt()}s  |  ${fmt(m.start)} - ${fmt(m.end)}", color = C.Mute, fontSize = 12.sp)
                }
                Checkbox(
                    checked = m.selected, onCheckedChange = { vm.toggle(i) },
                    colors = CheckboxDefaults.colors(checkedColor = C.Violet),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScorePill("Hook", m.hook)
                ScorePill("Engagement", m.engagement)
            }
            Text(m.reasons.joinToString("  |  "), color = C.Cyan, fontSize = 12.sp)
            ExportBadge(vm.exportStatus[m.id])
            Text(m.text.take(200) + if (m.text.length > 200) "..." else "", color = C.Mute, fontSize = 12.sp)
            GradButton("Edit and preview", Modifier.fillMaxWidth(), enabled = vm.videoReady) { vm.editIdx = i }
        }
    }
    Panel {
        Label("Export quality")
        ChipRow {
            Chip("720p - fast", vm.res == 720) { vm.res = 720 }
            Chip("1080p - sharper", vm.res == 1080) { vm.res = 1080 }
        }
        if (vm.exportStatus.values.any { it != "ok" } && vm.videoReady) {
            GradButton("Retry failed clips", Modifier.fillMaxWidth()) { vm.saveDefaults(); vm.render(resume = true) }
        }
        GradButton(if (vm.videoReady) "Export selected clips (MP4)" else "Waiting for the video...", Modifier.fillMaxWidth(), enabled = vm.videoReady) { vm.saveDefaults(); vm.render() }
        GhostButton("Change settings and re-analyse", Modifier.fillMaxWidth()) { vm.stage = Stage.READY }
        GhostButton("Start over", Modifier.fillMaxWidth()) { vm.startOver() }
    }
}

@Composable
private fun ExportBadge(st: String?) {
    if (st == null) return
    if (st == "ok") Text("Exported and checked", color = C.Good, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    else Text("Export failed: ${st.removePrefix("fail: ")}", color = C.Bad, fontSize = 12.sp)
}

@Composable
private fun ScorePill(label: String, v: Int) {
    Text(
        "$label $v",
        color = C.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(C.Card).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun DoneCard(vm: MainVM) {
    val failedMoments = vm.moments.filter { vm.exportStatus[it.id]?.startsWith("fail") == true }
    if (vm.results.isNotEmpty()) {
        Text("${vm.results.size} clips exported and checked. Saved in Movies/ClipForge and listed in My Clips.", color = C.Mute, fontSize = 13.sp)
    }
    vm.notice?.let { Text(it, color = C.Warn, fontSize = 12.sp) }
    vm.libraryMsg?.let { Text(it, color = if (it.startsWith("Could not")) C.Bad else C.Good, fontSize = 12.sp) }
    vm.results.forEach { ClipRow(it, vm, false) }
    if (failedMoments.isNotEmpty()) {
        Panel {
            Text("${failedMoments.size} clip(s) failed", color = C.Bad, fontWeight = FontWeight.Bold)
            failedMoments.forEach { m ->
                Text("${m.title}: ${vm.exportStatus[m.id]?.removePrefix("fail: ")}", color = C.Mute, fontSize = 12.sp)
            }
            GradButton("Retry failed clips", Modifier.fillMaxWidth()) { vm.render(resume = true) }
        }
    }
    GradButton("Make more from this video", Modifier.fillMaxWidth()) { vm.backToReview() }
    GhostButton("New video", Modifier.fillMaxWidth()) { vm.startOver() }
}

// ================================================================ EDITOR

@Composable
private fun EditorScreen(vm: MainVM) {
    val i = vm.editIdx
    val m = vm.moments[i]
    val uri = vm.videoUri ?: return
    var face by remember(m.id, m.start, m.end) { mutableStateOf<FaceInfo?>(null) }
    LaunchedEffect(m.id, m.start, m.end) { face = vm.faceFor(m) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton("< Back") { vm.editIdx = -1 }
            Spacer(Modifier.width(12.dp))
            Text("Edit clip", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = C.Ink)
        }

        val f = face
        if (f == null) {
            Text("Finding the speaker...", color = C.Mute)
        } else {
            val fx = remember(vm.layout, vm.ratio, f, vm.corner) {
                LayoutEffect.create(vm.layout, vm.ratio, 540, f, vm.corner)
            }
            val spec = vm.specFor(f)
            val cues = remember(m.start, m.end, vm.captions, vm.presetIdx) {
                if (vm.captions) Renderer.captionCues(vm.wordsFor(m), m.start, m.end, vm.preset.upper) else emptyList()
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClipPreview(
                    uri = uri, startS = m.start, endS = m.end, effect = fx,
                    aspect = vm.ratio.aspect, cues = cues, preset = vm.preset,
                    captionY = Renderer.captionY(spec),
                    modifier = Modifier.fillMaxWidth(if (vm.ratio == Ratio.R916) 0.62f else 1f),
                )
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreRing(m.score)
                Column {
                    Text("Viral score ${m.score}", color = C.Ink, fontWeight = FontWeight.Bold)
                    Text("Hook ${m.hook}  |  Engagement ${m.engagement}", color = C.Mute, fontSize = 12.sp)
                }
            }
            Text(m.reasons.joinToString("  |  "), color = C.Cyan, fontSize = 12.sp)
        }

        Panel {
            OutlinedTextField(
                value = m.title, onValueChange = { vm.retitle(i, it.take(90)) },
                label = { Text("Title") }, modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(), shape = RoundedCornerShape(14.dp),
            )
            Toggle("Show title for 3 seconds", vm.showTitle) { vm.showTitle = it }
            TrimRow("Start", m.start, { vm.trim(i, -1.0, 0.0) }, { vm.trim(i, 1.0, 0.0) })
            TrimRow("End", m.end, { vm.trim(i, 0.0, -1.0) }, { vm.trim(i, 0.0, 1.0) })
            Text("${(m.end - m.start).toInt()} seconds", color = C.Mute, fontSize = 12.sp)
        }

        Panel {
            Label("Aspect ratio")
            ChipRow { Ratio.values().forEach { r -> Chip(r.label, vm.ratio == r) { vm.ratio = r } } }
            Label("Layout")
            ChipRow { LayoutMode.values().forEach { l -> Chip(l.label, vm.layout == l) { vm.layout = l } } }
            Text(vm.layout.hint, color = C.Mute, fontSize = 12.sp)
            if (vm.layout.needsCam) CornerPicker(vm)
            Label("Captions")
            Toggle("Word-by-word captions", vm.captions) { vm.captions = it }
            if (vm.captions) ChipRow { PRESETS.forEachIndexed { k, p -> Chip(p.name, vm.presetIdx == k) { vm.presetIdx = k } } }
        }
        GradButton("Done", Modifier.fillMaxWidth()) { vm.saveDefaults(); vm.editIdx = -1 }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TrimRow(label: String, value: Double, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label ${fmt(value)}", color = C.Mute, fontSize = 13.sp, modifier = Modifier.weight(1f))
        GhostButton("-1s", onClick = onMinus)
        GhostButton("+1s", onClick = onPlus)
    }
}

// ================================================================ MY CLIPS

@Composable
fun ClipRow(c: LibraryClip, vm: MainVM, deletable: Boolean) {
    val ctx = LocalContext.current
    val play = {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(c.uri), "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            vm.libraryMsg = "No video player could open this clip."
        }
    }
    // Real thumbnail of the saved video (not a placeholder).
    val thumb by produceState<android.graphics.Bitmap?>(null, c.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.loadThumbnail(Uri.parse(c.uri), Size(480, 270), null)
            } catch (_: Exception) {
                null
            }
        }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.width(96.dp).aspectRatio(9f / 12f).clip(RoundedCornerShape(12.dp)).background(C.Card).clickable { play() },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumb
                if (bmp != null) Image(bmp.asImageBitmap(), c.title, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                else Text("Play", color = C.Mute, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.title, color = C.Ink, fontWeight = FontWeight.Bold, maxLines = 2)
                Text("${fmt(c.seconds)}  |  ${c.layout}" + if (c.score > 0) "  |  score ${c.score}" else "", color = C.Mute, fontSize = 12.sp)
                Text(java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(c.created)), color = C.Mute, fontSize = 11.sp)
            }
        }
        GhostButton("Save to Device (Downloads)", Modifier.fillMaxWidth()) { vm.saveCopy(c) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradButton("Play", Modifier.weight(1f)) { play() }
            GhostButton("Share", Modifier.weight(1f)) {
                try {
                    val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(c.uri))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, "Share clip"))
                } catch (_: Exception) {
                    vm.libraryMsg = "Could not share this clip."
                }
            }
        }
        if (deletable) GhostButton("Remove from list") { vm.removeFromLibrary(c) }
    }
}

@Composable
fun MyClipsScreen(vm: MainVM) {
    LaunchedEffect(Unit) { vm.refreshLibrary() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("My Clips", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = C.Ink)
        vm.libraryMsg?.let { m ->
            val bad = m.startsWith("Could not") || m.startsWith("No video")
            Text(m, color = if (bad) C.Bad else C.Good, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (vm.library.isEmpty()) {
            Text("Clips you export appear here and in your Gallery (Movies/ClipForge). Nothing has been saved yet.", color = C.Mute)
        } else {
            Text("${vm.library.size} saved clip(s). They stay here after you close the app.", color = C.Mute, fontSize = 13.sp)
        }
        vm.library.forEach { ClipRow(it, vm, true) }
    }
}

// ================================================================ TEMPLATES

@Composable
fun TemplatesScreen(vm: MainVM) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Templates", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = C.Ink)
        Text("Pick a style. It sets the length, layout, captions and what the AI looks for.", color = C.Mute, fontSize = 13.sp)
        TEMPLATES.forEach { t ->
            Panel(Modifier.clickable { vm.applyTemplate(t) }) {
                Text(t.name, fontWeight = FontWeight.Bold, color = C.Ink, fontSize = 16.sp)
                Text(t.desc, color = C.Mute, fontSize = 13.sp)
                Text(
                    "${LENGTHS[t.lengthIdx].label}  |  ${t.layout.label}  |  ${t.ratio.label}  |  ${PRESETS[t.preset].name}",
                    color = C.Cyan, fontSize = 12.sp,
                )
            }
        }
    }
}

// ================================================================ SETTINGS

@Composable
fun SettingsScreen(vm: MainVM) {
    var msg by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = C.Ink)
        Panel {
            Label("Export quality")
            ChipRow {
                Chip("720p - fast", vm.res == 720) { vm.res = 720; vm.saveDefaults() }
                Chip("1080p - sharper", vm.res == 1080) { vm.res = 1080; vm.saveDefaults() }
            }
            Label("Default aspect ratio")
            ChipRow { Ratio.values().forEach { r -> Chip(r.label, vm.ratio == r) { vm.ratio = r; vm.saveDefaults() } } }
            Label("Default clip length")
            ChipRow { LENGTHS.forEachIndexed { i, l -> Chip(l.label, vm.lengthIdx == i) { vm.lengthIdx = i; vm.saveDefaults() } } }
            Label("Default caption style")
            ChipRow { PRESETS.forEachIndexed { i, p -> Chip(p.name, vm.presetIdx == i) { vm.presetIdx = i; vm.saveDefaults() } } }
            Toggle("Captions on by default", vm.captions) { vm.captions = it; vm.saveDefaults() }
        }
        Panel {
            Label("Storage")
            Text("Downloaded videos and temporary files can be cleared any time. Exported clips stay in your Gallery.", color = C.Mute, fontSize = 12.sp)
            GhostButton("Clear temporary files", Modifier.fillMaxWidth()) { msg = vm.clearCache() }
            if (msg.isNotEmpty()) Text(msg, color = C.Good, fontSize = 13.sp)
        }
        Panel {
            Label("Speed of your last job")
            if (vm.timings.isEmpty()) Text("Nothing measured yet.", color = C.Mute, fontSize = 12.sp)
            vm.timings["download"]?.let { Text("Download: ${it / 1000} s", color = C.Ink, fontSize = 13.sp) }
            vm.timings["analyze"]?.let { Text("Listening and transcript: ${it / 1000} s", color = C.Ink, fontSize = 13.sp) }
            vm.timings["export"]?.let { Text("Export, per clip: ${it / 1000} s", color = C.Ink, fontSize = 13.sp) }
            Text("Send me these numbers if it is still slow, so the real bottleneck can be fixed.", color = C.Mute, fontSize = 11.sp)
        }
        Panel {
            Label("Problems? Send the log")
            Text("If a video fails or gets stuck, tap this and send me the text. It lists the exact error, not a guess.", color = C.Mute, fontSize = 12.sp)
            val ctx = LocalContext.current
            GradButton("Share diagnostic log", Modifier.fillMaxWidth()) {
                try {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "ClipForge AI log")
                        .putExtra(Intent.EXTRA_TEXT, Diag.read(ctx))
                    ctx.startActivity(Intent.createChooser(send, "Share log"))
                } catch (_: Exception) {
                }
            }
            GhostButton("Clear log", Modifier.fillMaxWidth()) { Diag.clear(ctx) }
        }
        Panel {
            Label("Keep working in the background")
            Text(
                "Some phones (Xiaomi / MIUI, Oppo, Vivo, Samsung) stop apps when the screen is off. For long videos: " +
                    "set ClipForge AI battery to \"No restrictions\" and turn on Autostart.",
                color = C.Mute, fontSize = 12.sp,
            )
            val ctx2 = LocalContext.current
            GhostButton("Open battery settings", Modifier.fillMaxWidth()) {
                try {
                    ctx2.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    try {
                        ctx2.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx2.packageName)))
                    } catch (_: Exception) {
                    }
                }
            }
        }
        Panel {
            Label("Privacy")
            Text(
                "Everything runs on your phone: transcription, scoring, cropping and export. " +
                    "The only downloads are the video you link and a one-time 40 MB speech model. No account, no watermark.",
                color = C.Mute, fontSize = 12.sp,
            )
        }
        Panel {
            Label("About")
            Text("ClipForge AI 1.0  |  English speech only", color = C.Ink, fontSize = 13.sp)
        }
    }
}
