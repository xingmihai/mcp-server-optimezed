package com.mcp.server.tools

import android.content.Context
import android.os.Build
import android.os.Environment
import com.mcp.server.server.LogStore
import com.mcp.server.server.McpAppCtx
import com.mcp.server.server.McpServerService
import com.mcp.server.server.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 元信息 / 批量 / 统计类工具。
 * - help / tools_list / stats / health：服务器元信息
 * - batch / continue：批量调用工具
 */
object MetaTools {

    private const val START = "meta_tool_stats_start"
    private const val CALLS = "meta_tool_stats_calls"
    private const val ERRORS = "meta_tool_stats_errors"

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(help().inCategory("元信息"))
        sink.add(toolsList().inCategory("元信息"))
        sink.add(stats(ctx).inCategory("元信息"))
        sink.add(batch().inCategory("元信息"))
        sink.add(continueBatch(ctx).inCategory("元信息"))
        sink.add(health(ctx).inCategory("元信息"))
    }

    fun recordCall(name: String, error: Boolean) {
        try {
            val p = McpAppCtx.app.getSharedPreferences("meta_stats", Context.MODE_PRIVATE)
            p.edit()
                .putLong(CALLS, p.getLong(CALLS, 0) + 1)
                .apply()
            if (error) {
                p.edit().putLong(ERRORS, p.getLong(ERRORS, 0) + 1).apply()
            }
        } catch (_: Exception) {
        }
    }

    fun recordStart(ctx: Context) {
        try {
            val p = McpAppCtx.app.getSharedPreferences("meta_stats", Context.MODE_PRIVATE)
            p.edit().putLong(START, System.currentTimeMillis()).apply()
        } catch (_: Exception) {
        }
    }

    private fun help(): ToolDefinition {
        return ToolDefinition(
            "help", "查看 MCP 服务器帮助与可用工具列表（按分类组织）",
            JSONObject().put("type", "object")
        ) {
            val j = JSONObject()
            j.put("name", "android-mcp-server")
            j.put("version", "1.0.0")
            j.put("description", "运行在 Android 手机上的本地 MCP 服务器，暴露文件操作、系统管理、设备信息、应用管理、脚本执行、通讯交互、网络请求、元信息等工具。")
            val cats = JSONObject()
            for ((cat, list) in ToolKit.groupedTools()) {
                val arr = JSONArray()
                for (t in list) {
                    arr.put(JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                    })
                }
                cats.put(cat, arr)
            }
            j.put("categories", cats)
            j.put("workspace", Workspace.display(ctxFor()))
            j.put("endpoint", "/mcp")
            j.put("hint", "POST JSON-RPC 到 /mcp；GET / 或 /info 可查看服务器信息")
            j.put("pathRule", "操作必须使用以工作区为根的绝对路径（工作区: ${Workspace.display(ctxFor())}）。所有文件操作的 path/source/destination 等路径参数必须传绝对路径，且必须位于工作区目录内。")
            j
        }
    }

    private fun toolsList(): ToolDefinition {
        return ToolDefinition(
            "tools_list", "列出所有可用工具（名称、描述、参数 Schema）",
            JSONObject().put("type", "object")
        ) {
            JSONObject().put("tools", ToolKit.toMcpSchema().optJSONArray("tools"))
        }
    }

    private fun stats(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "stats", "查看服务器运行统计（运行时长、工具调用次数、错误次数、日志条数）",
            JSONObject().put("type", "object")
        ) {
            val p = McpAppCtx.app.getSharedPreferences("meta_stats", Context.MODE_PRIVATE)
            val start = p.getLong(START, 0)
            val now = System.currentTimeMillis()
            val uptime = if (start > 0) (now - start) / 1000 else 0L
            JSONObject().apply {
                put("uptimeSeconds", uptime)
                put("uptimeHuman", formatUptime(uptime))
                put("toolCalls", p.getLong(CALLS, 0))
                put("toolErrors", p.getLong(ERRORS, 0))
                put("logEntries", LogStore.snapshot().size)
                put("logLimit", 500)
                put("serverRunning", McpServerService.server?.isRunning == true)
                put("tunnelActive", McpServerService.tunnelActive)
                put("tunnelUrl", McpServerService.tunnelUrl ?: JSONObject.NULL)
                put("workspace", Workspace.display(ctx))
                put("port", Settings.port(ctx))
            }
        }
    }

    private fun batch(): ToolDefinition {
        return ToolDefinition(
            "batch", "批量调用多个工具（最多 20 个）。calls 为数组，每项为 {name, arguments}；失败不中断，返回每项结果与是否成功",
            paramSchema(listOf(
                param("calls", "array", required = true, example = "[{\"name\":\"time_now\",\"arguments\":{}}]", description = "工具调用列表，每项 {name, arguments}"),
            ), required = listOf("calls"))
        ) { args ->
            val calls = args.optJSONArray("calls")
            if (calls == null || calls.length() == 0) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "calls 不能为空",
                    "calls 需为 [{name, arguments}, ...] 数组")
            }
            if (calls.length() > 20) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "一次最多批量调用 20 个工具，当前 ${calls.length()} 个",
                    "请分批调用，每批不超过 20 个")
            }
            val results = JSONArray()
            var okCount = 0
            var errorCount = 0
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val name = c.optString("name", "")
                val arguments = c.optJSONObject("arguments") ?: JSONObject()
                val r = JSONObject()
                r.put("index", i)
                r.put("name", name)
                val tool = ToolKit.find(name)
                if (tool == null) {
                    r.put("ok", false)
                    r.put("error", "未知工具: $name")
                    r.put("errorCode", ErrorCodes.UNKNOWN_TOOL)
                    errorCount++
                } else if (!ToolKit.isEnabled(name)) {
                    r.put("ok", false)
                    r.put("error", "工具已禁用: $name")
                    r.put("errorCode", ErrorCodes.TOOL_DISABLED)
                    errorCount++
                } else {
                    try {
                        val out = tool.handler(arguments)
                        r.put("ok", true)
                        r.put("result", if (out is JSONObject) out else JSONObject().put("value", out ?: JSONObject.NULL))
                        okCount++
                    } catch (e: Exception) {
                        r.put("ok", false)
                        r.put("error", "执行失败: ${e.message ?: e.javaClass.simpleName}")
                        r.put("errorCode", ErrorCodes.INTERNAL)
                        errorCount++
                    }
                }
                results.put(r)
            }
            JSONObject().put("ok", errorCount == 0)
                .put("total", calls.length())
                .put("okCount", okCount)
                .put("errorCount", errorCount)
                .put("results", results)
        }
    }

    private fun continueBatch(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "continue", "分批处理文件或任务。files 为路径数组（目录会递归展开为文件）；method 可选：size（总大小/文件数）/ count（计数）/ read_head（读取每个文件前 maxLines 行）/ read_tail（末尾 maxLines 行）；配合 page/pageSize 分页返回",
            paramSchema(listOf(
                param("files", "array", required = true, example = "[\"/sdcard/Documents/note.txt\"]", description = "文件路径数组（目录会递归展开）"),
                param("method", "string", example = "size", allowedValues = listOf("size", "count", "read_head", "read_tail"), description = "处理方式"),
                param("page", "integer", example = 1, description = "页码（从 1 开始）"),                param("pageSize", "integer", example = 50, description = "每页数量"),
                param("maxLines", "integer", example = 50, description = "read_head/read_tail 读取的最大行数"),
            ), required = listOf("files"))
        ) { args ->
            val files = args.optJSONArray("files")
            if (files == null || files.length() == 0) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "files 不能为空", "files 需为文件路径数组")
            }
            val method = args.optString("method", "size")
            if (method !in setOf("size", "count", "read_head", "read_tail")) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "未知 method: $method",
                    "allowedValues: size / count / read_head / read_tail，例如: \"size\"")
            }
            val page = args.optInt("page", 1).coerceAtLeast(1)
            val pageSize = args.optInt("pageSize", 50).coerceIn(1, 500)
            val maxLines = args.optInt("maxLines", 50).coerceIn(1, 2000)

            // 展开目录（路径支持相对工作区路径，自动补全为绝对路径）
            fun resolveFile(p: String): File {
                val f = File(p)
                if (f.isAbsolute) return f
                return File(Workspace.root(ctx), p)
            }
            val expanded = mutableListOf<File>()
            for (i in 0 until files.length()) {
                val f = resolveFile(files.getString(i))
                if (f.isDirectory) {
                    f.walkTopDown().filter { it.isFile }.forEach { expanded.add(it) }
                } else if (f.isFile) {
                    expanded.add(f)
                }
            }
            if (expanded.isEmpty()) {
                // 报告不可解析的具体路径，方便排查
                val bad = (0 until files.length()).map { files.getString(it) }.joinToString(", ")
                return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "没有可用的文件（路径均不存在或不可访问）: $bad",
                    "请检查 files 中的路径是否存在于工作区内")
            }
            expanded.sortBy { it.absolutePath.lowercase() }

            val total = expanded.size
            val totalPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
            if (page > totalPages) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "页码越界: page=$page, 总页数=$totalPages",
                    "请传入 page 在 1..$totalPages 之间")
            }
            val slice = expanded.subList((page - 1) * pageSize, minOf(page * pageSize, total))

            val entries = JSONArray()
            when (method) {
                "size" -> {
                    var sum = 0L
                    for (f in slice) {
                        sum += f.length()
                        entries.put(JSONObject().apply {
                            put("path", f.absolutePath)
                            put("size", f.length())
                        })
                    }
                    return@ToolDefinition JSONObject().put("ok", true)
                        .put("method", "size")
                        .put("total", total)
                        .put("page", page)
                        .put("pageSize", pageSize)
                        .put("totalPages", totalPages)
                        .put("count", slice.size)
                        .put("totalBytes", sum)
                        .put("entries", entries)
                }
                "count" -> {
                    for (f in slice) {
                        entries.put(JSONObject().put("path", f.absolutePath).put("exists", f.exists()))
                    }
                    return@ToolDefinition JSONObject().put("ok", true)
                        .put("method", "count")
                        .put("total", total)
                        .put("page", page)
                        .put("pageSize", pageSize)
                        .put("totalPages", totalPages)
                        .put("count", slice.size)
                        .put("entries", entries)
                }
                "read_head", "read_tail" -> {
                    for (f in slice) {
                        val lines = try {
                            val all = f.readLines(Charsets.UTF_8)
                            if (method == "read_head") all.take(maxLines) else all.takeLast(maxLines)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        entries.put(JSONObject().apply {
                            put("path", f.absolutePath)
                            put("lines", lines.size)
                            put("content", lines.joinToString("\n"))
                        })
                    }
                    return@ToolDefinition JSONObject().put("ok", true)
                        .put("method", method)
                        .put("total", total)
                        .put("page", page)
                        .put("pageSize", pageSize)
                        .put("totalPages", totalPages)
                        .put("count", slice.size)
                        .put("entries", entries)
                }
                else -> return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "未知 method: $method",
                    "allowedValues: size / count / read_head / read_tail，例如: \"size\"")
            }
        }
    }

    private fun health(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "health", "服务器健康检查：运行状态、端口、隧道、工作区、内存/存储",
            JSONObject().put("type", "object")
        ) {
            val mem = Runtime.getRuntime()
            JSONObject().apply {
                put("ok", McpServerService.server?.isRunning == true)
                put("status", if (McpServerService.server?.isRunning == true) "running" else "stopped")
                put("version", "1.0.0")
                put("port", Settings.port(ctx))
                put("tunnelActive", McpServerService.tunnelActive)
                put("tunnelUrl", McpServerService.tunnelUrl ?: JSONObject.NULL)
                put("workspace", Workspace.display(ctx))
                put("memoryUsedMb", (mem.totalMemory() - mem.freeMemory()) / 1024 / 1024)
                put("memoryMaxMb", mem.maxMemory() / 1024 / 1024)
                val stat = android.os.StatFs(Environment.getDataDirectory().absolutePath)
                put("storageFreeMb", stat.availableBytes / 1024 / 1024)
                put("device", Build.MODEL)
                put("android", Build.VERSION.RELEASE)
                put("time", System.currentTimeMillis())
            }
        }
    }

    private fun ctxFor(): Context = McpAppCtx.app

    private fun formatUptime(sec: Long): String {
        if (sec < 60) return "${sec}秒"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}小时${m}分" else "${m}分"
    }
}
