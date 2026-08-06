package com.mcp.server.tools

import android.content.Context
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JSObject
import com.quickjs.JSValue
import com.quickjs.QuickJS
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 脚本执行类工具。
 * - mcp_javascript：内置 QuickJS 引擎（ES2020）执行 JavaScript。
 * - exec_system_command 已合并进 shell（mode=auto 自动提升 root -> Shizuku -> 应用沙箱）。
 */
object ScriptTools {

    /** 单个专用线程跑 QuickJS（Runtime 线程绑定，所有调用必须在同一线程） */
    private val jsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mcp-quickjs").apply { isDaemon = true }
    }

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(mcpJavascript().inCategory("脚本执行"))
        // exec_system_command 已删除：功能并入 shell（mode=auto 自动提升 root/shizuku）
    }

    /** 脚本中可用的 console 对象：收集 log/info/warn/error 输出 */
    private class JsConsole {
        val buffer = StringBuilder()
        fun log(msg: String) { buffer.appendLine(msg) }
        fun info(msg: String) { buffer.appendLine(msg) }
        fun warn(msg: String) { buffer.appendLine("warn: $msg") }
        fun error(msg: String) { buffer.appendLine("error: $msg") }
    }

    private fun mcpJavascript(): ToolDefinition {
        return ToolDefinition(
            "mcp_javascript", "执行 JavaScript 代码（内置 QuickJS 引擎，支持 ES2020）。支持算术运算、字符串处理、JSON 操作等。可通过 args 传入参数（对象或数组），console.log/info/warn/error 会输出到 console 字段。注意：运行在应用沙箱内，无法访问系统能力",
            paramSchema(listOf(
                param("script", "string", required = true, example = "1 + 2", description = "要执行的 JavaScript 代码"),
                param("args", "object", example = "{\"x\":1}", description = "传入脚本的参数（可通过全局 args 访问）"),
                param("timeoutMs", "integer", example = 10000, minimum = 1000, maximum = 60000, description = "超时时间（毫秒）"),
            ), required = listOf("script"))
        ) { args ->
            val script = args.optString("script", "")
            if (script.isBlank()) return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "script 不能为空",
                "script 为必填参数，传入要执行的 JS 代码")
            val timeoutMs = args.optInt("timeoutMs", 10000).coerceIn(1000, 60000)
            val rawArgs = args.opt("args")

            // QuickJS 线程绑定：在专用线程上创建 Runtime 并执行；超时后返回错误，
            // worker 线程继续执行并在 finally 中自行关闭清理
            val future: Future<JSONObject> = jsExecutor.submit<JSONObject> {
                val start = System.currentTimeMillis()
                var quickJS: QuickJS? = null
                var context: JSContext? = null
                try {
                    quickJS = QuickJS.createRuntime()
                    context = quickJS.createContext()

                    // 注入 args（数组 -> JSArray，对象 -> JSObject）
                    if (rawArgs != null && rawArgs != JSONObject.NULL) {
                        val jsValue: JSValue = when (rawArgs) {
                            is JSONArray -> JSArray(context, rawArgs)
                            is JSONObject -> JSObject(context, rawArgs)
                            else -> JSValue.NULL()
                        }
                        context.set("args", jsValue)
                    }

                    // 自定义 console：把脚本输出收集到 buffer
                    val console = JsConsole()
                    context.addJavascriptInterface(console, "console")

                    val result = context.executeScript(script, "mcp_script.js")
                    val resultObj = when (result) {
                        null -> JSONObject().put("result", JSONObject.NULL).put("resultType", "null")
                        is JSArray -> JSONObject().put("result", result.toJSONArray()).put("resultType", "array")
                        is JSObject -> JSONObject().put("result", result.toJSONObject()).put("resultType", "object")
                        is Int -> JSONObject().put("result", result).put("resultType", "number")
                        is Double -> JSONObject().put("result", result).put("resultType", "number")
                        is Boolean -> JSONObject().put("result", result).put("resultType", "boolean")
                        is String -> JSONObject().put("result", result).put("resultType", "string")
                        else -> {
                            // JSFunction / Undefined / 其他无法直接序列化的类型
                            val t = if (result is JSValue) result.typeName() else result.javaClass.simpleName
                            JSONObject().put("result", t).put("resultType", t)
                        }
                    }
                    resultObj.put("ok", true)
                        .put("console", console.buffer.toString())
                        .put("elapsedMs", System.currentTimeMillis() - start)
                } catch (e: com.quickjs.QuickJSException) {
                    JSONObject().put("error", e.name ?: "QuickJSException")
                        .put("message", e.message?.substringBefore('\n') ?: "")
                        .put("elapsedMs", System.currentTimeMillis() - start)
                } catch (e: Throwable) {
                    JSONObject().put("error", "执行失败")
                        .put("message", e.message ?: e.javaClass.simpleName)
                        .put("elapsedMs", System.currentTimeMillis() - start)
                } finally {
                    try { context?.close() } catch (_: Throwable) {}
                    try { quickJS?.close() } catch (_: Throwable) {}
                }
            }

            try {
                future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                JSONObject().put("error", "脚本执行超时（${timeoutMs}ms）")
                    .put("hint", "超时后脚本仍在后台运行，最终结果将被丢弃")
            } catch (e: java.util.concurrent.ExecutionException) {
                JSONObject().put("error", "执行失败").put("message", e.cause?.message ?: "未知错误")
            }
        }
    }

    /** JSValue 类型名（供无法直接序列化的返回值使用） */
    private fun JSValue.typeName(): String = when (type) {
        com.quickjs.JSValue.TYPE.NULL -> "null"
        com.quickjs.JSValue.TYPE.UNDEFINED -> "undefined"
        com.quickjs.JSValue.TYPE.JS_FUNCTION -> "function"
        else -> type.name.lowercase()
    }
}

