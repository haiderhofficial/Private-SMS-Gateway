package org.privatesms.gateway

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Small persistent log. Message bodies are never logged and phone numbers are masked. */
object AppLog {
    data class Entry(val time: Long, val level: Char, val msg: String)

    private const val MAX_ENTRIES = 400
    private const val MAX_FILE_BYTES = 256 * 1024

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private var file: File? = null

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "gateway.log")
        file = f
        if (f.exists()) {
            _entries.value = runCatching {
                f.readLines().takeLast(MAX_ENTRIES).mapNotNull { line ->
                    val p = line.split('\t', limit = 3)
                    val t = p.getOrNull(0)?.toLongOrNull()
                    if (p.size == 3 && t != null) Entry(t, p[1].firstOrNull() ?: 'I', p[2]) else null
                }
            }.getOrDefault(emptyList())
        }
    }

    fun i(msg: String) = add('I', msg)
    fun w(msg: String) = add('W', msg)
    fun e(msg: String) = add('E', msg)

    @Synchronized
    private fun add(level: Char, msg: String) {
        val clean = msg.replace('\n', ' ').replace('\t', ' ')
        val entry = Entry(System.currentTimeMillis(), level, clean)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        val f = file ?: return
        runCatching {
            f.appendText("${entry.time}\t$level\t$clean\n")
            if (f.length() > MAX_FILE_BYTES) {
                f.writeText(_entries.value.joinToString("\n", postfix = "\n") { "${it.time}\t${it.level}\t${it.msg}" })
            }
        }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        runCatching { file?.writeText("") }
    }

    fun asText(): String = _entries.value.joinToString("\n") { "${fmtDateTime(it.time)} ${it.level} ${it.msg}" }
}
