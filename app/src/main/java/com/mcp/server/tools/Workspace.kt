package com.mcp.server.tools

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.mcp.server.server.Settings
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 工作区：客户端只能操作工作区目录内的文件。
 * 工作区通过系统文件夹选择器（SAF）选择，应用获得该目录的长期访问权（URI 持久授权）。
 * - 有 SAF URI 时：文件操作全部通过 DocumentFile 进行（Android 10+ 无需"所有文件访问"权限）
 * - 无 SAF URI 时：回退到普通 File（应用私有目录 / 旧版本 / 已授予所有文件访问权限时）
 */
object Workspace {

    /** 工作区显示名（供 UI 显示） */
    fun display(context: Context): String {
        val uri = Settings.workspaceUri(context)
        return if (uri != null) uri else Settings.workspace(context)
    }

    /** 工作区根 DocumentFile（SAF 模式） */
    fun rootDoc(context: Context): DocumentFile? {
        val uri = Settings.workspaceUri(context) ?: return null
        return try {
            DocumentFile.fromTreeUri(context.applicationContext, Uri.parse(uri))
        } catch (_: Exception) {
            null
        }
    }

    /** 是否 SAF 工作区模式 */
    fun isSaf(context: Context): Boolean = Settings.workspaceUri(context) != null

    fun path(context: Context): String = Settings.workspace(context)

    fun root(context: Context): File = File(Settings.workspace(context))

    /** 将路径转换为工作区内的 DocumentFile，返回 null 表示不可访问 */
    fun resolveDoc(context: Context, path: String): DocumentFile? {
        val base = rootDoc(context) ?: return null
        val rel = relativePath(context, path) ?: return null
        var cur: DocumentFile = base
        if (rel.isEmpty()) return base
        for (seg in rel.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") return null
            cur = cur.findFile(seg) ?: return null
        }
        return cur
    }

    fun resolveDoc(context: Context, file: File): DocumentFile? = resolveDoc(context, file.absolutePath)

    /** 计算 path 相对工作区的相对路径；在工作区外返回 null */
    fun relativePath(context: Context, path: String): String? {
        val rootPath = normalized(root(context))
        val f = File(path)
        val filePath = normalized(f)
        if (filePath == rootPath) return ""
        if (!filePath.startsWith(rootPath + File.separator)) return null
        return filePath.substring(rootPath.length + 1)
    }

    fun relativePath(context: Context, file: File): String? = relativePath(context, file.absolutePath)

    /**
     * 将 SAF tree URI 解析为真实文件系统路径（如 /storage/emulated/0/Documents/MyFolder）。
     * 解析失败返回 null。
     */
    fun pathFromTreeUri(context: Context, uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            if (split.size < 2) null
            else {
                val type = split[0]
                val rel = split.drop(1).joinToString("/")
                val base = when (type) {
                    "primary" -> Environment.getExternalStorageDirectory().absolutePath
                    else -> "/storage/$type"
                }
                if (rel.isEmpty()) base else "$base/$rel"
            }
        } catch (_: Exception) {
            null
        }
    }


    /** 返回 null 表示允许访问；否则返回拒绝原因 */
    fun rejectReason(context: Context, file: File): String? {
        val rootPath = normalized(root(context))
        val filePath = normalized(file)
        if (filePath == rootPath) return null
        if (!filePath.startsWith(rootPath + File.separator)) {
            return "路径在工作区之外: $filePath（工作区: $rootPath）"
        }
        return null
    }

    private fun normalized(f: File): String = try {
        f.canonicalPath
    } catch (_: Exception) {
        f.absoluteFile.normalize().path
    }
}

/** SAF 辅助：在工作区内创建/写入/读取（通过 ContentResolver 访问 URI） */
object Saf {
    fun findChild(parent: DocumentFile, name: String): DocumentFile? = parent.findFile(name)

    fun createFile(parent: DocumentFile, name: String): DocumentFile? =
        parent.createFile("application/octet-stream", name)

    fun createDir(parent: DocumentFile, name: String): DocumentFile? = parent.createDirectory(name)

    fun list(doc: DocumentFile): List<DocumentFile> =
        (doc.listFiles() ?: emptyArray()).sortedBy { it.name.orEmpty().lowercase() }

    fun readBytes(context: Context, doc: DocumentFile): ByteArray? {
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    fun writeBytes(context: Context, doc: DocumentFile, bytes: ByteArray): Boolean {
        return try {
            val out: OutputStream? = context.contentResolver.openOutputStream(doc.uri)
            out?.use { it.write(bytes) }
            out != null
        } catch (_: Exception) {
            false
        }
    }

    fun appendBytes(context: Context, doc: DocumentFile, bytes: ByteArray): Boolean {
        return try {
            val out: OutputStream? = context.contentResolver.openOutputStream(doc.uri, "a")
            out?.use { it.write(bytes) }
            out != null
        } catch (_: Exception) {
            false
        }
    }

    fun openInputStream(context: Context, doc: DocumentFile): InputStream? =
        try { context.contentResolver.openInputStream(doc.uri) } catch (_: Exception) { null }

    fun openOutputStream(context: Context, doc: DocumentFile): OutputStream? =
        try { context.contentResolver.openOutputStream(doc.uri) } catch (_: Exception) { null }

    fun copy(context: Context, doc: DocumentFile, destDoc: DocumentFile): Boolean {
        return try {
            val ins: InputStream = context.contentResolver.openInputStream(doc.uri) ?: return false
            val out: OutputStream = context.contentResolver.openOutputStream(destDoc.uri) ?: return false
            ins.use { i -> out.use { o -> i.copyTo(o) } }
            true
        } catch (_: Exception) {
            false
        }
    }
}
