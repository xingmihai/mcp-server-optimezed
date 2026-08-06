package com.mcp.server.tools

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/** 通讯交互类工具 */
object CommunicationTools {

    private const val NOTIFICATION_CHANNEL_ID = "mcp_notifications"
    private const val NOTIFICATION_CHANNEL_NAME = "MCP 通知"
    private const val NOTIFICATION_ID = 1001

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(clipboard(ctx).inCategory("通讯交互"))
        sink.add(sendNotification(ctx).inCategory("通讯交互"))
    }

    private fun clipboard(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "clipboard", "读取或写入设备系统剪贴板。operation 为 read（读取，默认）或 write（写入，需提供 text）。注意：Android 10+ 后台应用无法读取剪贴板内容，可能返回空",
            paramSchema(listOf(
                param("operation", "string", example = "read", allowedValues = listOf("read", "write"), description = "操作类型：read 读取 / write 写入"),
                param("text", "string", example = "要复制的内容", description = "写入剪贴板的文本（operation=write 时必填）"),
            ))
        ) { args ->
            val operation = args.optString("operation", "read").lowercase()
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            when (operation) {
                "read" -> {
                    val clip = cm.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString() else ""
                    JSONObject().apply {
                        put("ok", true)
                        put("operation", "read")
                        put("text", text)
                        put("hasText", text.isNotEmpty())
                        put("note", if (text.isEmpty()) "剪贴板为空或无法读取（Android 10+ 后台应用受限）" else "读取成功")
                    }
                }
                "write" -> {
                    val text = args.optString("text", "")
                    if (text.isEmpty()) return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "写入剪贴板时 text 不能为空",
                        "operation=write 时必须提供 text 参数")
                    cm.setPrimaryClip(ClipData.newPlainText("mcp", text))
                    JSONObject().put("ok", true)
                        .put("operation", "write")
                        .put("textLength", text.length)
                        .put("note", "写入成功")
                }
                else -> Err.of(ErrorCodes.INVALID_VALUE, "未知 operation: $operation",
                    "allowedValues: read / write，例如: \"read\"")
            }
        }
    }

    private fun sendNotification(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "send_notification", "在设备上发送一条系统通知。支持标题 title（默认 MCP 通知）、内容 content（必填）、优先级 priority（low / normal / high / max，默认 normal）",
            paramSchema(listOf(
                param("title", "string", example = "下载完成", description = "通知标题"),
                param("content", "string", required = true, example = "文件已下载到工作区", description = "通知内容（必填）"),
                param("priority", "string", example = "normal", allowedValues = listOf("low", "normal", "high", "max"), description = "通知优先级"),
            ), required = listOf("content"))
        ) { args ->
            val content = args.optString("content", "")
            if (content.isBlank()) return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "content 不能为空",
                "content 为必填参数")
            val title = args.optString("title", "MCP 通知")
            val priority = args.optString("priority", "normal").lowercase()

            // 校验优先级取值
            if (priority !in setOf("low", "normal", "high", "max")) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "未知 priority: $priority",
                    "allowedValues: low / normal / high / max，例如: \"normal\"")
            }

            // Android 13+ 需要通知权限
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                    "缺少通知权限 POST_NOTIFICATIONS，请在系统设置中授权",
                    "在系统设置 -> 通知 中允许本应用发送通知")
            }

            // 创建通知渠道（Android 8+ 必须）
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = android.app.NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    priorityToImportance(priority)
                ).apply {
                    description = "MCP 服务器发送的通知"
                }
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.createNotificationChannel(channel)
            }

            val builder = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)
                .setPriority(priorityToCompatPriority(priority))

            val nid = (System.currentTimeMillis() % 10000).toInt()
            NotificationManagerCompat.from(ctx).notify(nid, builder.build())
            JSONObject().put("ok", true)
                .put("title", title)
                .put("content", content)
                .put("priority", priority)
                .put("notificationId", nid)
                .put("note", "通知已发送")
        }
    }

    private fun priorityToImportance(p: String): Int = when (p) {
        "low" -> android.app.NotificationManager.IMPORTANCE_LOW
        "high" -> android.app.NotificationManager.IMPORTANCE_HIGH
        "max" -> android.app.NotificationManager.IMPORTANCE_MAX
        else -> android.app.NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun priorityToCompatPriority(p: String): Int = when (p) {
        "low" -> NotificationCompat.PRIORITY_LOW
        "high" -> NotificationCompat.PRIORITY_HIGH
        "max" -> NotificationCompat.PRIORITY_MAX
        else -> NotificationCompat.PRIORITY_DEFAULT
    }
}
