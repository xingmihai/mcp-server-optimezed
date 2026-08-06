package com.mcp.server.tools

import org.json.JSONObject

/**
 * MCP 工具统一错误码。
 *
 * 所有工具返回的失败结果统一使用以下结构（MCP 工具结果 + isError）：
 *   {
 *     "errorCode": "FILE_NOT_FOUND",   // 稳定的机器可读错误码
 *     "error": "文件不存在: /sdcard/xxx",
 *     "hint": "...",                    // 可选：如何修复/正确用法
 *     "isError": true                   // MCP 标记，由调用方写入
 *   }
 *
 * JSON-RPC 层错误（参数错误、未知工具、工具禁用、桥接失败）在 error.data.errorCode
 * 中携带同样的错误码，客户端可通过错误码做精确处理。
 */
object ErrorCodes {

    // ===== JSON-RPC / 调用层 =====
    const val UNKNOWN_TOOL = "UNKNOWN_TOOL"                 // 未知工具
    const val TOOL_DISABLED = "TOOL_DISABLED"               // 工具已禁用
    const val INVALID_PARAMS = "INVALID_PARAMS"             // 参数校验失败
    const val BRIDGE_UNREACHABLE = "BRIDGE_UNREACHABLE"     // 桥接远端不可达

    // ===== 文件操作 =====
    const val FILE_NOT_FOUND = "FILE_NOT_FOUND"             // 文件/目录不存在
    const val PATH_OUTSIDE_WORKSPACE = "PATH_OUTSIDE_WORKSPACE" // 路径超出工作区
    const val PERMISSION_DENIED = "PERMISSION_DENIED"       // 缺少权限（公共目录/Shizuku/通知等）
    const val UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"     // 格式不支持/无法解码
    const val IO_ERROR = "IO_ERROR"                         // 读写/执行失败
    const val ALREADY_EXISTS = "ALREADY_EXISTS"             // 已存在同名文件/目录

    // ===== 网络 =====
    const val NETWORK_ERROR = "NETWORK_ERROR"               // 网络请求失败
    const val HTTP_ERROR = "HTTP_ERROR"                     // HTTP 状态码 >= 400

    // ===== 其他 =====
    const val INVALID_VALUE = "INVALID_VALUE"               // 取值非法（枚举/数值越界等）
    const val TIMEOUT = "TIMEOUT"                           // 执行超时
    const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"           // 暂不支持
    const val INTERNAL = "INTERNAL"                         // 内部错误
}

/** 工具错误结果构建器 */
object Err {
    /** 构造错误结果：{errorCode, error, hint?}，调用方负责加 isError */
    fun of(code: String, message: String, hint: String? = null): JSONObject {
        val o = JSONObject().put("errorCode", code).put("error", message)
        if (!hint.isNullOrEmpty()) o.put("hint", hint)
        return o
    }

    /** 无错误码的普通错误（保留旧结构） */
    fun plain(message: String): JSONObject =
        JSONObject().put("error", message)

    /** 从错误结果中提取错误码，无则返回 null */
    fun codeOf(result: JSONObject): String? =
        if (result.has("errorCode")) result.optString("errorCode") else null
}