/** 脚本执行权限开关设置 */
object Settings2 {
    private const val PREFS = "mcp_server_settings"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shizukuEnabled(context: Context): Boolean = prefs(context).getBoolean("shizuku_enabled", false)
    fun setShizukuEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("shizuku_enabled", on).apply()
    }

    fun rootEnabled(context: Context): Boolean = prefs(context).getBoolean("root_enabled", false)
    fun setRootEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("root_enabled", on).apply()
    }
}

/** Shizuku 访问辅助（使用官方 API，需在应用内授权） */
object ShizukuHelper {
    @Volatile
    private var granted: Boolean? = null

    /** Shizuku 是否已安装且可用（binder 是否收到） */
    fun isAvailable(context: Context): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    /** Shizuku 权限是否已授予 */
    fun isGranted(context: Context): Boolean {
        if (granted == null) {
            granted = try {
                rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        }
        return granted == true
    }

    /** 请求 Shizuku 权限（返回是否已授予） */
    fun requestPermission(context: Context): Boolean {
        return try {
            if (rikka.shizuku.Shizuku.isPreV11() || rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                // 通过 provider 引导用户授权
                rikka.shizuku.Shizuku.requestPermission(0)
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun isRunning(context: Context): Boolean {
        if (!isAvailable(context)) return false
        return try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun exec(command: String, timeoutMs: Int): ExecResult? {
        return try {
            // newProcess 是隐藏 API，通过反射调用
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val method = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), arrayOf<String>(), null) as? Process ?: return null
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            val exit = if (finished) process.exitValue() else -1
            ExecResult("shizuku", exit, out, err, 0)
        } catch (_: Throwable) {
            null
        }
    }
}

/** root 访问辅助 */
object RootHelper {
    @Volatile
    private var available: Boolean? = null

    fun isAvailable(): Boolean {
        if (available == null) {
            available = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out.contains("uid=0")
            } catch (_: Throwable) {
                false
            }
        }
        return available == true
    }

    fun exec(command: String, timeoutMs: Int): ExecResult? {
        if (!isAvailable()) return null
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            val exit = if (finished) process.exitValue() else -1
            ExecResult("root", exit, out, err, 0)
        } catch (_: Throwable) {
            null
        }
    }
}

data class ExecResult(
    val mode: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val elapsedMs: Long,
)

/** 在启用 Shizuku/root 时以更高权限执行命令 */
object ScriptExecutor {
    fun runElevated(ctx: Context, command: String, timeoutMs: Int): ExecResult? {
        // 优先 root（权限最高），再 Shizuku
        if (Settings2.rootEnabled(ctx) && RootHelper.isAvailable()) {
            return RootHelper.exec(command, timeoutMs)
        }
        if (Settings2.shizukuEnabled(ctx) && ShizukuHelper.isGranted(ctx) && ShizukuHelper.isRunning(ctx)) {
            return ShizukuHelper.exec(command, timeoutMs)
        }
        return null
    }

    /** 请求 Shizuku 授权（供 UI 调用） */
    fun requestShizuku(context: Context): Boolean {
        return try {
            if (ShizukuHelper.isGranted(context)) true
            else ShizukuHelper.requestPermission(context)
        } catch (_: Throwable) {
            false
        }
    }
}
