package org.privatesms.gateway

import org.json.JSONArray
import org.json.JSONObject

data class SmsJob(val id: String, val to: String, val body: String, val sim: Int?)

/**
 * Accepts any of:
 *   {"id":"1","to":"+92...","body":"hi"}          a single message
 *   [{...},{...}]                                  a list
 *   {"messages":[{...}]}  or  {"data":[{...}]}    a wrapped list
 * Field names come from the user's field-mapping settings.
 */
object MessageParser {
    fun parse(text: String, cfg: Config): List<SmsJob> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()

        val objs = ArrayList<JSONObject>()
        when {
            t.startsWith("[") -> {
                val a = JSONArray(t)
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { objs.add(it) }
            }
            t.startsWith("{") -> {
                val o = JSONObject(t)
                val arr = o.optJSONArray("messages") ?: o.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { objs.add(it) }
                } else if (!o.isNull(cfg.toField)) {
                    objs.add(o)
                }
            }
            else -> throw IllegalArgumentException("response is not JSON")
        }

        return objs.map { o ->
            SmsJob(
                id = o.str(cfg.idField),
                to = o.str(cfg.toField),
                body = o.str(cfg.bodyField),
                sim = if (o.isNull(cfg.simField)) null else o.optInt(cfg.simField, 0).takeIf { it in 1..4 },
            )
        }
    }

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else opt(key)?.toString().orEmpty()
}
