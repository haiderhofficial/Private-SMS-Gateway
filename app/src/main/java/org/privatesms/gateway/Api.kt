package org.privatesms.gateway

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HttpResult(val code: Int, val body: String)

/** All HTTP the app does: polling for messages and reporting results. Blocking; call off the main thread. */
object Api {
    private val base = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun client(cfg: Config): OkHttpClient {
        val t = cfg.timeoutSec.coerceIn(3, 120).toLong()
        return base.newBuilder()
            .connectTimeout(t, TimeUnit.SECONDS)
            .readTimeout(t, TimeUnit.SECONDS)
            .writeTimeout(t, TimeUnit.SECONDS)
            .callTimeout(t * 2, TimeUnit.SECONDS)
            .build()
    }

    private fun request(cfg: Config, url: String, method: String, json: String?): Request {
        var httpUrl = url.toHttpUrlOrNull() ?: throw IOException("Invalid URL: $url")
        if (cfg.authType == AuthType.QUERY && cfg.token.isNotEmpty()) {
            httpUrl = httpUrl.newBuilder()
                .addQueryParameter(cfg.authName.ifBlank { "token" }, cfg.token)
                .build()
        }
        val b = Request.Builder().url(httpUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "PrivateSMSGateway/${BuildConfig.VERSION_NAME}")

        when (cfg.authType) {
            AuthType.BEARER -> if (cfg.token.isNotEmpty()) b.header("Authorization", "Bearer ${cfg.token}")
            AuthType.BASIC -> b.header("Authorization", Credentials.basic(cfg.authUser, cfg.token))
            AuthType.HEADER -> if (cfg.token.isNotEmpty()) b.header(cfg.authName.ifBlank { "X-API-Key" }, cfg.token)
            else -> Unit
        }

        cfg.extraHeaders.lineSequence().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                if (k.isNotEmpty()) b.header(k, v)
            }
        }

        if (method.equals("POST", ignoreCase = true)) b.post((json ?: "{}").toRequestBody(JSON)) else b.get()
        return b.build()
    }

    private fun exec(cfg: Config, req: Request): HttpResult =
        client(cfg).newCall(req).execute().use { r ->
            HttpResult(r.code, r.body?.string().orEmpty().take(500_000))
        }

    fun poll(cfg: Config): HttpResult {
        val json = JSONObject().put("device", cfg.deviceName).put("app", "PrivateSMSGateway").toString()
        return exec(cfg, request(cfg, cfg.pollUrl.trim(), cfg.pollMethod, json))
    }

    fun postResult(cfg: Config, id: String, status: String, error: String?, tsMs: Long): HttpResult {
        val url = cfg.resultUrl.trim().replace("{id}", URLEncoder.encode(id, "UTF-8"))
        val body = JSONObject()
            .put("id", id)
            .put("status", status)
            .put("error", error ?: JSONObject.NULL)
            .put("timestamp", isoTime(tsMs))
            .put("device", cfg.deviceName)
            .toString()
        return exec(cfg, request(cfg, url, "POST", body))
    }
}
