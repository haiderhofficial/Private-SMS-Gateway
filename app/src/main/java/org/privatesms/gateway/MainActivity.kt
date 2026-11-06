package org.privatesms.gateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the user left the gateway switched on but the service is gone (crash, force-stop), bring it back.
        if (SettingsStore.get(this).enabled && !GatewayState.running.value) GatewayService.start(this)
        setContent { AppTheme { AppRoot() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Private SMS Gateway") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("Status") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) }, label = { Text("Settings") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) }, label = { Text("Log") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> StatusScreen(vm)
                1 -> SettingsScreen(vm)
                else -> LogScreen()
            }
        }
    }
}

@Composable
fun StatusScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val running by GatewayState.running.collectAsState()
    val lastPoll by GatewayState.lastPoll.collectAsState()
    val sent by GatewayState.sent.collectAsState()
    val delivered by GatewayState.delivered.collectAsState()
    val failed by GatewayState.failed.collectAsState()

    // Bumped whenever we return to the screen or a permission dialog closes, so the checklist refreshes.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val smsOk = remember(tick) { granted(Manifest.permission.SEND_SMS) }
    val phoneOk = remember(tick) { granted(Manifest.permission.READ_PHONE_STATE) }
    val notifOk = remember(tick) { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) }
    val batteryOk = remember(tick) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    var showTest by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) "Gateway running" else "Gateway stopped",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(lastPoll, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = running,
                    onCheckedChange = { on ->
                        if (!on) {
                            GatewayService.stop(ctx)
                        } else if (vm.config.pollUrl.isBlank()) {
                            Toast.makeText(ctx, "Set the Poll URL in Settings first", Toast.LENGTH_LONG).show()
                        } else {
                            GatewayService.start(ctx)
                        }
                    },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Counter("Sent", sent)
                Counter("Delivered", delivered)
                Counter("Failed", failed)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Setup checklist", style = MaterialTheme.typography.titleMedium)
                PermRow("Permission to send SMS", smsOk, "Grant") {
                    permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS))
                }
                PermRow("Phone state (needed to pick SIM 1 / SIM 2)", phoneOk, "Grant") {
                    permLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    PermRow("Notifications (status in the notification bar)", notifOk, "Grant") {
                        permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
                PermRow("Battery optimization off (keeps it alive)", batteryOk, "Fix") {
                    try {
                        ctx.startActivity(
                            Intent(
                                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}"),
                            ),
                        )
                    } catch (e: Exception) {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com")))
                }) { Text("Phone kills background apps? See tips for your brand") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tools", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.pollNow() }, enabled = !vm.testBusy) {
                        Text(if (vm.testBusy) "Polling…" else "Poll now")
                    }
                    OutlinedButton(onClick = { showTest = true }) { Text("Send test SMS") }
                }
                Text(
                    "“Poll now” is a real poll: if your server returns a message, it is sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                vm.testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    if (showTest) {
        var to by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("Test from Private SMS Gateway") }
        AlertDialog(
            onDismissRequest = { showTest = false },
            title = { Text("Send test SMS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = to, onValueChange = { to = it },
                        label = { Text("Phone number") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Message") })
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.sendTest(to, text); showTest = false }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { showTest = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Counter(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            modifier = Modifier.width(24.dp),
        )
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val entries by AppLog.entries.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLog.asText())
                ctx.startActivity(Intent.createChooser(send, "Share log"))
            }) { Text("Share") }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("Clear") }
        }
        if (entries.isEmpty()) {
            Text("Nothing logged yet.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(entries.asReversed()) { e ->
                Text(
                    "${fmtDateTime(e.time)} ${e.level} ${e.msg}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
