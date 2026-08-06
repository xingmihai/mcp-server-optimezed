package com.mcp.server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mcp.server.server.McpServerService
import com.mcp.server.ui.theme.McpTheme
import com.mcp.server.server.LogStore

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果不阻塞任何流程 */ }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                com.mcp.server.server.Settings.setWorkspaceUri(this, uri.toString())
                val realPath = com.mcp.server.tools.Workspace.pathFromTreeUri(this, uri)
                if (realPath != null) {
                    com.mcp.server.server.Settings.setWorkspace(this, realPath)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("mcp_server_settings", MODE_PRIVATE)
        // 首次运行且还没有选择工作区文件夹（SAF）时，引导选择
        val firstRun = !prefs.getBoolean("storage_permission_dialog_shown", false)
        val noWorkspace = com.mcp.server.server.Settings.workspaceUri(this).isNullOrEmpty()
        val needStorage = firstRun && noWorkspace
        setContent {
            McpTheme {
                MainScreen(
                    onStartServer = { startServer() },
                    onStopServer = { stopServer() },
                    onOpenTools = { startActivity(Intent(this, ToolsActivity::class.java)) },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenAbout = { startActivity(Intent(this, AboutActivity::class.java)) },
                    onClearLogs = { LogStore.clear() },
                    showStorageDialog = firstRun && needStorage,
                    onStorageDialogDismiss = {
                        prefs.edit().putBoolean("storage_permission_dialog_shown", true).apply()
                    },
                    onRequestStorage = {
                        prefs.edit().putBoolean("storage_permission_dialog_shown", true).apply()
                        // 直接打开系统文件夹选择器选择工作区（SAF 方式，无需任何权限）
                        folderPicker.launch(null)
                    },
                )
            }
        }
        // 悬浮窗开关已开启且已授权时，重新打开应用自动恢复悬浮窗
        restoreFloatWindowIfNeeded()
    }

    private fun restoreFloatWindowIfNeeded() {
        try {
            if (com.mcp.server.server.Settings.floatWindowEnabled(this) &&
                (Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)) &&
                !com.mcp.server.server.FloatWindowService.running) {
                com.mcp.server.server.FloatWindowService.start(this)
            }
        } catch (_: Exception) {
        }
    }

    private fun startServer() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            // 前台服务通知需要通知权限，先请求
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            startForegroundServer()
        }
    }

    private fun startForegroundServer() {
        val intent = Intent(this, McpServerService::class.java)
            .setAction(McpServerService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopServer() {
        val intent = Intent(this, McpServerService::class.java)
            .setAction(McpServerService.ACTION_STOP)
        startService(intent)
    }
}
