package org.privatesms.gateway

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore.get(app)

    // Compose state (not a Flow) so text fields update synchronously and the cursor never jumps.
    var config by mutableStateOf(store.load())
        private set

    var testResult by mutableStateOf<String?>(null)
        private set
    var testBusy by mutableStateOf(false)
        private set

    fun update(change: (Config) -> Config) {
        config = change(config)
        store.save(config)
    }

    fun replace(c: Config) {
        config = c
        store.save(c)
    }

    /** A real poll cycle, run immediately. Any message the server returns is sent normally. */
    fun pollNow() {
        if (testBusy) return
        testBusy = true
        viewModelScope.launch {
            val r = try {
                GatewayEngine.pollOnce(getApplication<Application>(), config, force = true)
            } catch (e: Exception) {
                PollResult(false, e.message ?: e.javaClass.simpleName)
            }
            testResult = (if (r.ok) "OK: " else "Failed: ") + r.text
            AppLog.i("Manual poll: ${r.text}")
            testBusy = false
        }
    }

    fun sendTest(to: String, body: String) {
        val app = getApplication<Application>()
        val cfg = config
        val number = GatewayEngine.normalize(to, cfg.defaultCountryCode)
        if (number == null) {
            testResult = "Failed: that doesn't look like a valid phone number"
            return
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            testResult = "Failed: SMS permission not granted"
            return
        }
        try {
            SmsSender.send(app, "", number, body.ifBlank { "Test from Private SMS Gateway" }, cfg.simSlot, cfg.reportDelivery)
            AppLog.i("Test SMS to ${maskNumber(number)}")
            testResult = "Test SMS handed to Android. The Log tab shows whether it was sent."
        } catch (e: Exception) {
            testResult = "Failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
