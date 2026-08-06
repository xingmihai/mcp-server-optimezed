package com.mcp.server.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mcp.server.MainActivity
import com.mcp.server.R

/**
 * 悬浮窗：开关开启后，只要应用在运行就持续显示。
 * 胶囊形状小窗，显示应用名 + 服务器运行状态（运行中/已停止），可拖动，点击回到主界面。
 * 作为前台服务运行（低优先级通知），避免 Android 8+ 后台启动限制，同时更不易被系统回收。
 * 需要 SYSTEM_ALERT_WINDOW 权限。
 */
class FloatWindowService : Service() {

    companion object {
        const val ACTION_START = "com.mcp.server.FLOAT_START"
        const val ACTION_STOP = "com.mcp.server.FLOAT_STOP"
        const val CHANNEL_ID = "mcp_float"
        private const val NOTIFICATION_ID = 2

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            val i = Intent(context, FloatWindowService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatWindowService::class.java).setAction(ACTION_STOP))
        }
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateText()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 必须尽快进入前台，否则 startForegroundService 会崩溃
                startForeground(NOTIFICATION_ID, buildNotification())
                if (floatView == null) showFloat()
                else updateText()
                return START_STICKY
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "悬浮窗服务运行状态"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("MCP 悬浮窗运行中")
            .setContentText("悬浮窗已开启")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun showFloat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 应用名
            val nameTv = TextView(this).apply {
                text = "MCP 服务器"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            // 运行状态
            val statusTv = TextView(this).apply {
                setTextColor(Color.parseColor("#C8E6C9"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                isClickable = true
            }
            // 状态提示（无背景色，与胶囊主体融为一体）
            val statusBg = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(statusTv, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            // 主体
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat()
                    setColor(Color.parseColor("#CC3F51B5"))
                }
                addView(nameTv, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(statusBg, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                })
            }

            val type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 200
            }

            layout.setOnClickListener {
                val i = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
            layout.setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var moved = false

                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = params.x
                            startY = params.y
                            touchX = e.rawX
                            touchY = e.rawY
                            moved = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (e.rawX - touchX).toInt()
                            val dy = (e.rawY - touchY).toInt()
                            if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                            params.x = startX + dx
                            params.y = startY + dy
                            try {
                                windowManager?.updateViewLayout(v, params)
                            } catch (_: Exception) {
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) v.performClick()
                        }
                    }
                    return true
                }
            })

            windowManager?.addView(layout, params)
            floatView = layout
            updateText()
            handler.post(updateRunnable)
            running = true
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun updateText() {
        val view = floatView ?: return
        val srv = McpServerService.server
        val active = srv?.isRunning == true
        try {
            val statusBg = (view as LinearLayout).getChildAt(1) as LinearLayout
            val statusTv = statusBg.getChildAt(0) as TextView
            statusTv.text = if (active) "● 运行中" else "● 已停止"
            statusTv.setTextColor(if (active) Color.parseColor("#C8E6C9") else Color.parseColor("#FFCDD2"))
        } catch (_: Exception) {
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(updateRunnable)
        try {
            floatView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        floatView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
