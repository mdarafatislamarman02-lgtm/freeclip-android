package app.freeclip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainVM get() = (application as ClipApp).vm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent {
            MaterialTheme(colorScheme = Scheme) {
                var splash by remember { mutableStateOf(savedInstanceState == null) }
                LaunchedEffect(Unit) {
                    delay(1300)
                    splash = false
                }
                Box(Modifier.fillMaxSize().background(C.Bg)) {
                    if (splash) Splash() else App(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.inBackground = false
        // Opening the app: a finished-job notification is no longer needed, and a progress one must not outlive its job.
        ClipService.clearAll(this)
    }

    override fun onStop() {
        super.onStop()
        vm.inBackground = true
        vm.persist() // a running job keeps going (foreground service); this also saves edits for a later restart
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A link shared from YouTube (or any app) lands in the link box. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val url = i.getStringExtra(Intent.EXTRA_TEXT)?.let { Links.extractUrl(it) } ?: return
        if (!vm.working) {
            if (vm.stage != Stage.PICK) vm.startOver()
            vm.linkText = url
            vm.tab = 0
        }
    }
}

@Composable
private fun Splash() {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(700)) }
    Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(120.dp, Modifier.scale(0.7f + 0.3f * a.value).alpha(a.value))
            Spacer(Modifier.height(16.dp))
            Box(Modifier.alpha(a.value)) { BrandName(30) }
            Spacer(Modifier.height(6.dp))
            Text("Long videos in. Viral shorts out.", color = C.Mute, modifier = Modifier.alpha(a.value))
        }
    }
}

private class NavItem(val label: String, val icon: ImageVector)

private val NAV = listOf(
    NavItem("Home", Icons.Filled.Home),
    NavItem("Create", Icons.Filled.Add),
    NavItem("My Clips", Icons.Filled.PlayArrow),
    NavItem("Templates", Icons.Filled.Star),
    NavItem("Settings", Icons.Filled.Settings),
)

@Composable
private fun App(vm: MainVM) {
    val ctx = LocalContext.current
    // Screen-off is allowed: the foreground service keeps the work going, so the screen is NOT kept on.
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            vm.onPicked(uri)
        }
    }
    val pick = { picker.launch(arrayOf("video/*")) }

    BackHandler(enabled = vm.editIdx >= 0 || vm.tab != 0) {
        if (vm.editIdx >= 0) vm.editIdx = -1 else vm.tab = 0
    }

    Scaffold(
        containerColor = C.Bg,
        bottomBar = {
            NavigationBar(containerColor = C.Surface) {
                NAV.forEachIndexed { i, n ->
                    NavigationBarItem(
                        selected = vm.tab == i,
                        onClick = { vm.tab = i },
                        icon = { Icon(n.icon, contentDescription = n.label) },
                        label = { Text(n.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = C.Ink, selectedTextColor = C.Cyan,
                            indicatorColor = C.Violet.copy(alpha = 0.35f),
                            unselectedIconColor = C.Mute, unselectedTextColor = C.Mute,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.tab) {
                0 -> HomeScreen(vm, pick)
                1 -> CreateScreen(vm, pick)
                2 -> MyClipsScreen(vm)
                3 -> TemplatesScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
