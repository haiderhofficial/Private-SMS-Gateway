package org.privatesms.gateway

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtDateTime(ms: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))
fun fmtClock(ms: Long = System.currentTimeMillis()): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
fun isoTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(ms))

/** Keeps logs useful without writing full phone numbers to disk. */
fun maskNumber(s: String): String = if (s.length <= 6) "***" else s.take(3) + "***" + s.takeLast(3)

fun labelFor(id: String): String = if (id.isEmpty()) "(no id)" else "#$id"
