package com.mcp.server.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.mcp.server.server.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件操作类工具。
 * 注意：Android 10+ 上访问公共目录需要"所有文件访问"权限（MANAGE_EXTERNAL_STORAGE）。
 *
 * 工具合并说明（2026-08）：重复工具已删除，功能统一并入以下主工具：
 * - 读取：read_file（offset/length 切片、start/end 行范围）
 * - 写入：write_file（base64、mode=append/replace/insert）
 * - 列出：list_files（recursive/maxDepth/extension/includeHidden）
 * - 删除：delete_file；搜索：file_search；目录：create_directory
 * - 创建：touch（truncate=true 清空）；工作区：get_workspace
 */
object FileTools {

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(getWorkspace(ctx).inCategory("文件操作"))
        sink.add(listFiles(ctx).inCategory("文件操作"))
        sink.add(readFile(ctx).inCategory("文件操作"))
        sink.add(writeFile(ctx).inCategory("文件操作"))
        sink.add(createDirectory(ctx).inCategory("文件操作"))
        sink.add(copyFile(ctx).inCategory("文件操作"))
        sink.add(moveFile(ctx).inCategory("文件操作"))
        sink.add(deleteFile(ctx).inCategory("文件操作"))
        sink.add(fileInfo(ctx).inCategory("文件操作"))
        sink.add(compressZip(ctx).inCategory("文件操作"))
        sink.add(extractZip(ctx).inCategory("文件操作"))
        sink.add(fileSearch(ctx).inCategory("文件操作"))
        sink.add(editTool(ctx).inCategory("文件操作"))
        sink.add(touch(ctx, truncateExisting = false).inCategory("文件操作"))
        sink.add(compareFiles(ctx).inCategory("文件操作"))
        sink.add(webDownload(ctx).inCategory("文件操作"))
        // 重复工具已全部删除并合并进对应主工具（不再注册任何别名）：
        // pwd -> get_workspace；ls/list_all/tree/file_list -> list_files；
        // read/read_lines/file_read -> read_file；write/file_write/write_base64/append_file -> write_file；
        // mkdir -> create_directory；file_delete -> delete_file；find -> file_search；
        // batch_ops -> batch（元信息）；empty -> touch（truncate=true）。
    }

    private fun schema(props: List<Triple<String, String, Boolean>>): JSONObject {
        val s = JSONObject()
        val p = JSONObject()
        for ((k, t, _) in props) p.put(k, JSONObject().put("type", t))
        s.put("type", "object")
        s.put("properties", p)
        val required = props.filter { it.third }.map { it.first }
        if (required.isNotEmpty()) s.put("required", JSONArray(required))
        return s
    }

    private fun require(args: JSONObject, name: String): String {
        val v = args.optString(name, "").trim()
        if (v.isEmpty()) throw IllegalArgumentException("缺少必要参数: $name")
        return v
    }

    private fun getWorkspace(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "get_workspace", "查看当前工作区（客户端只能操作工作区内的文件）。返回工作区路径、显示名、是否 SAF 模式",
            schema(emptyList())
        ) {
            val root = Workspace.root(ctx)
            JSONObject().put("workspace", Workspace.path(ctx))
                .put("path", Workspace.path(ctx))
                .put("display", Workspace.display(ctx))
                .put("saf", Workspace.isSaf(ctx))
                .put("exists", root.exists())
        }
    }

    private fun resolve(ctx: Context, path: String): File {
        val f = File(path)
        if (f.isAbsolute) return f
        return File(Environment.getExternalStorageDirectory(), path)
    }

    /** 校验文件位于工作区内，不通过则返回带错误码的错误 JSON */
    private fun checkWorkspace(ctx: Context, f: File): JSONObject? =
        Workspace.rejectReason(ctx, f)?.let {
            Err.of(ErrorCodes.PATH_OUTSIDE_WORKSPACE, it,
                "请使用以工作区为根的绝对路径，可用 get_workspace 查看当前工作区")
        }

    private fun isAllFilesAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun readableError(ctx: Context): JSONObject =
        if (!isAllFilesAllowed(ctx))
            Err.of(ErrorCodes.PERMISSION_DENIED,
                "公共目录读写需要'所有文件访问'权限。请在设置中授予，或在应用界面中点击授权。",
                "在 App 设置中授予'所有文件访问'权限，或使用系统文件夹选择器选择工作区")
        else Err.of(ErrorCodes.PERMISSION_DENIED, "路径不存在或无访问权限",
            "检查路径是否存在于当前工作区内")

    private fun fmtSize(size: Long): String {
        if (size < 1024) return "$size B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = size.toDouble()
        var u = -1
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return String.format(Locale.US, "%.1f %s", v, units[u])
    }

    private fun entryJson(f: File): JSONObject = JSONObject().apply {
        put("name", f.name)
        put("path", f.absolutePath)
        put("type", if (f.isDirectory) "directory" else "file")
        put("size", f.length())
        put("sizeHuman", fmtSize(f.length()))
        put("lastModified", f.lastModified())
        put("lastModifiedHuman",
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified())))
        put("isHidden", f.isHidden)
        put("isReadable", f.canRead())
        put("isWritable", f.canWrite())
    }

    private fun docEntryJson(d: DocumentFile): JSONObject = JSONObject().apply {
        put("name", d.name ?: "")
        put("type", if (d.isDirectory) "directory" else "file")
        put("size", d.length())
        put("sizeHuman", fmtSize(d.length()))
        put("lastModified", d.lastModified())
        put("lastModifiedHuman",
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(d.lastModified())))
        put("uri", d.uri.toString())
        put("isReadable", d.canRead())
        put("isWritable", d.canWrite())
    }

    private fun listFiles(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "list_files", "列出目录内容（支持递归深度与扩展名过滤，可包含隐藏文件）",
            paramSchema(listOf(
                param("path", "string", example = ".", description = "目录路径（绝对路径，默认当前工作区）"),
                param("recursive", "boolean", example = false, description = "是否递归子目录"),
                param("maxDepth", "integer", example = 3, description = "递归最大深度（recursive=true 时生效）"),
                param("extension", "string", example = "txt", description = "按扩展名过滤"),
                param("includeHidden", "boolean", example = false, description = "是否包含隐藏文件"),
            ))
        ) { args ->
            val raw = args.optString("path", ".")
            val root = resolve(ctx, raw)
            checkWorkspace(ctx, root)?.let { return@ToolDefinition it }
            val recursive = args.optBoolean("recursive", false)
            val maxDepth = args.optInt("maxDepth", 3)
            val extRaw = args.optString("extension", "").trim()
            val ext = if (extRaw.isEmpty()) "" else if (extRaw.startsWith(".")) extRaw.lowercase() else ".${extRaw.lowercase()}"
            val includeHidden = args.optBoolean("includeHidden", false)
            val arr = JSONArray()

            // SAF 工作区：通过 DocumentFile 列出
            if (Workspace.isSaf(ctx)) {
                val doc = Workspace.resolveDoc(ctx, root)
                if (doc == null || !doc.exists()) {
                    return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "$root 不是有效目录",
                        "请传入存在的目录绝对路径")
                }
                fun walkDoc(d: DocumentFile, depth: Int) {
                    if (depth > maxDepth) return
                    for (c in Saf.list(d)) {
                        if (c.name.orEmpty().startsWith(".") && !includeHidden) continue
                        if (c.isFile && ext.isNotEmpty() && !c.name.orEmpty().lowercase().endsWith(ext)) continue
                        arr.put(docEntryJson(c))
                        if (c.isDirectory && recursive) walkDoc(c, depth + 1)
                    }
                }
                walkDoc(doc, 0)
                return@ToolDefinition JSONObject().put("path", root.absolutePath)
                    .put("count", arr.length())
                    .put("entries", arr)
            }

            if (!root.exists() || !root.isDirectory) {
                return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "$root 不是有效目录",
                    "请传入存在的目录绝对路径")
            }
            fun walk(dir: File, depth: Int) {
                if (depth > maxDepth) return
                val children = dir.listFiles() ?: return
                for (c in children.sortedBy { it.name.lowercase() }) {
                    if (c.isHidden && !includeHidden) continue
                    if (ext.isNotEmpty() && c.isFile && !c.name.lowercase().endsWith(ext)) continue
                    arr.put(entryJson(c))
                    if (c.isDirectory && recursive) walk(c, depth + 1)
                }
            }
            walk(root, 0)
            JSONObject().put("path", root.absolutePath)
                .put("count", arr.length())
                .put("entries", arr)
        }
    }

    private fun readFile(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "read_file", "读取文本文件内容（可指定编码与起始位置/长度）",
            paramSchema(listOf(
                param("path", "string", required = true, example = "/sdcard/Documents/note.txt", description = "文件绝对路径"),
                param("encoding", "string", example = "UTF-8", description = "文件编码，默认 UTF-8"),
                param("offset", "integer", example = 0, description = "起始字节偏移"),
                param("length", "integer", example = 4096, description = "读取字节数（默认读到末尾）"),
                param("start", "integer", example = 1, description = "起始行号（从 1 计数，兼容 read_lines）"),
                param("end", "integer", example = 50, description = "结束行号（含，兼容 read_lines）"),
                param("showLineNumbers", "boolean", example = true, description = "是否显示行号（兼容 file_read）"),
            ), required = listOf("path"))
        ) { args ->
            val f = resolve(ctx, require(args, "path"))
            checkWorkspace(ctx, f)?.let { return@ToolDefinition it }
            // SAF 工作区
            if (Workspace.isSaf(ctx)) {
                val doc = Workspace.resolveDoc(ctx, f)
                if (doc == null || !doc.exists()) {
                    return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "文件不存在: $f",
                        "请检查路径是否存在于当前工作区内")
                }
                val bytes = Saf.readBytes(ctx, doc) ?: return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                    "读取失败: 没有访问权限", "请重新选择工作区文件夹以获得访问权限")
                return@ToolDefinition buildReadResult(ctx, f, bytes, args)
            }
            if (!f.exists() || !f.isFile) {
                return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "文件不存在: $f",
                    "请检查路径是否存在于当前工作区内")
            }
            if (!isAllFilesAllowed(ctx) && !f.absolutePath.startsWith(ctx.filesDir.absolutePath)
                && !f.absolutePath.startsWith(ctx.cacheDir.absolutePath)
                && !f.absolutePath.startsWith(ctx.getExternalFilesDir(null)?.absolutePath ?: "\u0000")
            ) {
                return@ToolDefinition readableError(ctx)
            }
            return@ToolDefinition try {
                val bytes = f.readBytes()
                buildReadResult(ctx, f, bytes, args)
            } catch (e: Exception) {
                Err.of(ErrorCodes.IO_ERROR, "读取失败: ${e.message}", "检查文件是否存在且可读")
            }
        }
    }

    /** 读取结果组装：offset/length 切片（read_file 风格），行范围参数优先时按行返回（read_lines/file_read 风格） */
    private fun buildReadResult(ctx: Context, f: File, bytes: ByteArray, args: JSONObject): JSONObject {
        val hasLineRange = args.has("start") || args.has("end")
        if (hasLineRange) {
            val start = args.optInt("start", 1).coerceAtLeast(1)
            val end = if (args.has("end")) args.optInt("end").coerceAtLeast(start) else Int.MAX_VALUE
            val showLine = args.optBoolean("showLineNumbers", true)
            val text = String(bytes, java.nio.charset.Charset.forName(args.optString("encoding", "UTF-8")))
            val lines = text.split("\n", "\r\n")
            val slice = lines.subList((start - 1).coerceAtMost(lines.size), minOf(end - 1, lines.size))
            val content = StringBuilder()
            for ((i, l) in slice.withIndex()) {
                val lineNo = start + i
                if (showLine) content.append("$lineNo: ").append(l).append("\n") else content.append(l).append("\n")
            }
            return JSONObject().put("path", f.absolutePath)
                .put("start", start)
                .put("end", start + slice.size - 1)
                .put("totalLines", lines.size)
                .put("count", slice.size)
                .put("lines", JSONArray(slice))
                .put("content", content.toString())
        }
        val encoding = args.optString("encoding", "UTF-8")
        return try {
            val offset = args.optInt("offset", 0).coerceIn(0, bytes.size)
            val length = if (args.has("length")) args.optInt("length").coerceAtLeast(0) else bytes.size - offset
            val slice = bytes.copyOfRange(offset, (offset + length).coerceAtMost(bytes.size))
            JSONObject().apply {
                put("path", f.absolutePath)
                put("size", bytes.size)
                put("offset", offset)
                put("read", slice.size)
                put("truncated", offset + slice.size < bytes.size)
                put("content", String(slice, java.nio.charset.Charset.forName(encoding)))
            }
        } catch (e: Exception) {
            Err.of(ErrorCodes.UNSUPPORTED_FORMAT, "解码失败: ${e.message}",
                "请检查 encoding 参数是否正确（如 UTF-8 / GBK）")
        }
    }

    private fun writeFile(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "write_file", "写入文本内容到文件（覆盖）。content 也可以是 base64 编码的二进制数据（配合 base64=true）",
            paramSchema(listOf(
                param("path", "string", required = true, example = "/sdcard/Documents/note.txt", description = "目标文件绝对路径"),
                param("content", "string", required = true, example = "文件内容", description = "写入的内容（base64=true 时为 base64 编码的二进制）"),
                param("encoding", "string", example = "UTF-8", description = "文本编码，默认 UTF-8"),
                param("base64", "boolean", example = false, description = "true 时 content 按 base64 解码为二进制写入"),
                param("mode", "string", example = "append", allowedValues = listOf("append", "replace", "insert"), description = "写入模式（兼容 file_write；append=追加到末尾）"),
                param("line", "integer", example = 1, description = "行号（mode=replace/insert 时使用，从 1 开始）"),
                param("newContent", "string", example = "新内容", description = "replace/insert 模式使用的新内容（缺省用 content）"),
            ), required = listOf("path", "content"))
        ) { args ->
            val f = resolve(ctx, require(args, "path"))
            val content = require(args, "content")
            checkWorkspace(ctx, f)?.let { return@ToolDefinition it }
            val mode = args.optString("mode", "").lowercase()
            // file_write 模式：行级替换/插入/追加
            if (mode.isNotEmpty()) {
                if (mode !in setOf("replace", "insert", "append")) {
                    return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "未知 mode: $mode",
                        "allowedValues: replace / insert / append，例如: \"append\"")
                }
                return@ToolDefinition fileWriteMode(ctx, f, args, mode, content)
            }
            val bytes = if (args.optBoolean("base64", false)) {
                try {
                    android.util.Base64.decode(content, android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    return@ToolDefinition Err.of(ErrorCodes.UNSUPPORTED_FORMAT, "Base64 解码失败: ${e.message}",
                        "content 必须是合法的 base64 字符串")
                }
            } else {
                content.toByteArray(java.nio.charset.Charset.forName(args.optString("encoding", "UTF-8")))
            }
            return@ToolDefinition try {
                // SAF 工作区：通过 DocumentFile 写入（无需"所有文件访问"权限）
                if (Workspace.isSaf(ctx)) {
                    val rel = Workspace.relativePath(ctx, f) ?: return@ToolDefinition Err.of(ErrorCodes.PATH_OUTSIDE_WORKSPACE,
                        "路径在工作区之外", "请使用以工作区为根的绝对路径")
                    var doc = Workspace.resolveDoc(ctx, f)
                    if (doc == null || !doc.exists()) {
                        // 自动创建父目录与文件
                        val base = Workspace.rootDoc(ctx) ?: return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                            "工作区不可用", "请重新选择工作区文件夹")
                        val segs = rel.split('/').filter { it.isNotEmpty() }
                        var cur = base
                        for (i in segs.indices) {
                            val seg = segs[i]
                            val child = cur.findFile(seg)
                            if (child == null) {
                                if (i == segs.lastIndex) {
                                    cur = Saf.createFile(cur, seg) ?: run { return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "创建文件失败: $seg") }
                                } else {
                                    cur = Saf.createDir(cur, seg) ?: run { return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "创建目录失败: $seg") }
                                }
                            } else {
                                cur = child
                            }
                        }
                        doc = cur
                    }
                    if (!Saf.writeBytes(ctx, doc, bytes)) {
                        return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                            "写入失败: 没有该目录的访问权限，请重新选择工作区文件夹",
                            "在 App 设置中重新选择工作区文件夹")
                    }
                    return@ToolDefinition JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("ok", true)
                }
                // 普通模式
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("ok", true)
            } catch (e: Exception) {
                Err.of(ErrorCodes.IO_ERROR, "写入失败: ${e.message}", "检查路径与存储空间是否可用")
            }
        }
    }

    /** file_write 兼容模式：按行 replace/insert/append */
    private fun fileWriteMode(ctx: Context, f: File, args: JSONObject, mode: String, content: String): JSONObject {
        val line = args.optInt("line", 1).coerceAtLeast(1)
        val newContent = if (args.has("newContent")) args.optString("newContent", "") else content
        val readAll: () -> String = {
            if (Workspace.isSaf(ctx)) {
                val doc = Workspace.resolveDoc(ctx, f)
                    ?: throw IllegalStateException("文件不存在: $f")
                val bytes = Saf.readBytes(ctx, doc) ?: throw IllegalStateException("读取失败")
                String(bytes, Charsets.UTF_8)
            } else {
                if (!f.exists()) throw IllegalStateException("文件不存在: $f")
                f.readText(Charsets.UTF_8)
            }
        }
        val writeAll: (String) -> Unit = { text ->
            if (Workspace.isSaf(ctx)) {
                var doc = Workspace.resolveDoc(ctx, f)
                if (doc == null || !doc.exists()) {
                    safEnsureFile(ctx, f)?.let { throw IllegalStateException(it.optString("error", "无法创建文件")) }
                    doc = Workspace.resolveDoc(ctx, f)
                }
                val ok = Saf.writeBytes(ctx, doc!!, text.toByteArray(Charsets.UTF_8))
                if (!ok) throw IllegalStateException("写入失败: 没有访问权限")
            } else {
                f.parentFile?.mkdirs()
                f.writeText(text, Charsets.UTF_8)
            }
        }
        return try {
            val oldText = readAll()
            val oldLines = oldText.split("\n", "\r\n")
            val newText = when (mode) {
                "replace" -> {
                    if (line > oldLines.size) throw IllegalStateException("行号越界: $line > ${oldLines.size}")
                    val list = oldLines.toMutableList()
                    list[line - 1] = newContent
                    list.joinToString("\n")
                }
                "insert" -> {
                    val list = oldLines.toMutableList()
                    val idx = (line - 1).coerceAtMost(list.size)
                    list.add(idx, newContent)
                    list.joinToString("\n")
                }
                else -> {
                    if (oldText.endsWith("\n") || oldText.isEmpty()) oldText + newContent else oldText + "\n" + newContent
                }
            }
            writeAll(newText)
            val newLines = newText.split("\n", "\r\n")
            JSONObject().apply {
                put("path", f.absolutePath)
                put("mode", mode)
                put("ok", true)
                put("oldLines", oldLines.size)
                put("newLines", newLines.size)
                put("added", (newLines.size - oldLines.size).coerceAtLeast(0))
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("文件不存在")) {
                Err.of(ErrorCodes.FILE_NOT_FOUND, "写入失败: $msg", "请检查路径是否存在于当前工作区内")
            } else {
                Err.of(ErrorCodes.IO_ERROR, "写入失败: $msg", "检查路径与访问权限")
            }
        }
    }

    /** 替换文件中指定文本内容（支持简单的字符串替换；occurrence 从 1 开始，-1 表示全部替换） */
    private fun editTool(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "edit", "替换文件中指定文本内容（支持简单的字符串替换；occurrence 从 1 开始，-1 表示全部替换）",
            paramSchema(listOf(
                param("path", "string", required = true, example = "/sdcard/Documents/note.txt", description = "文件绝对路径"),
                param("oldText", "string", required = true, example = "旧文本", description = "要被替换的文本"),
                param("newText", "string", required = true, example = "新文本", description = "替换后的文本"),
                param("occurrence", "integer", example = 1, description = "替换第几次出现（-1 全部替换）"),
            ), required = listOf("path", "oldText", "newText"))
        ) { args ->
            val f = resolve(ctx, require(args, "path"))
            checkPath(ctx, f, "编辑")?.let { return@ToolDefinition it }
            val oldText = require(args, "oldText")
            val newText = require(args, "newText")
            val occurrence = args.optInt("occurrence", 1)
            val oldBytes = oldText.toByteArray(Charsets.UTF_8)
            val newBytes = newText.toByteArray(Charsets.UTF_8)
            // SAF 工作区
            if (Workspace.isSaf(ctx)) {
                val doc = Workspace.resolveDoc(ctx, f)
                if (doc == null || !doc.exists()) return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "文件不存在: $f",
                    "请检查路径")
                val bytes = Saf.readBytes(ctx, doc) ?: return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                    "读取失败: 没有访问权限", "请重新选择工作区文件夹")
                val replaced = replaceBytes(bytes, oldBytes, newBytes, occurrence)
                    ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "未找到匹配文本（第 $occurrence 次出现）",
                        "请检查 oldText 是否正确存在于文件中")
                if (!Saf.writeBytes(ctx, doc, replaced)) {
                    return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED, "写入失败: 没有访问权限",
                        "请重新选择工作区文件夹")
                }
                return@ToolDefinition JSONObject().put("path", f.absolutePath).put("replaced", true).put("ok", true)
            }
            if (!f.exists() || !f.isFile) return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "文件不存在: $f",
                "请检查路径")
            return@ToolDefinition try {
                val bytes = f.readBytes()
                val replaced = replaceBytes(bytes, oldBytes, newBytes, occurrence)
                    ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "未找到匹配文本（第 $occurrence 次出现）",
                        "请检查 oldText 是否正确存在于文件中")
                f.writeBytes(replaced)
                JSONObject().put("path", f.absolutePath).put("replaced", true).put("ok", true)
            } catch (e: Exception) {
                Err.of(ErrorCodes.IO_ERROR, "编辑失败: ${e.message}", "检查路径与访问权限")
            }
        }
    }

    /** 字节级替换（UTF-8 安全）。occurrence=-1 全部替换；否则替换第 occurrence 次 */
    private fun replaceBytes(data: ByteArray, old: ByteArray, new: ByteArray, occurrence: Int): ByteArray? {
        if (old.isEmpty()) return data
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        var found = 0
        while (i <= data.size - old.size) {
            var match = true
            for (j in old.indices) {
                if (data[i + j] != old[j]) { match = false; break }
            }
            if (match) {
                found++
                if (occurrence == -1 || found == occurrence) {
                    out.write(new)
                    i += old.size
                    if (occurrence != -1) {
                        out.write(data, i, data.size - i)
                        return out.toByteArray()
                    }
                    continue
                }
            }
            out.write(data[i].toInt())
            i++
        }
        if (found == 0) return null
        return out.toByteArray()
    }

    private fun createDirectory(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "create_directory", "创建目录（自动创建父目录）",
            paramSchema(listOf(
                param("path", "string", required = true, example = "/sdcard/Documents/new_dir", description = "目录绝对路径"),
            ), required = listOf("path"))
        ) { args ->
            val f = resolve(ctx, require(args, "path"))
            checkWorkspace(ctx, f)?.let { return@ToolDefinition it }
            // SAF 工作区
            if (Workspace.isSaf(ctx)) {
                val doc = Workspace.resolveDoc(ctx, f)
                if (doc != null && doc.exists()) {
                    if (!doc.isDirectory) return@ToolDefinition Err.of(ErrorCodes.ALREADY_EXISTS, "已存在同名文件: $f",
                        "请换一个目录名")
                    return@ToolDefinition JSONObject().put("path", f.absolutePath).put("ok", true)
                }
                val rel = Workspace.relativePath(ctx, f) ?: return@ToolDefinition Err.of(ErrorCodes.PATH_OUTSIDE_WORKSPACE,
                    "路径在工作区之外", "请使用以工作区为根的绝对路径")
                val base = Workspace.rootDoc(ctx) ?: return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                    "工作区不可用", "请重新选择工作区文件夹")
                val segs = rel.split('/').filter { it.isNotEmpty() }
                var cur = base
                for (seg in segs) {
                    val child = cur.findFile(seg)
                    if (child == null) cur = Saf.createDir(cur, seg) ?: return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "创建目录失败: $seg")
                    else if (child.isDirectory) cur = child
                    else return@ToolDefinition Err.of(ErrorCodes.ALREADY_EXISTS, "已存在同名文件: $seg", "请换一个目录名")
                }
                return@ToolDefinition JSONObject().put("path", f.absolutePath).put("ok", true)
            }
            if (f.exists()) {
                if (f.isDirectory) return@ToolDefinition JSONObject().put("path", f.absolutePath).put("exists", true).put("ok", true)
                return@ToolDefinition Err.of(ErrorCodes.ALREADY_EXISTS, "已存在同名文件: $f", "请换一个目录名")
            }
            val ok = try { f.mkdirs() } catch (e: Exception) { false }
            JSONObject().put("path", f.absolutePath).put("ok", ok || f.isDirectory)
                .put("error", if (ok || f.isDirectory) null else "创建失败")
        }
    }

    private fun copyFile(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "copy_file", "复制文件或目录",
            paramSchema(listOf(
                param("source", "string", required = true, example = "/sdcard/Documents/a.txt", description = "源路径"),
                param("destination", "string", required = true, example = "/sdcard/Documents/b.txt", description = "目标路径"),
                param("recursive", "boolean", example = true, description = "复制目录时是否递归"),
            ), required = listOf("source", "destination"))
        ) { args ->
            val src = resolve(ctx, require(args, "source"))
            val dst = resolve(ctx, require(args, "destination"))
            checkWorkspace(ctx, src)?.let { return@ToolDefinition it }
            checkWorkspace(ctx, dst)?.let { return@ToolDefinition it }
            return@ToolDefinition try {
                // SAF 工作区：通过 DocumentFile 复制
                if (Workspace.isSaf(ctx)) {
                    val srcDoc = Workspace.resolveDoc(ctx, src)
                        ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "源不存在: $src", "请检查源路径")
                    val dstDoc = Workspace.resolveDoc(ctx, dst)
                    val target = if (dstDoc != null && dstDoc.exists()) dstDoc else {
                        val rel = Workspace.relativePath(ctx, dst) ?: return@ToolDefinition Err.of(ErrorCodes.PATH_OUTSIDE_WORKSPACE,
                            "路径在工作区之外", "请使用以工作区为根的绝对路径")
                        val base = Workspace.rootDoc(ctx) ?: return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                            "工作区不可用", "请重新选择工作区文件夹")
                        val segs = rel.split('/').filter { it.isNotEmpty() }
                        var cur = base
                        for (i in segs.indices) {
                            val child = cur.findFile(segs[i])
                            if (child == null) {
                                if (i == segs.lastIndex) cur = Saf.createFile(cur, segs[i]) ?: return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "创建失败: ${segs[i]}")
                                else cur = Saf.createDir(cur, segs[i]) ?: return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "创建目录失败: ${segs[i]}")
                            } else cur = child
                        }
                        cur
                    }
                    val ok = Saf.copy(ctx, srcDoc, target)
                    return@ToolDefinition JSONObject().put("source", src.absolutePath)
                        .put("destination", dst.absolutePath)
                        .put("ok", ok)
                        .put("error", if (ok) null else "复制失败: 没有访问权限")
                }
                val copied: Long
                if (src.isDirectory) {
                    if (!args.optBoolean("recursive", true)) {
                        return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "复制目录需要 recursive=true",
                            "例如: recursive = true")
                    }
                    src.copyRecursively(dst, overwrite = true) { _, _ -> kotlin.io.OnErrorAction.SKIP }
                    copied = dst.length()
                } else {
                    src.copyTo(dst, overwrite = true)
                    copied = src.length()
                }
                JSONObject().put("source", src.absolutePath)
                    .put("destination", dst.absolutePath)
                    .put("bytes", copied)
                    .put("ok", true)
            } catch (e: Exception) {
                Err.of(ErrorCodes.IO_ERROR, "复制失败: ${e.message}", "检查源/目标路径与访问权限")
            }
        }
    }

    private fun moveFile(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "move_file", "移动/重命名文件或目录",
            paramSchema(listOf(
                param("source", "string", required = true, example = "/sdcard/Documents/a.txt", description = "源路径"),
                param("destination", "string", required = true, example = "/sdcard/Documents/b.txt", description = "目标路径"),
            ), required = listOf("source", "destination"))
        ) { args ->
            val src = resolve(ctx, require(args, "source"))
            val dst = resolve(ctx, require(args, "destination"))
            checkWorkspace(ctx, src)?.let { return@ToolDefinition it }
            checkWorkspace(ctx, dst)?.let { return@ToolDefinition it }
            // SAF 工作区：不支持 renameTo，通过复制+删除实现
            if (Workspace.isSaf(ctx)) {
                val srcDoc = Workspace.resolveDoc(ctx, src)
                    ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "源不存在: $src", "请检查源路径")
                val dstDoc = Workspace.resolveDoc(ctx, dst)
                val target = if (dstDoc != null && dstDoc.exists()) dstDoc else {
                    val rel = Workspace.relativePath(ctx, dst) ?: return@ToolDefinition Err.of(ErrorCodes.PATH_OUTSIDE_WORKSPACE,
                        "路径在工作区之外", "请使用以工作区为根的绝对路径")
                    val base = Workspace.rootDoc(ctx) ?: return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                        "工作区不可用", "请重新选择工作区文件夹")
                    val segs = rel.split('/').filter { it.isNotEmpty() }
                    var cur = base
                    for (i in segs.indices) {
                        val child = cur.findFile(segs[i])
                        if (child == null) {
                            if (i == segs.lastIndex) cur = Saf.createFile(cur, segs[i]) ?: return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "创建失败: ${segs[i]}")
                            else cur = Saf.createDir(cur, segs[i]) ?: return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "创建目录失败: ${segs[i]}")
                        } else cur = child
                    }
                    cur
                }
                val ok = Saf.copy(ctx, srcDoc, target)
                if (ok) srcDoc.delete()
                return@ToolDefinition JSONObject().put("source", src.absolutePath)
                    .put("destination", dst.absolutePath)
                    .put("ok", ok)
                    .put("error", if (ok) null else "移动失败: 没有访问权限")
            }
            return@ToolDefinition try {
                dst.parentFile?.mkdirs()
                val ok = src.renameTo(dst)
                JSONObject().put("source", src.absolutePath)
                    .put("destination", dst.absolutePath)
                    .put("ok", ok)
                    .put("error", if (ok) null else "移动失败")
            } catch (e: Exception) {
                Err.of(ErrorCodes.IO_ERROR, "移动失败: ${e.message}", "检查源/目标路径与访问权限")
            }
        }
    }

    private fun deleteFile(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "delete_file", "删除文件或目录（目录需 recursive=true）。操作不可逆，请谨慎使用",
            paramSchema(listOf(
                param("path", "string", required = true, example = "/sdcard/Documents/old.txt", description = "要删除的文件/目录路径"),
                param("recursive", "boolean", example = true, description = "删除目录时是否递归删除"),
            ), required = listOf("path"))
        ) { args ->
            val f = resolve(ctx, require(args, "path"))
            checkWorkspace(ctx, f)?.let { return@ToolDefinition it }
            // SAF 工作区
            if (Workspace.isSaf(ctx)) {
                val doc = Workspace.resolveDoc(ctx, f)
                    ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "不存在: $f", "请检查路径")
                if (!doc.exists()) return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "不存在: $f", "请检查路径")
                val ok = if (doc.isDirectory && args.optBoolean("recursive", false)) {
                    // DocumentFile 没有递归删除，逐层删除（先子后父）
                    fun deleteRecursive(d: DocumentFile): Boolean {
                        var all = true
                        for (c in d.listFiles()) {
                            if (c.isDirectory) all = deleteRecursive(c) && all
                            all = c.delete() && all
                        }
                        return d.delete() && all
                    }
                    deleteRecursive(doc)
                } else {
                    doc.delete()
                }
                // 删除后复核：不存在才算真正成功（DocumentFile.delete 返回值不可靠）
                val gone = !doc.exists()
                return@ToolDefinition JSONObject().put("path", f.absolutePath).put("ok", gone || ok)
                    .put("verified", gone)
                    .put("error", if (gone || ok) null else "删除失败: 没有访问权限")
            }
            return@ToolDefinition try {
                val ok = if (f.isDirectory && args.optBoolean("recursive", false)) {
                    f.deleteRecursively()
                } else {
                    f.delete()
                }
                val gone = !f.exists()
                JSONObject().put("path", f.absolutePath).put("ok", gone || ok)
                    .put("verified", gone)
                    .put("error", if (gone || ok) null else "删除失败")
            } catch (e: Exception) {
                Err.of(ErrorCodes.IO_ERROR, "删除失败: ${e.message}", "检查路径与访问权限")
            }
        }
    }

    private fun fileInfo(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "file_info", "获取文件或目录的详细信息，包括大小、权限、修改时间、MD5/SHA256 哈希值（hash=true 时计算文件哈希）",
            paramSchema(listOf(
                param("path", "string", required = true, example = "/sdcard/Documents/note.txt", description = "文件/目录路径"),
                param("hash", "boolean", example = false, description = "true 时计算文件 MD5/SHA256 哈希"),
            ), required = listOf("path"))
        ) { args ->
            val f = resolve(ctx, require(args, "path"))
            checkWorkspace(ctx, f)?.let { return@ToolDefinition it }
            val wantHash = args.optBoolean("hash", false)
            // SAF 工作区
            if (Workspace.isSaf(ctx)) {
                val doc = Workspace.resolveDoc(ctx, f)
                    ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "不存在: $f", "请检查路径")
                if (!doc.exists()) return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "不存在: $f", "请检查路径")
                return@ToolDefinition docEntryJson(doc).apply {
                    put("absolutePath", f.absolutePath)
                    put("parent", f.parent)
                    if (doc.isDirectory) {
                        put("childCount", doc.listFiles().size)
                    }
                    if (wantHash && doc.isFile) {
                        val bytes = Saf.readBytes(ctx, doc)
                        if (bytes != null) {
                            put("md5", md5(bytes))
                            put("sha256", sha256(bytes))
                        } else {
                            put("hashError", "无法读取文件内容计算哈希")
                        }
                    }
                }
            }
            if (!f.exists()) return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "不存在: $f", "请检查路径")
            entryJson(f).apply {
                put("absolutePath", f.absolutePath)
                put("canonicalPath", try { f.canonicalPath } catch (_: Exception) { f.absolutePath })
                put("parent", f.parent)
                if (f.isDirectory) {
                    val c = f.listFiles()
                    put("childCount", c?.size ?: 0)
                }
                if (wantHash && f.isFile) {
                    val bytes = try { f.readBytes() } catch (_: Exception) { ByteArray(0) }
                    if (bytes.isNotEmpty()) {
                        put("md5", md5(bytes))
                        put("sha256", sha256(bytes))
                    }
                }
            }
        }
    }

    private fun compressZip(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "compress_zip", "将文件或目录压缩为 zip",
            paramSchema(listOf(
                param("source", "string", required = true, example = "/sdcard/Documents/folder", description = "要压缩的文件/目录"),
                param("destination", "string", required = true, example = "/sdcard/Documents/out.zip", description = "目标 zip 文件路径"),
                param("compression", "integer", example = 6, minimum = 0, maximum = 9, description = "压缩级别 0-9"),
            ), required = listOf("source", "destination"))
        ) { args ->
            val src = resolve(ctx, require(args, "source"))
            val dst = resolve(ctx, require(args, "destination"))
            checkWorkspace(ctx, src)?.let { return@ToolDefinition it }
            checkWorkspace(ctx, dst)?.let { return@ToolDefinition it }
            return@ToolDefinition try {
                val level = args.optInt("compression", 6).coerceIn(0, 9)
                // SAF 工作区：流式压缩（目标文件不存在时自动创建）
                if (Workspace.isSaf(ctx)) {
                    var dstDoc = Workspace.resolveDoc(ctx, dst)
                    if (dstDoc == null || !dstDoc.exists()) {
                        val dstRel = Workspace.relativePath(ctx, dst) ?: return@ToolDefinition Err.of(ErrorCodes.PATH_OUTSIDE_WORKSPACE,
                            "目标在工作区之外: $dst", "请使用以工作区为根的绝对路径")
                        val dstBase = Workspace.rootDoc(ctx) ?: return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                            "工作区不可用", "请重新选择工作区文件夹")
                        val segs = dstRel.split('/').filter { it.isNotEmpty() }
                        var cur = dstBase
                        for (i in segs.indices) {
                            val child = cur.findFile(segs[i])
                            if (child == null) {
                                if (i == segs.lastIndex) cur = Saf.createFile(cur, segs[i]) ?: return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "创建目标文件失败: ${segs[i]}")
                                else cur = Saf.createDir(cur, segs[i]) ?: return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "创建目录失败: ${segs[i]}")
                            } else cur = child
                        }
                        dstDoc = cur
                    }
                    val srcDoc = Workspace.resolveDoc(ctx, src)
                        ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "源不存在: $src", "请检查源路径")
                    val out = Saf.openOutputStream(ctx, dstDoc) ?: return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                        "无法写入目标", "请重新选择工作区文件夹")
                    java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out)).use { zos ->
                        zos.setLevel(level)
                        fun addDoc(d: DocumentFile, rel: String) {
                            if (d.isDirectory) {
                                zos.putNextEntry(java.util.zip.ZipEntry(rel + "/"))
                                zos.closeEntry()
                                for (c in d.listFiles()) addDoc(c, "$rel/${c.name}")
                            } else {
                                zos.putNextEntry(java.util.zip.ZipEntry(rel))
                                Saf.openInputStream(ctx, d)?.use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                        addDoc(srcDoc, srcDoc.name ?: "src")
                    }
                    return@ToolDefinition JSONObject().put("source", src.absolutePath)
                        .put("destination", dst.absolutePath)
                        .put("ok", true)
                }
                dst.parentFile?.mkdirs()
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(dst))).use { zos ->
                    zos.setLevel(level)
                    fun add(f: File, rel: String) {
                        if (f.isDirectory) {
                            zos.putNextEntry(java.util.zip.ZipEntry(rel + "/"))
                            zos.closeEntry()
                            f.listFiles()?.forEach { add(it, "$rel/${it.name}") }
                        } else {
                            zos.putNextEntry(java.util.zip.ZipEntry(rel))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    add(src, if (src.isDirectory) src.name else src.name)
                }
                JSONObject().put("source", src.absolutePath)
                    .put("destination", dst.absolutePath)
                    .put("size", dst.length())
                    .put("ok", true)
            } catch (e: Exception) {
                Err.of(ErrorCodes.IO_ERROR, "压缩失败: ${e.message}", "检查源/目标路径与访问权限")
            }
        }
    }

    private fun extractZip(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "extract_zip", "解压 zip 到目标目录",
            paramSchema(listOf(
                param("archive", "string", required = true, example = "/sdcard/Documents/out.zip", description = "zip 文件路径"),
                param("destination", "string", required = true, example = "/sdcard/Documents/unzip", description = "解压目标目录"),
            ), required = listOf("archive", "destination"))
        ) { args ->
            val zip = resolve(ctx, require(args, "archive"))
            val dst = resolve(ctx, require(args, "destination"))
            checkWorkspace(ctx, zip)?.let { return@ToolDefinition it }
            checkWorkspace(ctx, dst)?.let { return@ToolDefinition it }
            return@ToolDefinition try {
                // SAF 工作区：流式解压
                if (Workspace.isSaf(ctx)) {
                    val zipDoc = Workspace.resolveDoc(ctx, zip)
                        ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "压缩包不存在: $zip", "请检查路径")
                    val dstDoc = Workspace.resolveDoc(ctx, dst)
                    val base = if (dstDoc != null && dstDoc.exists() && dstDoc.isDirectory) dstDoc else {
                        val rel = Workspace.relativePath(ctx, dst) ?: return@ToolDefinition Err.of(ErrorCodes.PATH_OUTSIDE_WORKSPACE,
                            "路径在工作区之外", "请使用以工作区为根的绝对路径")
                        val root = Workspace.rootDoc(ctx) ?: return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED,
                            "工作区不可用", "请重新选择工作区文件夹")
                        val segs = rel.split('/').filter { it.isNotEmpty() }
                        var cur = root
                        for (seg in segs) {
                            val child = cur.findFile(seg)
                            cur = if (child != null && child.isDirectory) child
                            else Saf.createDir(cur, seg) ?: return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "创建目录失败: $seg")
                        }
                        cur
                    }
                    var count = 0
                    Saf.openInputStream(ctx, zipDoc)?.use { ins ->
                        java.util.zip.ZipInputStream(java.io.BufferedInputStream(ins)).use { zis ->
                            var e = zis.nextEntry
                            while (e != null) {
                                val name = e.name
                                if (!e.isDirectory) {
                                    val parts = name.split('/').filter { it.isNotEmpty() }
                                    if (parts.isNotEmpty()) {
                                        var cur = base
                                        for (i in parts.indices) {
                                            if (i == parts.lastIndex) {
                                                val f = cur.findFile(parts[i])
                                                val target = if (f != null) f else Saf.createFile(cur, parts[i])
                                                if (target != null) {
                                                    Saf.openOutputStream(ctx, target)?.use { zis.copyTo(it) }
                                                    count++
                                                }
                                            } else {
                                                val d = cur.findFile(parts[i])
                                                cur = if (d != null && d.isDirectory) d
                                                else Saf.createDir(cur, parts[i]) ?: break
                                            }
                                        }
                                    }
                                }
                                e = zis.nextEntry
                            }
                        }
                    }
                    return@ToolDefinition JSONObject().put("destination", dst.absolutePath)
                        .put("files", count)
                        .put("ok", true)
                }
                dst.mkdirs()
                var count = 0
                java.util.zip.ZipFile(zip).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val target = File(dst, e.name)
                        // 防 Zip Slip
                        val canonical = target.canonicalPath
                        if (!canonical.startsWith(dst.canonicalPath + File.separator) && canonical != dst.canonicalPath) {
                            continue
                        }
                        if (e.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            zf.getInputStream(e).use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                            count++
                        }
                    }
                }
                JSONObject().put("destination", dst.absolutePath)
                    .put("files", count)
                    .put("ok", true)
            } catch (e: Exception) {
                Err.of(ErrorCodes.IO_ERROR, "解压失败: ${e.message}", "检查压缩包是否完整、目标目录是否可写")
            }
        }
    }

    // ===== 便捷别名工具（已合并，保留实现以兼容旧名调用） =====

    /** 解析并校验工作区路径，返回 null 表示应直接返回错误 */
    private fun checkPath(ctx: Context, f: File, what: String): JSONObject? {
        checkWorkspace(ctx, f)?.let { return it }
        return null
    }

    /** SAF 模式下创建文件（含父目录），失败返回错误 JSON 或 null */
    private fun safEnsureFile(ctx: Context, f: File): JSONObject? {
        val rel = Workspace.relativePath(ctx, f) ?: return Err.of(ErrorCodes.PATH_OUTSIDE_WORKSPACE, "路径在工作区之外: $f",
            "请使用以工作区为根的绝对路径")
        val base = Workspace.rootDoc(ctx) ?: return Err.of(ErrorCodes.PERMISSION_DENIED, "工作区不可用",
            "请重新选择工作区文件夹")
        val segs = rel.split('/').filter { it.isNotEmpty() }
        var cur = base
        for (i in segs.indices) {
            val child = cur.findFile(segs[i])
            if (child == null) {
                if (i == segs.lastIndex) {
                    cur = Saf.createFile(cur, segs[i]) ?: return Err.of(ErrorCodes.IO_ERROR, "创建文件失败: ${segs[i]}")
                } else {
                    cur = Saf.createDir(cur, segs[i]) ?: return Err.of(ErrorCodes.IO_ERROR, "创建目录失败: ${segs[i]}")
                }
            } else cur = child
        }
        return null
    }

    /** 创建空文件，如果文件已存在则更新其最后修改时间。truncate 为 true 时清空已有文件内容 */
    private fun touch(ctx: Context, truncateExisting: Boolean = false): ToolDefinition {
        return ToolDefinition(
            "touch",
            "创建空文件（文件已存在时更新时间戳）。truncate=true 时清空文件内容",
            paramSchema(listOf(
                param("path", "string", required = true, example = "/sdcard/Documents/new.txt", description = "文件路径"),
                param("truncate", "boolean", example = false, description = "true 时将已有文件截断为 0 字节（清空内容）"),
            ), required = listOf("path"))
        ) { args ->
            val f = resolve(ctx, require(args, "path"))
            checkPath(ctx, f, "touch")?.let { return@ToolDefinition it }
            val truncate = args.optBoolean("truncate", truncateExisting)
            if (Workspace.isSaf(ctx)) {
                var doc = Workspace.resolveDoc(ctx, f)
                if (doc == null || !doc.exists()) {
                    safEnsureFile(ctx, f)?.let { return@ToolDefinition it }
                    doc = Workspace.resolveDoc(ctx, f)
                }
                if (doc == null || !doc.exists()) return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "无法创建文件: $f",
                    "检查路径与工作区权限")
                if (truncate) {
                    // 打开并截断为 0 字节
                    val out = Saf.openOutputStream(ctx, doc)
                    if (out == null) return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED, "无法写入: 没有访问权限",
                        "请重新选择工作区文件夹")
                    out.use { }
                } else {
                    // 追加 0 字节并截断，触发文档修改
                    Saf.appendBytes(ctx, doc, ByteArray(0))
                }
                return@ToolDefinition JSONObject().put("path", f.absolutePath)
                    .put("truncated", truncate)
                    .put("created", false)
                    .put("touched", true).put("ok", true)
            }
            val created = !f.exists()
            val ok = try {
                if (!f.exists()) {
                    f.parentFile?.mkdirs()
                    f.createNewFile()
                } else if (truncate) {
                    f.writeBytes(ByteArray(0))
                    true
                } else {
                    f.setLastModified(System.currentTimeMillis())
                }
            } catch (e: Exception) {
                false
            }
            JSONObject().put("path", f.absolutePath)
                .put("truncated", truncate)
                .put("created", created)
                .put("touched", ok).put("ok", ok)
                .put("error", if (ok) null else "操作失败")
        }
    }

    /** 在目录中搜索文件或文件内容 */
    private fun fileSearch(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "file_search", "在目录中搜索文件或文件内容。search_content=false 按文件名关键词；search_content=true 按文件内容关键词（仅文本文件，UTF-8）",
            paramSchema(listOf(
                param("path", "string", example = ".", description = "搜索目录（默认当前工作区）"),
                param("keyword", "string", required = true, example = "report", description = "搜索关键词（大小写不敏感）"),
                param("searchContent", "boolean", example = false, description = "true 时按文件内容搜索"),
                param("extension", "string", example = "txt", description = "按扩展名过滤"),
                param("maxDepth", "integer", example = -1, description = "最大递归深度（-1 不限制）"),
                param("limit", "integer", example = 100, minimum = 1, maximum = 500, description = "最多返回条数"),
            ), required = listOf("keyword"))
        ) { args ->
            val raw = args.optString("path", ".")
            val keyword = require(args, "keyword").lowercase()
            val searchContent = args.optBoolean("searchContent", false)
            val extRaw = args.optString("extension", "").trim()
            val ext = if (extRaw.isEmpty()) "" else if (extRaw.startsWith(".")) extRaw.lowercase() else ".${extRaw.lowercase()}"
            val maxDepth = args.optInt("maxDepth", -1)
            val limit = args.optInt("limit", 100).coerceIn(1, 500)
            val root = resolve(ctx, raw)
            checkPath(ctx, root, "搜索")?.let { return@ToolDefinition it }
            val arr = JSONArray()
            if (Workspace.isSaf(ctx)) {
                val doc = Workspace.resolveDoc(ctx, root)
                    ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "目录不存在: $root", "请检查路径")
                fun walk(d: DocumentFile, depth: Int) {
                    if (arr.length() >= limit || (maxDepth >= 0 && depth > maxDepth)) return
                    for (c in Saf.list(d)) {
                        if (arr.length() >= limit) return
                        val name = c.name.orEmpty()
                        if (ext.isNotEmpty() && c.isFile && !name.lowercase().endsWith(ext)) continue
                        if (!searchContent) {
                            if (name.lowercase().contains(keyword)) arr.put(docEntryJson(c).put("match", "name"))
                        } else if (c.isFile) {
                            try {
                                val text = Saf.readBytes(ctx, c)?.toString(Charsets.UTF_8) ?: ""
                                if (text.lowercase().contains(keyword)) {
                                    arr.put(docEntryJson(c).put("match", "content"))
                                }
                            } catch (_: Exception) {
                            }
                        }
                        if (c.isDirectory) walk(c, depth + 1)
                    }
                }
                walk(doc, 0)
            } else {
                if (!root.exists() || !root.isDirectory) return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "目录不存在: $root",
                    "请检查路径")
                fun walk(dir: File, depth: Int) {
                    if (arr.length() >= limit || (maxDepth >= 0 && depth > maxDepth)) return
                    val children = dir.listFiles() ?: return
                    for (c in children) {
                        if (arr.length() >= limit) return
                        if (ext.isNotEmpty() && c.isFile && !c.name.lowercase().endsWith(ext)) continue
                        if (!searchContent) {
                            if (c.name.lowercase().contains(keyword)) arr.put(entryJson(c).put("match", "name"))
                        } else if (c.isFile) {
                            try {
                                val text = c.readText(Charsets.UTF_8)
                                if (text.lowercase().contains(keyword)) arr.put(entryJson(c).put("match", "content"))
                            } catch (_: Exception) {
                            }
                        }
                        if (c.isDirectory) walk(c, depth + 1)
                    }
                }
                walk(root, 0)
            }
            JSONObject().put("path", root.absolutePath)
                .put("keyword", keyword)
                .put("searchContent", searchContent)
                .put("count", arr.length())
                .put("limit", limit)
                .put("truncated", arr.length() >= limit)
                .put("entries", arr)
        }
    }

    private fun compareFiles(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "compare_files", "对比两个文件的差异，返回差异行（基于行的简单 diff）",
            paramSchema(listOf(
                param("fileA", "string", required = true, example = "/sdcard/Documents/a.txt", description = "第一个文件"),
                param("fileB", "string", required = true, example = "/sdcard/Documents/b.txt", description = "第二个文件"),
            ), required = listOf("fileA", "fileB"))
        ) { args ->
            val fa = resolve(ctx, require(args, "fileA"))
            val fb = resolve(ctx, require(args, "fileB"))
            checkPath(ctx, fa, "对比")?.let { return@ToolDefinition it }
            checkPath(ctx, fb, "对比")?.let { return@ToolDefinition it }
            val readText: (File) -> String? = { file ->
                if (Workspace.isSaf(ctx)) {
                    val doc = Workspace.resolveDoc(ctx, file)
                    if (doc == null || !doc.exists()) null
                    else Saf.readBytes(ctx, doc)?.toString(Charsets.UTF_8)
                } else {
                    if (!file.exists() || !file.isFile) null else try { file.readText(Charsets.UTF_8) } catch (_: Exception) { null }
                }
            }
            val ta = readText(fa) ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "文件不存在: $fa", "请检查路径")
            val tb = readText(fb) ?: return@ToolDefinition Err.of(ErrorCodes.FILE_NOT_FOUND, "文件不存在: $fb", "请检查路径")
            val la = ta.split("\n", "\r\n")
            val lb = tb.split("\n", "\r\n")
            // 简单 LCS diff（限制在合理规模内）
            val n = la.size
            val m = lb.size
            val maxLen = 2000
            if (n > maxLen || m > maxLen) {
                return@ToolDefinition Err.of(ErrorCodes.NOT_IMPLEMENTED, "文件行数过大（> $maxLen 行），暂不支持对比",
                    "请对比行数较小的文件")
            }
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    dp[i][j] = if (la[i] == lb[j]) dp[i + 1][j + 1] + 1
                    else maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
            val diffs = JSONArray()
            var i = 0
            var j = 0
            while (i < n && j < m) {
                if (la[i] == lb[j]) { i++; j++ }
                else if (dp[i + 1][j] >= dp[i][j + 1]) {
                    diffs.put(JSONObject().apply { put("type", "-"); put("lineA", i + 1); put("text", la[i]) })
                    i++
                } else {
                    diffs.put(JSONObject().apply { put("type", "+"); put("lineB", j + 1); put("text", lb[j]) })
                    j++
                }
            }
            while (i < n) { diffs.put(JSONObject().apply { put("type", "-"); put("lineA", i + 1); put("text", la[i]) }); i++ }
            while (j < m) { diffs.put(JSONObject().apply { put("type", "+"); put("lineB", j + 1); put("text", lb[j]) }); j++ }
            JSONObject().put("fileA", fa.absolutePath)
                .put("fileB", fb.absolutePath)
                .put("linesA", n)
                .put("linesB", m)
                .put("equal", diffs.length() == 0)
                .put("diffCount", diffs.length())
                .put("diffs", diffs)
        }
    }

    private fun webDownload(ctx: Context): ToolDefinition {
        return ToolDefinition(
            "web_download", "下载远程文件到本地存储，支持大文件下载。url 为下载地址；path 为目标文件路径（工作区内）",
            paramSchema(listOf(
                param("url", "string", required = true, example = "https://example.com/file.zip", description = "下载地址（http/https）"),
                param("path", "string", required = true, example = "/sdcard/Documents/file.zip", description = "目标文件路径（工作区内）"),
                param("timeoutMs", "integer", example = 30000, minimum = 5000, maximum = 300000, description = "超时时间（毫秒）"),
                param("maxBytes", "integer", example = 1073741824, description = "最大下载字节数（默认和最大值均为 1GB）"),
            ), required = listOf("url", "path"))
        ) { args ->
            val urlStr = require(args, "url")
            val f = resolve(ctx, require(args, "path"))
            checkPath(ctx, f, "下载")?.let { return@ToolDefinition it }
            if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "仅支持 http/https 链接",
                    "url 必须以 http:// 或 https:// 开头")
            }
            val timeout = args.optInt("timeoutMs", 30000).coerceIn(5000, 300000)
            val maxBytes = args.optLong("maxBytes", 1024L * 1024 * 1024).coerceIn(1024, 1024L * 1024 * 1024)
            
            // 设置下载通知
            val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "web_download_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "下载通知",
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }
            
            val fileName = f.name
            val notificationId = System.currentTimeMillis().toInt()
            val builder = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载: $fileName")
                .setContentText("准备中...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(100, 0, false)
            
            var lastUpdateTime = System.currentTimeMillis()
            var lastWritten = 0L
            
            return@ToolDefinition try {
                // 首次调用 DNS/连接可能较慢：连接超时固定取 max(timeout/2, 15s)
                val connectTimeout = maxOf(timeout / 2, 15000)
                val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = connectTimeout
                conn.readTimeout = timeout
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "MCP-AndroidServer/1.0")
                val code = conn.responseCode
                if (code >= 400) {
                    notificationManager.cancel(notificationId)
                    return@ToolDefinition Err.of(ErrorCodes.HTTP_ERROR, "下载失败: HTTP $code",
                        "检查 url 是否正确、服务端是否可用")
                }
                val total = conn.contentLengthLong
                if (total > maxBytes) {
                    conn.disconnect()
                    notificationManager.cancel(notificationId)
                    return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "文件过大: $total 字节（限制 $maxBytes）",
                        "增大 maxBytes 或使用其他下载方式")
                }
                
                // 更新通知显示总大小
                if (total > 0) {
                    builder.setContentText("总大小: ${fmtSize(total)}")
                    notificationManager.notify(notificationId, builder.build())
                }
                
                val input = conn.inputStream ?: run {
                    notificationManager.cancel(notificationId)
                    return@ToolDefinition Err.of(ErrorCodes.NETWORK_ERROR, "无响应内容",
                        "服务端未返回内容，请检查 url")
                }
                var written = 0L
                var exceeded = false
                
                if (Workspace.isSaf(ctx)) {
                    var doc = Workspace.resolveDoc(ctx, f)
                    if (doc == null || !doc.exists()) {
                        safEnsureFile(ctx, f)?.let { 
                            notificationManager.cancel(notificationId)
                            return@ToolDefinition it 
                        }
                        doc = Workspace.resolveDoc(ctx, f)
                    }
                    val out = Saf.openOutputStream(ctx, doc!!)
                        ?: run {
                            notificationManager.cancel(notificationId)
                            return@ToolDefinition Err.of(ErrorCodes.PERMISSION_DENIED, "无法写入: 没有访问权限",
                                "请重新选择工作区文件夹")
                        }
                    out.use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > maxBytes) {
                                exceeded = true
                                break
                            }
                            o.write(buf, 0, n)
                            
                            // 更新通知（每 500ms 更新一次）
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500) {
                                val speed = (written - lastWritten) * 1000 / (currentTime - lastUpdateTime)
                                val speedText = "${fmtSize(speed)}/s"
                                val progressText = if (total > 0) {
                                    "${fmtSize(written)} / ${fmtSize(total)} ($speedText)"
                                } else {
                                    "${fmtSize(written)} ($speedText)"
                                }
                                builder.setContentText(progressText)
                                if (total > 0) {
                                    builder.setProgress(100, (written * 100 / total).toInt(), false)
                                } else {
                                    builder.setProgress(0, 0, true)
                                }
                                notificationManager.notify(notificationId, builder.build())
                                lastUpdateTime = currentTime
                                lastWritten = written
                            }
                        }
                    }
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > maxBytes) {
                                exceeded = true
                                break
                            }
                            o.write(buf, 0, n)
                            
                            // 更新通知（每 500ms 更新一次）
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500) {
                                val speed = (written - lastWritten) * 1000 / (currentTime - lastUpdateTime)
                                val speedText = "${fmtSize(speed)}/s"
                                val progressText = if (total > 0) {
                                    "${fmtSize(written)} / ${fmtSize(total)} ($speedText)"
                                } else {
                                    "${fmtSize(written)} ($speedText)"
                                }
                                builder.setContentText(progressText)
                                if (total > 0) {
                                    builder.setProgress(100, (written * 100 / total).toInt(), false)
                                } else {
                                    builder.setProgress(0, 0, true)
                                }
                                notificationManager.notify(notificationId, builder.build())
                                lastUpdateTime = currentTime
                                lastWritten = written
                            }
                        }
                    }
                }
                input.close()
                conn.disconnect()
                
                // 检查是否超出大小限制
                if (exceeded) {
                    // 删除已下载的部分文件
                    if (!Workspace.isSaf(ctx) && f.exists()) {
                        f.delete()
                    }
                    notificationManager.cancel(notificationId)
                    return@ToolDefinition Err.of(ErrorCodes.IO_ERROR, "超出大小限制: 已下载 $written 字节（限制 $maxBytes）",
                        "增大 maxBytes 或使用其他下载方式")
                }
                
                // 下载完成，更新通知
                builder.setContentTitle("下载完成: $fileName")
                    .setContentText("大小: ${fmtSize(written)}")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                notificationManager.notify(notificationId, builder.build())
                
                JSONObject().put("ok", true)
                    .put("url", urlStr)
                    .put("path", f.absolutePath)
                    .put("bytes", written)
                    .put("sizeHuman", fmtSize(written))
            } catch (e: Exception) {
                notificationManager.cancel(notificationId)
                Err.of(ErrorCodes.NETWORK_ERROR, "下载失败: ${e.message}",
                    "检查网络连接与 url 是否可访问")
            }
        }
    }

    private fun md5(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("MD5").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }
}
