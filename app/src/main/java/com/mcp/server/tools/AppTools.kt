package com.mcp.server.tools

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** 应用管理类工具 */
object AppTools {

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(appInfo(ctx).inCategory("应用管理"))
        sink.add(stopApp(ctx).inCategory("应用管理"))
    }

    private fun schema(props: List<Pair<String, String>>): JSONObject {
        val s = JSONObject()
        val p = JSONObject()
        for ((k, t) in props) p.put(k, JSONObject().put("type", t))
        s.put("type", "object")
        s.put("properties", p)
        s.put("required", JSONArray(listOf(props.first().first)))
        return s
    }

    private fun require(args: JSONObject, name: String): String {
        val v = args.optString(name, "").trim()
        if (v.isEmpty()) throw IllegalArgumentException("缺少必要参数: $name")
        return v
    }

    private fun appInfo(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "app_info", "获取单个应用的详细信息",
            paramSchema(listOf(
                param("packageName", "string", required = true, example = "com.android.settings", description = "应用包名"),
            ), required = listOf("packageName"))
        ) { args ->
            val pm = ctx.packageManager
            val pkg = require(args, "packageName")
            return@ToolDefinition try {
                val ai = if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
                val v = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
                JSONObject().apply {
                    put("packageName", pkg)
                    put("appName", pm.getApplicationLabel(ai).toString())
                    put("versionName", v.versionName ?: "")
                    put("versionCode", if (Build.VERSION.SDK_INT >= 28) v.longVersionCode else v.versionCode.toLong())
                    put("system", (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    put("enabled", ai.enabled)
                    put("firstInstallTime", v.firstInstallTime)
                    put("lastUpdateTime", v.lastUpdateTime)
                    put("uid", ai.uid)
                    put("dataDir", ai.dataDir)
                    put("sourceDir", ai.sourceDir)
                    put("targetSdk", ai.targetSdkVersion)
                    put("minSdk", try { ai.minSdkVersion } catch (_: Exception) { -1 })
                }
            } catch (e: Exception) {
                Err.of(ErrorCodes.FILE_NOT_FOUND, "未找到应用 $pkg",
                    "请检查包名是否正确（可用 installed_apps 查看已安装应用列表）")
            }
        }
    }

    private fun stopApp(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "stop_app", "强制停止指定应用。已开启 Root/Shizuku 高级权限时通过 `am force-stop` 强制停止（推荐，普通 killBackgroundProcesses 需要系统权限）；否则回退到结束后台进程（可能失败）",
            paramSchema(listOf(
                param("packageName", "string", required = true, example = "com.android.settings", description = "应用包名"),
            ), required = listOf("packageName"))
        ) { args ->
            val pkg = require(args, "packageName")
            return@ToolDefinition try {
                // 优先使用已开启的 root / Shizuku 权限执行 am force-stop
                val elevated = com.mcp.server.tools.ScriptExecutor.runElevated(ctx, "am force-stop $pkg", 10000)
                if (elevated != null) {
                    val ok = elevated.exitCode == 0
                    JSONObject().apply {
                        put("packageName", pkg)
                        put("mode", elevated.mode)
                        put("exitCode", elevated.exitCode)
                        put("stdout", elevated.stdout)
                        put("stderr", elevated.stderr)
                        put("stopped", ok)
                        if (ok) {
                            put("note", "已通过 ${elevated.mode} 权限执行 am force-stop 强制停止应用")
                        } else {
                            put("note", "am force-stop 执行失败，可能不是 root/Shizuku 权限或应用受保护")
                        }
                    }
                } else {
                    // 无高级权限：回退到 killBackgroundProcesses（仅结束后台进程）
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val killed = am.killBackgroundProcesses(pkg)
                    JSONObject().put("packageName", pkg).put("killed", killed)
                        .put("mode", "app")
                        .put("stopped", killed)
                        .put("note", "未开启 Root/Shizuku，仅结束后台进程（killBackgroundProcesses）；如需强制停止，请在设置中开启 Root 或 Shizuku 后重试")
                }
            } catch (e: Exception) {
                Err.of(ErrorCodes.IO_ERROR, "操作失败: ${e.message}", "检查包名与权限")
            }
        }
    }
}
