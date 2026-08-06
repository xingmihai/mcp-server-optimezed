package com.mcp.server.server

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** Server settings persisted in SharedPreferences. */
object Settings {
    private const val PREFS = "mcp_server_settings"

    fun port(context: Context): Int = prefs(context).getInt("port", 1145)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit().putInt("port", port.coerceIn(1, 65535)).apply()
    }

    /** 服务器是否已运行过（用于引导弹窗） */
    fun everStarted(context: Context): Boolean = prefs(context).getBoolean("ever_started", false)

    fun setEverStarted(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("ever_started", on).apply()
    }

    fun autoStart(context: Context): Boolean = prefs(context).getBoolean("auto_start", false)

    fun setAutoStart(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("auto_start", on).apply()
    }

    fun tokenEnabled(context: Context): Boolean = prefs(context).getBoolean("token_enabled", false)

    fun setTokenEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("token_enabled", on).apply()
    }

    fun token(context: Context): String {
        val p = prefs(context)
        var t = p.getString("token", null)
        if (t.isNullOrBlank()) {
            t = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            p.edit().putString("token", t).apply()
        }
        return t
    }

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString("token", token).apply()
    }

    /** WakeLock 保持运行 */
    fun wakeLockEnabled(context: Context): Boolean = prefs(context).getBoolean("wakelock", false)

    fun setWakeLockEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("wakelock", on).apply()
    }

    /** 悬浮窗状态显示 */
    fun floatWindowEnabled(context: Context): Boolean = prefs(context).getBoolean("float_window", false)

    fun setFloatWindowEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("float_window", on).apply()
    }

    /** 工作区根目录（客户端只能操作该目录内文件） */
    fun workspace(context: Context): String =
        prefs(context).getString("workspace", null) ?: "/storage/emulated/0/"

    fun setWorkspace(context: Context, path: String) {
        prefs(context).edit().putString("workspace", path).apply()
    }

    /** 工作区 SAF 目录 URI（使用系统文件夹选择器选择后保存） */
    fun workspaceUri(context: Context): String? =
        prefs(context).getString("workspace_uri", null)

    fun setWorkspaceUri(context: Context, uri: String?) {
        prefs(context).edit().putString("workspace_uri", uri).apply()
    }

    /** 内网穿透开关 */
    fun tunnelEnabled(context: Context): Boolean = prefs(context).getBoolean("tunnel_enabled", false)

    fun setTunnelEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("tunnel_enabled", on).apply()
    }

    /** 内网穿透远程服务器（默认 bore.pub） */
    fun tunnelServer(context: Context): String =
        prefs(context).getString("tunnel_server", null) ?: "bore.pub"

    fun setTunnelServer(context: Context, server: String) {
        prefs(context).edit().putString("tunnel_server", server).apply()
    }

    /** 内网穿透认证密钥（公共服务器可留空） */
    fun tunnelSecret(context: Context): String =
        prefs(context).getString("tunnel_secret", "") ?: ""

    fun setTunnelSecret(context: Context, secret: String) {
        prefs(context).edit().putString("tunnel_secret", secret).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Network helpers. */
object Net {
    fun ipv4Addresses(): List<String> {
        val out = mutableListOf<String>()
        try {
            val enums = NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in enums) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        out.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun wifiSsid(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val ssid = info?.ssid
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") null else ssid.trim('"')
        } catch (_: Exception) {
            null
        }
    }

    fun deviceName(): String = android.os.Build.MODEL

    fun statusJson(context: Context, running: Boolean, pid: Long?): JSONObject {
        val j = JSONObject()
        j.put("status", if (running) "running" else "stopped")
        j.put("version", "1.0.0")
        j.put("port", Settings.port(context))
        j.put("token", Settings.token(context))
        j.put("tokenEnabled", Settings.tokenEnabled(context))
        j.put("workspace", Settings.workspace(context))
        j.put("protocol", "2025-03-26")
        j.put("device", deviceName())
        j.put("pid", pid ?: JSONObject.NULL)
        val ips = ipv4Addresses()
        j.put("ipv4", JSONObject().apply { for ((i, ip) in ips.withIndex()) put("address$i", ip) })
        val ssid = wifiSsid(context)
        if (ssid != null) j.put("wifi", ssid)
        return j
    }
}
