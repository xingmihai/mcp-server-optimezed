package com.mcp.server.tools

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设备信息 / 系统管理类工具 */
object SystemTools {

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(deviceInfo(ctx).inCategory("设备信息"))
        sink.add(battery(ctx).inCategory("系统管理"))
        sink.add(storageInfo(ctx).inCategory("系统管理"))
        sink.add(screenInfo(ctx).inCategory("设备信息"))
        sink.add(localeInfo(ctx).inCategory("系统管理"))
        sink.add(systemProps().inCategory("系统管理"))
        sink.add(installedApps(ctx).inCategory("应用管理"))
        sink.add(runningProcesses().inCategory("系统管理"))
        sink.add(shell(ctx).inCategory("系统管理"))
        sink.add(shizuku(ctx).inCategory("系统管理"))
    }

    private fun schema(props: List<Pair<String, String>>): JSONObject {
        val s = JSONObject()
        val propsObj = JSONObject()
        for ((k, t) in props) propsObj.put(k, JSONObject().put("type", t))
        s.put("type", "object")
        s.put("properties", propsObj)
        return s
    }

    private fun deviceInfo(ctx: Context): ToolDefinition {
        val props = listOf(
            "manufacturer" to "string", "brand" to "string", "model" to "string",
            "device" to "string", "product" to "string", "androidVersion" to "string",
            "sdkInt" to "integer", "hardware" to "string", "board" to "string",
            "fingerprint" to "string", "buildId" to "string", "buildTime" to "string",
            "serial" to "string", "abi" to "string", "bootloader" to "string",
            "radioVersion" to "string", "host" to "string", "tags" to "string",
        )
        return ToolDefinition(
            "device_info", "获取手机硬件与系统信息（品牌、型号、Android 版本、指纹等）",
            schema(props)
        ) {
            JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("product", Build.PRODUCT)
                put("androidVersion", Build.VERSION.RELEASE)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("hardware", Build.HARDWARE)
                put("board", Build.BOARD)
                put("fingerprint", Build.FINGERPRINT)
                put("buildId", Build.ID)
                put("buildTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(Build.TIME)))
                put("serial", try { Build.getSerial() } catch (_: Exception) { "unknown" })
                put("abi", Build.SUPPORTED_ABIS.joinToString(","))
                put("bootloader", Build.BOOTLOADER)
                put("radioVersion", Build.getRadioVersion() ?: "unknown")
                put("host", Build.HOST)
                put("tags", Build.TAGS)
            }
        }
    }

    private fun statusString(s: Int): String = when (s) {
        android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        android.os.BatteryManager.BATTERY_STATUS_FULL -> "full"
        android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    /** 电池 + 保活信息（电量、温度、充电状态、电池优化、WakeLock 等） */
    private fun battery(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "battery", "获取电池状态与保活信息（电量 percent、充电状态 status、温度、电压、健康度，以及电池优化、WakeLock、服务器运行状态等）",
            JSONObject().put("type", "object")
        ) {
            val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val voltage = intent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val health = intent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1
            // 保活信息
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val ignoringBatteryOptimizations = try {
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } catch (_: Exception) {
                false
            }
            val wakeLockHeld = try {
                com.mcp.server.server.WakeLockHelper.isHeld()
            } catch (_: Exception) {
                false
            }
            val serverRunning = try {
                com.mcp.server.server.McpServerService.server?.isRunning == true
            } catch (_: Exception) {
                false
            }
            JSONObject().apply {
                put("level", level)
                put("scale", scale)
                put("percent", if (scale > 0) level * 100 / scale else -1)
                put("status", statusString(status))
                put("plugged", when (plugged) {
                    android.os.BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    android.os.BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> "none"
                })
                put("charging", status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL)
                put("temperatureCelsius", if (temp > 0) temp / 10.0 else -1.0)
                put("voltageMillivolts", voltage)
                put("health", when (health) {
                    android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                    android.os.BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    else -> "unknown"
                })
                put("ignoreBatteryOptimizations", ignoringBatteryOptimizations)
                put("wakeLockHeld", wakeLockHeld)
                put("serverRunning", serverRunning)
                put("dozeMode", try {
                    pm.isDeviceIdleMode
                } catch (_: Exception) {
                    false
                })
            }
        }
    }

    private fun storageInfo(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "storage_info", "获取内部/外部存储空间使用情况（字节）",
            JSONObject().put("type", "object")
        ) {
            fun stat(path: File): JSONObject? {
                return try {
                    val sf = StatFs(path.absolutePath)
                    JSONObject().apply {
                        put("path", path.absolutePath)
                        put("total", sf.totalBytes)
                        put("available", sf.availableBytes)
                        put("free", sf.freeBytes)
                        put("used", sf.totalBytes - sf.availableBytes)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            JSONObject().apply {
                put("internal", stat(Environment.getDataDirectory()))
                val ext = Environment.getExternalStorageDirectory()
                if (ext != null) put("external", stat(ext))
                val exts = ctx.getExternalFilesDirs(null).filterNotNull()
                if (exts.isNotEmpty()) {
                    val arr = JSONArray()
                    for (e in exts) stat(e)?.let { arr.put(it) }
                    put("appExternal", arr)
                }
            }
        }
    }

    private fun screenInfo(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "screen_info", "获取屏幕信息（分辨率、密度、刷新率、亮度等）",
            JSONObject().put("type", "object")
        ) {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val refresh = try {
                val mode = wm.defaultDisplay.mode
                mode.refreshRate
            } catch (_: Exception) {
                -1.0
            }
            val brightness = try {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            } catch (_: Exception) {
                -1
            }
            JSONObject().apply {
                put("widthPixels", metrics.widthPixels)
                put("heightPixels", metrics.heightPixels)
                put("densityDpi", metrics.densityDpi)
                put("density", metrics.density)
                put("scaledDensity", metrics.scaledDensity)
                put("xdpi", metrics.xdpi)
                put("ydpi", metrics.ydpi)
                put("refreshRate", refresh)
                put("brightness", brightness)
            }
        }
    }

    private fun localeInfo(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "locale_info", "获取系统语言与时区信息",
            JSONObject().put("type", "object")
        ) {
            val loc = Locale.getDefault()
            JSONObject().apply {
                put("language", loc.language)
                put("country", loc.country)
                put("displayLanguage", loc.displayLanguage)
                put("displayCountry", loc.displayCountry)
                put("timezone", java.util.TimeZone.getDefault().id)
                put("timezoneOffsetMinutes", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
            }
        }
    }

    private fun systemProps(): ToolDefinition {
        return ToolDefinition(
            "system_properties", "读取系统属性（build 相关等）",
            schema(listOf("filter" to "string"))
        ) { args ->
            val filter = args.optString("filter", "")
            val keys = listOf(
                "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.security_patch",
                "ro.product.model", "ro.product.manufacturer", "ro.product.brand",
                "ro.product.device", "ro.hardware", "ro.bootloader", "ro.build.fingerprint",
                "ro.build.type", "ro.build.tags", "ro.build.id", "ro.build.date",
                "ro.serialno", "ro.product.cpu.abi", "ro.secure", "ro.debuggable",
                "persist.sys.timezone", "ro.config.ringtone", "gsm.version.baseband",
            )
            JSONObject().apply {
                for (k in keys) {
                    if (filter.isNotEmpty() && !k.contains(filter)) continue
                    val v = readSystemProperty(k)
                    if (!v.isNullOrEmpty()) put(k, v)
                }
            }
        }
    }

    /** android.os.SystemProperties 是隐藏 API，通过反射读取 */
    private fun readSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (_: Exception) {
            null
        }
    }

    private fun installedApps(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "installed_apps", "列出已安装应用（包名、名称、版本、安装时间等）",
            schema(listOf("includeSystem" to "boolean"))
        ) { args ->
            val includeSystem = args.optBoolean("includeSystem", false)
            val pm = ctx.packageManager
            val apps = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getInstalledApplications(0)
            val arr = JSONArray()
            for (ai in apps) {
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) continue
                try {
                    val v = pm.getPackageInfo(ai.packageName, 0)
                    arr.put(JSONObject().apply {
                        put("packageName", ai.packageName)
                        put("appName", pm.getApplicationLabel(ai).toString())
                        put("versionName", v.versionName ?: "")
                        put("versionCode", if (Build.VERSION.SDK_INT >= 28) v.longVersionCode else v.versionCode.toLong())
                        put("system", isSystem)
                        put("enabled", ai.enabled)
                        put("firstInstallTime", v.firstInstallTime)
                        put("lastUpdateTime", v.lastUpdateTime)
                        put("dataDir", ai.dataDir)
                        put("sourceDir", ai.sourceDir)
                    })
                } catch (_: Exception) {
                }
            }
            JSONObject().put("count", arr.length()).put("apps", arr)
        }
    }

    private fun runningProcesses(): ToolDefinition {
        return ToolDefinition(
            "running_processes", "获取当前正在运行的进程信息（需要 READ_PHONE_STATE 或系统权限，可能受限）",
            JSONObject().put("type", "object")
        ) {
            val am = ctxActivityManager()
            val arr = JSONArray()
            try {
                val procs = am.runningAppProcesses ?: emptyList()
                for (p in procs) {
                    arr.put(JSONObject().apply {
                        put("pid", p.pid)
                        put("processName", p.processName)
                        put("importance", p.importance)
                        put("importanceReasonCode", p.importanceReasonCode)
                    })
                }
            } catch (_: Exception) {
            }
            JSONObject().put("count", arr.length()).put("processes", arr)
        }
    }

    private fun ctxActivityManager(): android.app.ActivityManager =
        (com.mcp.server.McpApp.instance?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?: throw IllegalStateException("ActivityManager 不可用")

    /** 在设备上执行 shell 命令。mode=app 在应用沙箱内执行；mode=shizuku 需已开启并授权 Shizuku（默认关闭，需在 App 设置中开启）；mode=auto 自动尝试 root → shizuku → app。支持超时、工作目录、执行 .sh 文件 */
    private fun shell(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "shell", "在设备上执行 Shell 命令。mode 可选 app（应用沙箱，默认）/ shizuku（高权限，需先在 App 设置中开启并授权 Shizuku）/ auto（自动尝试 root → Shizuku → 应用沙箱）。返回 stdout/stderr/exitCode。支持设置超时和工作目录，并且支持执行 .sh 文件（scriptPath 指向工作区内的脚本文件）",
            paramSchema(listOf(
                param("command", "string", example = "ls -la", description = "要执行的命令（与 scriptPath 至少提供一个）"),
                param("mode", "string", example = "app", allowedValues = listOf("app", "shizuku", "auto"), description = "执行方式：app 应用沙箱 / shizuku 高权限 / auto 自动提升（root → Shizuku → app）"),
                param("timeoutMs", "integer", example = 10000, minimum = 1000, maximum = 120000, description = "超时时间（毫秒）"),
                param("workdir", "string", example = "/sdcard", description = "工作目录（通过 cd 切换）"),
                param("scriptPath", "string", example = "/sdcard/Documents/run.sh", description = "工作区内的 .sh 脚本文件路径（与 command 二选一）"),
            ))
        ) { args ->
            val scriptPath = args.optString("scriptPath", "").trim()
            val command = args.optString("command", "").trim()
            val mode = args.optString("mode", "app").lowercase()
            if (mode !in setOf("app", "shizuku", "auto")) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "未知 mode: $mode",
                    "allowedValues: app / shizuku / auto，例如: \"app\"")
            }
            if (scriptPath.isEmpty() && command.isEmpty()) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "command 与 scriptPath 至少提供一个",
                    "传 command 直接执行命令，或传 scriptPath 执行工作区内的 .sh 脚本")
            }
            val timeout = args.optInt("timeoutMs", 10000).coerceIn(1000, 120000)
            val workdir = args.optString("workdir", "").trim()

            // 支持执行 .sh 文件：读取工作区内脚本内容，作为命令执行
            val finalCommand: String
            val usedScript = scriptPath.isNotEmpty()
            if (usedScript) {
                val f = File(scriptPath)
                if (!f.isAbsolute) {
                    return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "scriptPath 必须是绝对路径（以工作区为根）: $scriptPath",
                        "例如: /sdcard/Documents/run.sh")
                }
                val reject = Workspace.rejectReason(ctx, f)
                if (reject != null) {
                    return@ToolDefinition Err.of(ErrorCodes.PATH_OUTSIDE_WORKSPACE, reject,
                        "请使用以工作区为根的绝对路径")
                }
                if (Workspace.isSaf(ctx)) {
                    val doc = Workspace.resolveDoc(ctx, f)
                    if (doc == null || !doc.exists()) return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "脚本文件不存在: $scriptPath",
                        "请检查路径")
                    val bytes = Saf.readBytes(ctx, doc)
                        ?: return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED, "无法读取脚本文件: $scriptPath",
                            "请重新选择工作区文件夹")
                    finalCommand = String(bytes, Charsets.UTF_8)
                } else {
                    if (!f.exists() || !f.isFile) return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "脚本文件不存在: $scriptPath",
                        "请检查路径")
                    finalCommand = try { f.readText(Charsets.UTF_8) } catch (e: Exception) {
                        return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "读取脚本失败: ${e.message}",
                            "检查文件是否可读")
                    }
                }
            } else {
                finalCommand = command
            }

            // shizuku 模式：要求已开启并授权 Shizuku
            if (mode == "shizuku") {
                if (!Settings2.shizukuEnabled(ctx)) {
                    return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                        "Shizuku 未开启：请在应用设置中开启 Shizuku 并完成授权",
                        "在 App 设置 -> 高级执行权限 中开启 Shizuku 并点击授权")
                }
                if (!ShizukuHelper.isGranted(ctx) || !ShizukuHelper.isRunning(ctx)) {
                    return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                        "Shizuku 未授权或不可用：请在应用设置中授权 Shizuku",
                        "在 App 设置中授权 Shizuku")
                }
                return@ToolDefinition try {
                    val result = ShizukuHelper.exec(finalCommand, timeout)
                    if (result == null) {
                        Err.of(ErrorCodes.IO_ERROR, "Shizuku 执行失败：进程无法启动（权限不足或 Shizuku 未运行）",
                            "检查 Shizuku 是否已启动并授权")
                    } else {
                        JSONObject().apply {
                            put("ok", result.exitCode == 0)
                            put("mode", "shizuku")
                            put("command", finalCommand)
                            put("exitCode", result.exitCode)
                            put("stdout", result.stdout)
                            put("stderr", result.stderr)
                            put("timeoutMs", timeout)
                        }
                    }
                } catch (e: Exception) {
                    Err.of(ErrorCodes.IO_ERROR, "执行失败: ${e.message}", "检查 Shizuku 状态")
                }
            }

            // auto 模式：自动尝试 root → shizuku → 应用沙箱
            if (mode == "auto") {
                val start = System.currentTimeMillis()
                val elevated = com.mcp.server.tools.ScriptExecutor.runElevated(ctx, finalCommand, timeout)
                if (elevated != null) {
                    return@ToolDefinition JSONObject().apply {
                        put("ok", true)
                        put("mode", elevated.mode)
                        put("exitCode", elevated.exitCode)
                        put("stdout", elevated.stdout)
                        put("stderr", elevated.stderr)
                        put("elapsedMs", elevated.elapsedMs)
                    }
                }
            }

            return@ToolDefinition try {
                val start = System.currentTimeMillis()
                // 工作目录通过 sh 的 cd 切换（shell 的 exec 没有直接指定 cwd 的可靠方式）
                val effectiveCommand = if (workdir.isNotEmpty()) "cd \"$workdir\" && $finalCommand" else finalCommand
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", effectiveCommand))
                val outText = StringBuilder()
                val errText = StringBuilder()
                val outThread = Thread { process.inputStream.bufferedReader().forEachLine { outText.appendLine(it) } }
                val errThread = Thread { process.errorStream.bufferedReader().forEachLine { errText.appendLine(it) } }
                outThread.start(); errThread.start()
                val finished = process.waitFor(timeout.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                outThread.join(500); errThread.join(500)
                if (!finished) {
                    process.destroyForcibly()
                    Err.of(ErrorCodes.TIMEOUT, "命令执行超时（${timeout}ms）", "增大 timeoutMs 或简化命令")
                        .put("command", finalCommand)
                        .put("workdir", workdir)
                        .put("stdout", outText.toString())
                        .put("stderr", errText.toString())
                } else {
                    JSONObject().apply {
                        put("ok", true)
                        put("mode", "app")
                        put("scriptFile", if (usedScript) scriptPath else JSONObject.NULL)
                        put("workdir", workdir)
                        put("exitCode", process.exitValue())
                        put("stdout", outText.toString())
                        put("stderr", errText.toString())
                        put("elapsedMs", System.currentTimeMillis() - start)
                    }
                }
            } catch (e: Exception) {
                Err.of(ErrorCodes.IO_ERROR, "执行失败: ${e.message}", "检查命令与权限")
            }
        }
    }

    /** 查看 Shizuku 权限状态，判断是否已授权 */
    private fun shizuku(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "shizuku", "查看 Shizuku 权限状态，判断是否已授权",
            JSONObject().put("type", "object")
        ) {
            JSONObject().apply {
                put("available", ShizukuHelper.isAvailable(ctx))
                put("running", ShizukuHelper.isRunning(ctx))
                put("granted", ShizukuHelper.isGranted(ctx))
                put("enabled", Settings2.shizukuEnabled(ctx))
                put("ready", Settings2.shizukuEnabled(ctx) && ShizukuHelper.isGranted(ctx) && ShizukuHelper.isRunning(ctx))
                put("version", try { rikka.shizuku.Shizuku.getVersion() } catch (_: Exception) { -1 })
                put("note", when {
                    !ShizukuHelper.isAvailable(ctx) -> "未安装或未启动 Shizuku 应用"
                    !ShizukuHelper.isGranted(ctx) -> "已安装但未授权，请在应用设置中点击授权"
                    !Settings2.shizukuEnabled(ctx) -> "已授权但开关未开启，请在应用设置中开启"
                    else -> "Shizuku 可用，可执行高权限命令"
                })
            }
        }
    }
}
