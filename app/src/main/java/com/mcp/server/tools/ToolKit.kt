package com.mcp.server.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
/** 给工具打分类标记 */
fun ToolDefinition.inCategory(category: String): ToolDefinition = copy(category = category)

/**
 * 构建带高级元数据的参数 schema 条目。
 * 除 type 外还支持：allowedValues（枚举）、example（示例值）、description（参数说明）、
 * minimum/maximum/minLength/maxLength。这些信息会写入 tools/list 返回的 inputSchema，
 * 参数错误时 ParamValidator 会结合它们生成带 allowedValues + example 的用法提示。
 */
fun param(
    name: String,
    type: String,
    required: Boolean = false,
    allowedValues: List<String>? = null,
    example: Any? = null,
    description: String? = null,
    minimum: Number? = null,
    maximum: Number? = null,
    minLength: Int? = null,
    maxLength: Int? = null,
): Pair<String, JSONObject> {
    val o = JSONObject().put("type", type)
    // 标准 JSON Schema enum 关键字（MCP 客户端可直接识别）；allowedValues 保留给 ParamValidator 使用
    if (allowedValues != null && allowedValues.isNotEmpty()) {
        o.put("enum", JSONArray(allowedValues))
        o.put("allowedValues", JSONArray(allowedValues))
    }
    if (example != null) o.put("example", example)
    if (description != null) o.put("description", description)
    if (minimum != null) o.put("minimum", minimum)
    if (maximum != null) o.put("maximum", maximum)
    if (minLength != null) o.put("minLength", minLength)
    if (maxLength != null) o.put("maxLength", maxLength)
    return name to o
}

/** 由 param() 条目列表生成完整 inputSchema */
fun paramSchema(
    props: List<Pair<String, JSONObject>>,
    required: List<String> = emptyList(),
): JSONObject {
    val s = JSONObject()
    val p = JSONObject()
    for ((k, v) in props) p.put(k, v)
    s.put("type", "object")
    s.put("properties", p)
    if (required.isNotEmpty()) s.put("required", JSONArray(required))
    return s
}

/** 聚合所有工具并暴露 MCP 元数据；支持按工具启停 */
object ToolKit {

    private const val PREFS = "tool_enabled"

    private lateinit var ctx: Context

    @Volatile
    var tools: List<ToolDefinition> = emptyList()
        private set

    fun init(context: Context) {
        ctx = context.applicationContext
        val list = mutableListOf<ToolDefinition>()
        // 防重保护：注册时同名工具只保留第一个，避免重复注册导致列表 key 冲突闪退
        fun addDef(t: ToolDefinition) {
            if (list.none { it.name == t.name }) list.add(t)
        }
        fun registerAll(reg: (MutableList<ToolDefinition>) -> Unit) {
            val tmp = mutableListOf<ToolDefinition>()
            reg(tmp)
            tmp.forEach { addDef(it) }
        }
        registerAll { FileTools.register(ctx, it) }
        registerAll { SystemTools.register(ctx, it) }
        registerAll { AppTools.register(ctx, it) }
        registerAll { ScriptTools.register(ctx, it) }
        registerAll { NetTools.register(it) }
        registerAll { UtilityTools.register(ctx, it) }
        registerAll { CommunicationTools.register(ctx, it) }
        registerAll { MetaTools.register(ctx, it) }
        registerAll { CryptoTools.register(ctx, it) }
        tools = list.toList()
        rebuildBridgeTools()
    }

    /** 桥接 MCP 服务的工具（进程内缓存，由 BridgeManager.refresh 填充） */
    @Volatile
    var bridgeTools: List<com.mcp.server.bridge.BridgeToolInfo> = emptyList()
        private set

    /** 是否已从桥接服务拉取过工具（未拉取时跳过本地 bridge 工具注册） */
    @Volatile
    var bridgeLoaded: Boolean = false
        private set

    /** 刷新桥接工具注册（BridgeManager.refresh 后调用） */
    fun rebuildBridgeTools() {
        bridgeTools = com.mcp.server.bridge.BridgeManager.cachedTools
        bridgeLoaded = com.mcp.server.bridge.BridgeManager.loaded
    }

    fun find(name: String): ToolDefinition? = tools.find { it.name == name }

    /** 桥接工具是否启用（按工具 id 记忆开关；默认开启） */
    fun isBridgeEnabled(id: String): Boolean {
        val prefs = prefs()
        if (prefs.contains("bridge_$id")) return prefs.getBoolean("bridge_$id", true)
        return true
    }

    fun setBridgeEnabled(id: String, enabled: Boolean) {
        prefs().edit().putBoolean("bridge_$id", enabled).apply()
    }

    /** 客户端可见的桥接工具（仅已拉取且开关开启的） */
    fun enabledBridgeTools(): List<com.mcp.server.bridge.BridgeToolInfo> =
        if (bridgeLoaded) bridgeTools.filter { it.enabled && isBridgeEnabled(it.id) } else emptyList()

    /** 默认关闭（需要高级权限/系统权限）的工具，首次使用时需要用户手动开启 */
    private val defaultDisabled = setOf(
        "running_processes",
        "stop_app",
    )

    /** 工具是否启用；未设置过且属于默认关闭列表时返回 false */
    fun isEnabled(name: String): Boolean {
        val prefs = prefs()
        if (prefs.contains("tool_$name")) return prefs.getBoolean("tool_$name", true)
        return name !in defaultDisabled
    }

    fun setEnabled(name: String, enabled: Boolean) {
        prefs().edit().putBoolean("tool_$name", enabled).apply()
    }

    /** 客户端可见的工具（仅启用的） */
    fun enabledTools(): List<ToolDefinition> = tools.filter { isEnabled(it.name) }

    /** 固定分类顺序 */
    val categoryOrder = listOf(
        "元信息", "文件操作", "系统管理", "设备信息", "应用管理",
        "脚本执行", "网络请求", "实用工具", "通讯交互",
    )

    /** 分类 -> 工具列表（全部工具，含已禁用的） */
    fun groupedTools(): List<Pair<String, List<ToolDefinition>>> {
        val byCat = tools.groupBy { it.category }
        return categoryOrder.mapNotNull { cat ->
            val list = byCat[cat]
            if (list.isNullOrEmpty()) null else cat to list
        } + byCat.filterKeys { it !in categoryOrder }.toList()
    }

    fun toMcpSchema(): JSONObject {
        val arr = JSONArray()
        for (t in enabledTools()) {
            arr.put(JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                put("inputSchema", t.inputSchema)
            })
        }
        // 桥接工具（带前缀，仅展示已启用且开关开启的）
        for (t in enabledBridgeTools()) {
            arr.put(JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                put("inputSchema", t.inputSchema)
            })
        }
        return JSONObject().put("tools", arr)
    }

    private fun prefs(): android.content.SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
