package com.mcp.server.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject

/** 实用工具类 */
object UtilityTools {

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(now().inCategory("实用工具"))
        sink.add(checkPermission(ctx).inCategory("实用工具"))
        sink.add(permissionState(ctx).inCategory("实用工具"))
        sink.add(jsonFormat().inCategory("实用工具"))
        sink.add(textConvert().inCategory("实用工具"))
    }

    private fun now(): ToolDefinition {
        return ToolDefinition(
            "time_now", "获取当前时间戳（秒/毫秒）与格式化时间",
            JSONObject().put("type", "object")
        ) {
            val now = System.currentTimeMillis()
            JSONObject().apply {
                put("epochSeconds", now / 1000)
                put("epochMillis", now)
                put("iso8601", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date(now)))
                put("local", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now)))
                put("timezone", java.util.TimeZone.getDefault().id)
            }
        }
    }

    private fun checkPermission(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "check_permission", "检查应用是否已获得指定权限（传入权限名，如 READ_SMS）",
            paramSchema(listOf(
                param("permission", "string", required = true, example = "READ_SMS", description = "权限名（可带 android.permission. 前缀）"),
            ), required = listOf("permission"))
        ) { args ->
            val name = args.optString("permission", "")
            if (name.isBlank()) return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "permission 不能为空",
                "permission 为必填参数，例如: READ_SMS")
            val perm = "android.permission.$name"
            val granted = try {
                ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                ctx.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
            }
            JSONObject().put("permission", name)
                .put("granted", granted)
                .put("note", if (granted) "已授予" else "未授予")
        }
    }

    private fun permissionState(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "permission_state", "汇总检查关键权限的授予状态",
            JSONObject().put("type", "object")
        ) {
            val perms = listOf(
                "POST_NOTIFICATIONS", "READ_PHONE_STATE",
            )
            val j = JSONObject()
            j.put("allFilesAccess",
                if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
                else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            j.put("overlayPermission", android.provider.Settings.canDrawOverlays(ctx))
            j.put("notificationPermission", try {
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false })
            for (p in perms) {
                j.put(p, try {
                    ctx.checkSelfPermission("android.permission.$p") == PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) {
                    false
                })
            }
            j
        }
    }

    private fun jsonFormat(): ToolDefinition {
        return ToolDefinition(
            "json_format", "格式化、压缩或验证 JSON 字符串。operation 可选 pretty（美化，默认）/ minify（压缩）/ validate（验证）",
            paramSchema(listOf(
                param("json", "string", required = true, example = "{\"a\":1}", description = "待处理的 JSON 字符串"),
                param("operation", "string", example = "pretty", allowedValues = listOf("pretty", "minify", "validate"), description = "处理方式"),
            ), required = listOf("json"))
        ) { args ->
            val input = args.optString("json", "")
            if (input.isBlank()) return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "json 不能为空",
                "json 为必填参数，传入 JSON 字符串")
            val op = args.optString("operation", "pretty").lowercase()
            if (op !in setOf("pretty", "minify", "validate")) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "未知 operation: $op",
                    "allowedValues: pretty / minify / validate，例如: \"pretty\"")
            }
            return@ToolDefinition try {
                val obj = if (input.trimStart().startsWith("[")) {
                    org.json.JSONArray(input)
                } else {
                    JSONObject(input)
                }
                when (op) {
                    "validate" -> JSONObject().put("valid", true).put("type", if (obj is JSONObject) "object" else "array")
                    "minify" -> JSONObject().put("ok", true).put("result", obj.toString())
                    else -> JSONObject().put("ok", true)
                        .put("result", if (obj is JSONObject) obj.toString(2) else (obj as org.json.JSONArray).toString(2))
                }
            } catch (e: Exception) {
                Err.of(ErrorCodes.UNSUPPORTED_FORMAT, "JSON 无效: ${e.message}",
                    "检查 JSON 语法是否正确（如缺少引号、多余逗号）").put("valid", false)
            }
        }
    }

    private fun textConvert(): ToolDefinition {
        return ToolDefinition(
            "text_convert", "文本格式转换。operation 可选：upper（转大写）/ lower（转小写）/ trim（去除首尾空白）/ trim_lines（每行去空白）/ remove_empty_lines（删除空行）/ normalize_newlines（统一换行符）/ reverse（反转文本）/ count（统计字符/单词/行数）",
            paramSchema(listOf(
                param("text", "string", required = true, example = "Hello World", description = "待处理的文本"),
                param("operation", "string", example = "trim",
                    allowedValues = listOf("upper", "lower", "trim", "trim_lines", "remove_empty_lines", "normalize_newlines", "reverse", "count"),
                    description = "转换方式"),
            ), required = listOf("text"))
        ) { args ->
            val text = args.optString("text", "")
            val op = args.optString("operation", "trim").lowercase()
            if (op !in setOf("upper", "lower", "trim", "trim_lines", "remove_empty_lines", "normalize_newlines", "reverse", "count")) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "未知 operation: $op",
                    "allowedValues: upper / lower / trim / trim_lines / remove_empty_lines / normalize_newlines / reverse / count，例如: \"trim\"")
            }
            val result = when (op) {
                "upper" -> text.uppercase()
                "lower" -> text.lowercase()
                "trim" -> text.trim()
                "trim_lines" -> text.lines().joinToString("\n") { it.trim() }
                "remove_empty_lines" -> text.lines().filter { it.isNotBlank() }.joinToString("\n")
                "normalize_newlines" -> text.replace("\r\n", "\n").replace("\r", "\n")
                "reverse" -> text.reversed()
                "count" -> {
                    return@ToolDefinition JSONObject().apply {
                        put("ok", true)
                        put("chars", text.length)
                        put("words", text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size)
                        put("lines", text.lines().size)
                    }
                }
                else -> return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "未知 operation: $op",
                    "allowedValues: upper / lower / trim / trim_lines / remove_empty_lines / normalize_newlines / reverse / count")
            }
            JSONObject().put("ok", true).put("operation", op).put("result", result)
        }
    }

    // 供 UI 使用的辅助方法
    fun requestAllFiles(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: Exception) {
        }
    }
}
