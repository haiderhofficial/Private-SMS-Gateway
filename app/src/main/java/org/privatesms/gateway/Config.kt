package org.privatesms.gateway

import android.os.Build
import org.json.JSONObject

enum class AuthType { NONE, BEARER, BASIC, HEADER, QUERY }

/** Every user-editable setting. Persisted by [SettingsStore]; the token is stored encrypted. */
data class Config(
    // Server
    val pollUrl: String = "",
    val pollMethod: String = "GET",
    val resultUrl: String = "",
    // Authentication
    val authType: AuthType = AuthType.BEARER,
    val authUser: String = "",
    val token: String = "",
    val authName: String = "X-API-Key",
    val extraHeaders: String = "",
    // Polling
    val timeoutSec: Int = 20,
    val pollIntervalSec: Int = 15,
    val maxBackoffSec: Int = 300,
    val wifiOnly: Boolean = false,
    val quietEnabled: Boolean = false,
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 6,
    // Sending
    val simSlot: Int = 0, // 0 = system default, 1 = SIM 1, 2 = SIM 2
    val sendDelaySec: Int = 3,
    val maxPerHour: Int = 0, // 0 = unlimited
    val defaultCountryCode: String = "",
    val reportDelivery: Boolean = true,
    // JSON field mapping
    val idField: String = "id",
    val toField: String = "to",
    val bodyField: String = "body",
    val simField: String = "sim",
    // Reliability
    val startOnBoot: Boolean = true,
    val wakeLock: Boolean = true,
    val deviceName: String = Build.MODEL.orEmpty().ifBlank { "android" },
) {
    fun toJson(includeToken: Boolean = false): JSONObject {
        val o = JSONObject()
        o.put("pollUrl", pollUrl)
        o.put("pollMethod", pollMethod)
        o.put("resultUrl", resultUrl)
        o.put("authType", authType.name)
        o.put("authUser", authUser)
        o.put("authName", authName)
        o.put("extraHeaders", extraHeaders)
        o.put("timeoutSec", timeoutSec)
        o.put("pollIntervalSec", pollIntervalSec)
        o.put("maxBackoffSec", maxBackoffSec)
        o.put("wifiOnly", wifiOnly)
        o.put("quietEnabled", quietEnabled)
        o.put("quietStartHour", quietStartHour)
        o.put("quietEndHour", quietEndHour)
        o.put("simSlot", simSlot)
        o.put("sendDelaySec", sendDelaySec)
        o.put("maxPerHour", maxPerHour)
        o.put("defaultCountryCode", defaultCountryCode)
        o.put("reportDelivery", reportDelivery)
        o.put("idField", idField)
        o.put("toField", toField)
        o.put("bodyField", bodyField)
        o.put("simField", simField)
        o.put("startOnBoot", startOnBoot)
        o.put("wakeLock", wakeLock)
        o.put("deviceName", deviceName)
        if (includeToken) o.put("token", token)
        return o
    }

    companion object {
        /** Reads known keys from [o]; anything missing keeps the value from [base]. */
        fun fromJson(o: JSONObject, base: Config = Config()): Config = base.copy(
            pollUrl = o.optString("pollUrl", base.pollUrl),
            pollMethod = o.optString("pollMethod", base.pollMethod),
            resultUrl = o.optString("resultUrl", base.resultUrl),
            authType = runCatching { AuthType.valueOf(o.optString("authType", base.authType.name)) }
                .getOrDefault(base.authType),
            authUser = o.optString("authUser", base.authUser),
            token = if (o.has("token")) o.optString("token") else base.token,
            authName = o.optString("authName", base.authName),
            extraHeaders = o.optString("extraHeaders", base.extraHeaders),
            timeoutSec = o.optInt("timeoutSec", base.timeoutSec),
            pollIntervalSec = o.optInt("pollIntervalSec", base.pollIntervalSec),
            maxBackoffSec = o.optInt("maxBackoffSec", base.maxBackoffSec),
            wifiOnly = o.optBoolean("wifiOnly", base.wifiOnly),
            quietEnabled = o.optBoolean("quietEnabled", base.quietEnabled),
            quietStartHour = o.optInt("quietStartHour", base.quietStartHour),
            quietEndHour = o.optInt("quietEndHour", base.quietEndHour),
            simSlot = o.optInt("simSlot", base.simSlot),
            sendDelaySec = o.optInt("sendDelaySec", base.sendDelaySec),
            maxPerHour = o.optInt("maxPerHour", base.maxPerHour),
            defaultCountryCode = o.optString("defaultCountryCode", base.defaultCountryCode),
            reportDelivery = o.optBoolean("reportDelivery", base.reportDelivery),
            idField = o.optString("idField", base.idField),
            toField = o.optString("toField", base.toField),
            bodyField = o.optString("bodyField", base.bodyField),
            simField = o.optString("simField", base.simField),
            startOnBoot = o.optBoolean("startOnBoot", base.startOnBoot),
            wakeLock = o.optBoolean("wakeLock", base.wakeLock),
            deviceName = o.optString("deviceName", base.deviceName),
        )
    }
}
