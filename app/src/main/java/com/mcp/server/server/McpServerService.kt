package com.mcp.server.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mcp.server.MainActivity
import com.mcp.server.R
import com.mcp.server.tools.ToolKit
import org.json.JSONObject

/** 前台服务：持有 MCP HTTP 服务器，保证后台持续运行 */
class McpServerService : Service() {

    companion object {
        const val ACTION_START = "com.mcp.server.START"
        const val ACTION_STOP = "com.mcp.server.STOP"
        const val CHANNEL_ID = "mcp_server"

        @Volatile
        var server: McpHttpServer? = null
            private set

        @Volatile
        var tunnel: BoreClient? = null
            private set

        @Volatile
        var tunnelUrl: String? = null
            private set

        /** 隧道是否已连接（拿到公网端口） */
        val tunnelConnected: Boolean get() = tunnel?.isConnected == true

        /** 隧道是否在尝试运行（开启但可能重连中） */
        val tunnelActive: Boolean get() = tunnel?.isRunning == true

        @Volatile
        private var instance: McpServerService? = null

        @Volatile
        private var startedAt: Long = 0L

        /** 服务器连续运行秒数（用于 /health 与 stats 工具） */
        fun uptimeSeconds(): Long {
            val s = startedAt
            return if (s > 0) (System.currentTimeMillis() - s) / 1000 else 0L
        }

        /** 供设置页调用：重启隧道（服务器运行中时生效） */
        fun restartTunnel(context: Context) {
            val srv = server ?: return
            if (!srv.isRunning) return
            val svc = instance ?: return
            tunnel?.stop()
            tunnel = null
            tunnelUrl = null
            LogStore.info("重启内网穿透隧道")
            svc.startTunnel()
        }

        /** 供设置页调用：停止隧道 */
        fun stopTunnel(context: Context) {
            tunnel?.stop()
            tunnel = null
            tunnelUrl = null
            LogStore.info("内网穿透隧道已停止")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                return START_NOT_STICKY
            }
            else -> startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (server?.isRunning == true) return
        val port = Settings.port(this)
        val token = Settings.token(this)
        ToolKit.init(this)
        // 异步拉取桥接 MCP 服务工具（不阻塞服务器启动）
        try {
            Thread {
                com.mcp.server.bridge.BridgeManager.refresh(applicationContext)
                ToolKit.rebuildBridgeTools()
                if (com.mcp.server.bridge.BridgeManager.cachedTools.isNotEmpty()) {
                    LogStore.info("已加载 ${com.mcp.server.bridge.BridgeManager.cachedTools.size} 个桥接工具")
                }
            }.start()
        } catch (_: Exception) {
        }
        startedAt = System.currentTimeMillis()
        com.mcp.server.tools.MetaTools.recordStart(this)
        if (Settings.wakeLockEnabled(this)) {
            WakeLockHelper.acquire(this)
        }
        server = McpHttpServer(
            port = port,
            token = token,
            onLog = { LogStore.info(it) },
            onToolCall = { name, args ->
                LogStore.info("工具调用: $name${if (args != null && args.length() > 0) " ${args.toString().take(120)}" else ""}")
            },
        ).also { it.start() }
        LogStore.info("服务器启动完成，端口 $port，工作区: ${Settings.workspace(this)}")

        if (Settings.tunnelEnabled(this)) {
            LogStore.info("内网穿透已开启，正在建立隧道...")
            startTunnel()
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startTunnel() {
        if (tunnel?.isRunning == true) return
        val serverHost = Settings.tunnelServer(this)
        val secret = Settings.tunnelSecret(this)
        val port = Settings.port(this)
        tunnel = BoreClient(
            serverHost = serverHost,
            localPort = port,
            requestedRemotePort = 0,
            secret = secret.ifEmpty { null },
            callback = object : BoreClient.Callback {
                override fun onConnected(publicUrl: String, mcpUrl: String) {
                    tunnelUrl = mcpUrl
                    LogStore.info("隧道已连接: $mcpUrl")
                    updateNotification("隧道已连接: $mcpUrl")
                }

                override fun onDisconnected() {
                    tunnelUrl = null
                    LogStore.warn("隧道已断开，等待自动重连...")
                    updateNotification("隧道断开，等待自动重连...")
                }

                override fun onError(message: String) {
                    tunnelUrl = null
                    LogStore.error("隧道错误: $message")
                    updateNotification("隧道错误: $message")
                }

                override fun onLog(message: String) {
                    LogStore.info(message)
                }
            },
        ).also { it.start() }
    }

    private fun stopTunnel() {
        tunnel?.stop()
        tunnel = null
        tunnelUrl = null
    }

    private fun stopServer() {
        server?.stop()
        server = null
        stopTunnel()
        // 保活开关仍开启时，保留 WakeLock（保活独立于服务器运行状态）
        if (!Settings.wakeLockEnabled(this)) {
            WakeLockHelper.release()
        }
        LogStore.info("MCP 服务器已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "MCP 服务器", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 MCP 服务器在后台运行"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String = ""): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, McpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val port = Settings.port(this)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("MCP 服务器运行中")
            .setContentText(text.ifEmpty { "端口 $port · 点击查看连接信息" })
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
