package org.privatesms.gateway

import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val c = vm.config
    val ctx = LocalContext.current
    val simOptions = remember { SmsSender.simOptions(ctx) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Server") {
            TextSetting(
                "Poll URL", c.pollUrl, { v -> vm.update { it.copy(pollUrl = v) } },
                helper = if (c.pollUrl.trim().startsWith("http://"))
                    "Plain HTTP is not encrypted. Use HTTPS unless this is a trusted local network."
                else "Endpoint the app calls to fetch the next message",
                keyboard = KeyboardType.Uri,
            )
            ChoiceSetting("Poll method", listOf("GET" to "GET", "POST" to "POST"), c.pollMethod) { v ->
                vm.update { it.copy(pollMethod = v) }
            }
            TextSetting(
                "Result URL (optional)", c.resultUrl, { v -> vm.update { it.copy(resultUrl = v) } },
                helper = "Results are POSTed here. Use {id} for the message id. Leave empty to disable reporting.",
                keyboard = KeyboardType.Uri,
            )
        }

        Section("Authentication") {
            ChoiceSetting(
                "Method",
                listOf(
                    AuthType.NONE.name to "None", AuthType.BEARER.name to "Bearer token",
                    AuthType.BASIC.name to "Basic", AuthType.HEADER.name to "Custom header",
                    AuthType.QUERY.name to "Query parameter",
                ),
                c.authType.name,
            ) { v -> vm.update { it.copy(authType = AuthType.valueOf(v)) } }

            if (c.authType == AuthType.BASIC) {
                TextSetting("Username", c.authUser, { v -> vm.update { it.copy(authUser = v) } })
            }
            if (c.authType == AuthType.HEADER || c.authType == AuthType.QUERY) {
                TextSetting(
                    if (c.authType == AuthType.HEADER) "Header name" else "Parameter name",
                    c.authName, { v -> vm.update { it.copy(authName = v) } },
                )
            }
            if (c.authType != AuthType.NONE) {
                TextSetting(
                    if (c.authType == AuthType.BASIC) "Password" else "Token / key",
                    c.token, { v -> vm.update { it.copy(token = v.trim()) } },
                    helper = "Stored encrypted on this phone", secret = true,
                )
            }
            TextSetting(
                "Extra headers (optional)", c.extraHeaders, { v -> vm.update { it.copy(extraHeaders = v) } },
                helper = "One per line, as  Name: value", singleLine = false,
            )
        }

        Section("Polling") {
            IntSetting("Poll interval (seconds)", c.pollIntervalSec, { v -> vm.update { it.copy(pollIntervalSec = v) } },
                helper = "Minimum 3. Shorter means faster delivery but more battery and server load.")
            IntSetting("Max back-off after errors (seconds)", c.maxBackoffSec, { v -> vm.update { it.copy(maxBackoffSec = v) } })
            IntSetting("Request timeout (seconds)", c.timeoutSec, { v -> vm.update { it.copy(timeoutSec = v) } })
            SwitchSetting("Wi-Fi / Ethernet only", c.wifiOnly, { v -> vm.update { it.copy(wifiOnly = v) } })
            SwitchSetting("Quiet hours (don't poll)", c.quietEnabled, { v -> vm.update { it.copy(quietEnabled = v) } })
            if (c.quietEnabled) {
                IntSetting("Quiet from hour (0-23)", c.quietStartHour, { v -> vm.update { it.copy(quietStartHour = v.coerceIn(0, 23)) } })
                IntSetting("Quiet until hour (0-23)", c.quietEndHour, { v -> vm.update { it.copy(quietEndHour = v.coerceIn(0, 23)) } })
            }
        }

        Section("Sending") {
            ChoiceSetting("SIM", simOptions, c.simSlot.toString()) { v -> vm.update { it.copy(simSlot = v.toInt()) } }
            IntSetting("Delay between messages (seconds)", c.sendDelaySec, { v -> vm.update { it.copy(sendDelaySec = v) } })
            IntSetting("Max messages per hour (0 = unlimited)", c.maxPerHour, { v -> vm.update { it.copy(maxPerHour = v) } },
                helper = "Protects your SIM from carrier flags and your balance from abuse.")
            TextSetting(
                "Default country code (optional)", c.defaultCountryCode,
                { v -> vm.update { it.copy(defaultCountryCode = v.filter { ch -> ch.isDigit() || ch == '+' }) } },
                helper = "e.g. 92. Numbers starting with 0 become +<code><number>.",
                keyboard = KeyboardType.Phone,
            )
            SwitchSetting(
                "Report delivery status", c.reportDelivery, { v -> vm.update { it.copy(reportDelivery = v) } },
                helper = "Sends “delivered” to the server when the carrier confirms it. Not every carrier does.",
            )
        }

        Section("JSON field names") {
            Text(
                "Tell the app which fields in your server's response hold each value.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextSetting("Message id", c.idField, { v -> vm.update { it.copy(idField = v.trim()) } })
            TextSetting("Phone number", c.toField, { v -> vm.update { it.copy(toField = v.trim()) } })
            TextSetting("Message text", c.bodyField, { v -> vm.update { it.copy(bodyField = v.trim()) } })
            TextSetting("SIM (optional, 1 or 2)", c.simField, { v -> vm.update { it.copy(simField = v.trim()) } })
        }

        Section("Reliability") {
            SwitchSetting("Start on boot", c.startOnBoot, { v -> vm.update { it.copy(startOnBoot = v) } })
            SwitchSetting(
                "Keep CPU awake (wake lock)", c.wakeLock, { v -> vm.update { it.copy(wakeLock = v) } },
                helper = "Most reliable. Uses more battery, so keep the phone plugged in.",
            )
            TextSetting("Device name", c.deviceName, { v -> vm.update { it.copy(deviceName = v) } },
                helper = "Sent to your server with each poll and result")
        }

        Section("Backup") {
            OutlinedButton(onClick = {
                val json = vm.config.toJson(includeToken = false).toString(2)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json)
                ctx.startActivity(Intent.createChooser(send, "Export settings"))
            }) { Text("Export settings (without token)") }

            OutlinedButton(onClick = {
                try {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    vm.replace(Config.fromJson(JSONObject(text), vm.config))
                    Toast.makeText(ctx, "Settings imported", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Clipboard doesn't contain valid settings JSON", Toast.LENGTH_LONG).show()
                }
            }) { Text("Import settings from clipboard") }
        }

        Text(
            "Private SMS Gateway v${BuildConfig.VERSION_NAME} · open source (Apache-2.0)\n" +
                "Message text is never written to the log; phone numbers are masked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun TextSetting(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    helper: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = if (helper != null) { { Text(helper) } } else null,
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun IntSetting(label: String, value: Int, onChange: (Int) -> Unit, helper: String? = null) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    TextSetting(
        label, text,
        { s ->
            val digits = s.filter { it.isDigit() }.take(6)
            text = digits
            digits.toIntOrNull()?.let(onChange)
        },
        helper = helper,
        keyboard = KeyboardType.Number,
    )
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit, helper: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (helper != null) {
                Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSetting(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}
