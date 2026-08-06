package com.mcp.server.server

import android.content.Context
import android.os.PowerManager

/** WakeLock 管理：防止息屏后 CPU 休眠 */
object WakeLockHelper {

    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        release()
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "McpServer:KeepRunning"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            wakeLock = null
        }
    }

    @Synchronized
    fun release() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    @Synchronized
    fun isHeld(): Boolean = try {
        wakeLock?.isHeld == true
    } catch (_: Exception) {
        false
    }
}
