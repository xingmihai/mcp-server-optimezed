package com.mcp.server.server

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 一条运行日志 */
data class LogEntry(
    val id: Long,
    val time: Long,
    val level: String, // I / W / E
    val message: String,
) {
    val timeText: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
}

/** 全局运行日志收集器：服务器、隧道、悬浮窗等所有组件把日志写入这里，日志页读取展示 */
object LogStore {
    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<LogEntry>()
    private var nextId = 1L

    @Synchronized
    fun info(message: String) = add("I", message)

    @Synchronized
    fun warn(message: String) = add("W", message)

    @Synchronized
    fun error(message: String) = add("E", message)

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    private fun add(level: String, message: String) {
        entries.addLast(LogEntry(nextId++, System.currentTimeMillis(), level, message))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }
}
