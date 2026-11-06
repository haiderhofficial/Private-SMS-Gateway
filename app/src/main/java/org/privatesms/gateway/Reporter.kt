package org.privatesms.gateway

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Delivers results (sent / delivered / failed) to the server's result URL.
 * Results are queued on disk first, so a network blip or app restart does not lose them.
 * Every queue mutation happens under [mutex].
 */
object Reporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private const val MAX_QUEUE = 500
    private const val MAX_ATTEMPTS = 10

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "report_queue.json")

    private fun read(ctx: Context): JSONArray {
        val f = file(ctx)
        return if (!f.exists()) JSONArray() else runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
    }

    private fun write(ctx: Context, a: JSONArray) {
        runCatching { file(ctx).writeText(a.toString()) }
    }

    fun enqueue(ctx: Context, id: String, status: String, error: String?) {
        val app = ctx.applicationContext
        if (id.isEmpty()) return
        if (SettingsStore.get(app).loadPublic().resultUrl.isBlank()) return
        scope.launch {
            mutex.withLock {
                val a = read(app)
                a.put(
                    JSONObject()
                        .put("id", id)
                        .put("status", status)
                        .put("error", error ?: JSONObject.NULL)
                        .put("ts", System.currentTimeMillis())
                        .put("attempts", 0)
                )
                while (a.length() > MAX_QUEUE) a.remove(0)
                write(app, a)
            }
            flush(app)
        }
    }

    suspend fun flush(ctx: Context) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        mutex.withLock {
            val queue = read(app)
            if (queue.length() == 0) return@withLock
            val cfg = SettingsStore.get(app).load()
            if (cfg.resultUrl.isBlank()) {
                write(app, JSONArray())
                return@withLock
            }

            val keep = JSONArray()
            var serverDown = false
            for (i in 0 until queue.length()) {
                val o = queue.getJSONObject(i)
                if (serverDown) {
                    keep.put(o)
                    continue
                }
                val id = o.getString("id")
                try {
                    val r = Api.postResult(
                        cfg, id, o.getString("status"),
                        if (o.isNull("error")) null else o.getString("error"),
                        o.getLong("ts"),
                    )
                    when {
                        r.code in 200..299 -> AppLog.i("Reported ${o.getString("status")} for ${labelFor(id)}")
                        r.code in 400..499 && r.code != 408 && r.code != 429 ->
                            AppLog.w("Server rejected result for ${labelFor(id)} (HTTP ${r.code}); dropped")
                        else -> retryLater(o, keep, "HTTP ${r.code}")
                    }
                } catch (e: Exception) {
                    serverDown = true
                    retryLater(o, keep, e.message ?: e.javaClass.simpleName)
                }
            }
            write(app, keep)
        }
    }

    private fun retryLater(o: JSONObject, keep: JSONArray, why: String) {
        val n = o.optInt("attempts", 0) + 1
        if (n >= MAX_ATTEMPTS) {
            AppLog.w("Giving up reporting ${labelFor(o.optString("id"))} after $n attempts")
            return
        }
        if (n == 1) AppLog.w("Could not report ${labelFor(o.optString("id"))} ($why); will retry")
        o.put("attempts", n)
        keep.put(o)
    }
}
