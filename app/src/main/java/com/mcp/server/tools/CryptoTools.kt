package com.mcp.server.tools

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/** 加解密 / 编码类工具 */
object CryptoTools {

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(decryptXor(ctx).inCategory("实用工具"))
        sink.add(encodeBase64().inCategory("实用工具"))
        // write_base64 已删除：功能并入 write_file（base64=true）
    }

    private fun require(args: JSONObject, name: String): String {
        val v = args.optString(name, "").trim()
        if (v.isEmpty()) throw IllegalArgumentException("缺少必要参数: $name")
        return v
    }

    private fun decryptXor(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "decrypt_xor", "XOR 异或解密/加密工具：对 XOR 加密的数据进行解密，支持单字节和多字节密钥。data 为待处理字符串；key 为密钥（多字节时逐字节轮换）；encoding 可选 hex/base64/utf8（默认 utf8）；输出 base64=true 时返回 base64 结果",
            paramSchema(listOf(
                param("data", "string", required = true, example = "dGVzdA==", description = "待处理的字符串"),
                param("key", "string", required = true, example = "secret", description = "XOR 密钥（多字节时逐字节轮换）"),
                param("encoding", "string", example = "utf8", allowedValues = listOf("utf8", "hex", "base64"), description = "data 的输入编码"),
                param("base64", "boolean", example = false, description = "true 时结果按 base64 输出"),
            ), required = listOf("data", "key"))
        ) { args ->
            val data = require(args, "data")
            val key = args.optString("key", "")
            if (key.isEmpty()) return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "key 不能为空",
                "key 为必填参数")
            val encoding = args.optString("encoding", "utf8").lowercase()
            if (encoding !in setOf("utf8", "hex", "base64")) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "未知 encoding: $encoding",
                    "allowedValues: utf8 / hex / base64，例如: \"utf8\"")
            }
            val wantBase64 = args.optBoolean("base64", false)
            val bytes = when (encoding) {
                "hex" -> try { hexToBytes(data) } catch (e: Exception) {
                    return@ToolDefinition Err.of(ErrorCodes.UNSUPPORTED_FORMAT, "hex 解码失败: ${e.message}",
                        "data 必须是合法的 hex 字符串（偶数长度）")
                }
                "base64" -> try { Base64.decode(data, Base64.DEFAULT) } catch (e: Exception) {
                    return@ToolDefinition Err.of(ErrorCodes.UNSUPPORTED_FORMAT, "base64 解码失败: ${e.message}",
                        "data 必须是合法的 base64 字符串")
                }
                else -> data.toByteArray(Charsets.UTF_8)
            }
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val out = ByteArray(bytes.size)
            for (i in bytes.indices) {
                out[i] = (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            JSONObject().apply {
                put("ok", true)
                put("method", "xor")
                put("keyLength", keyBytes.size)
                put("inputEncoding", encoding)
                put("result", if (wantBase64) Base64.encodeToString(out, Base64.NO_WRAP) else String(out, Charsets.UTF_8))
                put("resultHex", bytesToHex(out))
            }
        }
    }

    private fun encodeBase64(): ToolDefinition {
        return ToolDefinition(
            "base64_encode", "将文本编码为 Base64（也可用 decode=true 解码）",
            paramSchema(listOf(
                param("data", "string", required = true, example = "Hello World", description = "待编码的文本"),
                param("decode", "boolean", example = false, description = "true 时进行 base64 解码"),
            ), required = listOf("data"))
        ) { args ->
            val data = args.optString("data", "")
            if (data.isEmpty()) return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "data 不能为空",
                "data 为必填参数")
            if (args.optBoolean("decode", false)) {
                val bytes = try { Base64.decode(data, Base64.DEFAULT) } catch (e: Exception) {
                    return@ToolDefinition Err.of(ErrorCodes.UNSUPPORTED_FORMAT, "Base64 解码失败: ${e.message}",
                        "data 必须是合法的 base64 字符串")
                }
                JSONObject().put("ok", true).put("operation", "decode").put("result", String(bytes, Charsets.UTF_8))
            } else {
                JSONObject().put("ok", true).put("operation", "encode")
                    .put("result", Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "").replace("0x", "").replace(",", "")
        require(clean.length % 2 == 0) { "hex 长度必须为偶数" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
