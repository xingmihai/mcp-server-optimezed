package com.mcp.server.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 开机自启（需用户在 App 中开启"开机自启"开关） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!Settings.autoStart(context)) return
        val start = Intent(context, McpServerService::class.java).setAction(McpServerService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(start)
        } else {
            context.startService(start)
        }
        // 悬浮窗开关已开启且已授权时，开机后一并恢复悬浮窗
        val hasOverlay = Build.VERSION.SDK_INT < 23 ||
            android.provider.Settings.canDrawOverlays(context)
        if (Settings.floatWindowEnabled(context) && hasOverlay) {
            FloatWindowService.start(context)
        }
    }
}
