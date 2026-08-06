package com.mcp.server

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcp.server.server.FloatWindowService
import com.mcp.server.server.McpServerService
import com.mcp.server.server.Settings
import com.mcp.server.server.WakeLockHelper
import com.mcp.server.tools.RootHelper
import com.mcp.server.tools.Settings2
import com.mcp.server.tools.ShizukuHelper
import com.mcp.server.ui.theme.McpTheme

class SettingsActivity : ComponentActivity() {

    /** 权限授权结果 tick：+1 触发设置页重新计算权限状态 */
    private val permissionTick = mutableIntStateOf(0)

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        permissionTick.intValue++
        // 授权返回后，若悬浮窗开关已开启则立即启动悬浮窗
        if (AndroidSettings.canDrawOverlays(this) && Settings.floatWindowEnabled(this)) {
            FloatWindowService.start(this)
        }
    }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionTick.intValue++ }

    /** Shizuku 授权：通过 provider 引导，授权结果通过监听回调刷新 */
    private val shizukuRequestCode = 10001

    private val shizukuPermissionListener = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            permissionTick.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            McpTheme {
                SettingsScreen(
                    onBack = { finish() },
                    permissionTick = permissionTick.intValue,
                    onRequestOverlay = {
                        if (!AndroidSettings.canDrawOverlays(this)) {
                            try {
                                overlayLauncher.launch(Intent(
                                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")))
                            } catch (_: Exception) {
                                overlayLauncher.launch(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION))
                            }
                        }
                    },
                    onRequestBattery = {
                        if (!isIgnoringBatteryOptimizations(this)) {
                            try {
                                batteryLauncher.launch(Intent(
                                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:$packageName")))
                            } catch (_: Exception) {
                                batteryLauncher.launch(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    },
                    onRequestShizuku = {
                        try {
                            if (rikka.shizuku.Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                                rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
                                rikka.shizuku.Shizuku.requestPermission(shizukuRequestCode)
                            } else {
                                permissionTick.intValue++
                            }
                        } catch (_: Exception) {
                        }
                    },
                )
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    permissionTick: Int = 0,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestShizuku: () -> Unit = {},
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    var tokenEnabled by remember { mutableStateOf(Settings.tokenEnabled(context)) }
    var wakeLock by remember { mutableStateOf(Settings.wakeLockEnabled(context)) }
    var floatWindow by remember { mutableStateOf(Settings.floatWindowEnabled(context)) }
    var autoStartMcp by remember { mutableStateOf(Settings.autoStart(context)) }
    var tunnelEnabled by remember { mutableStateOf(Settings.tunnelEnabled(context)) }
    var tunnelServer by remember { mutableStateOf(Settings.tunnelServer(context)) }
    var tunnelSecret by remember { mutableStateOf(Settings.tunnelSecret(context)) }

    // refresh 变化时重新读取令牌（重新生成后刷新显示）
    val token = remember(refresh) { Settings.token(context) }

    // permissionTick 变化时重新计算权限状态（授权返回后刷新）
    val hasOverlay = remember(permissionTick) { AndroidSettings.canDrawOverlays(context) }
    val hasBattery = remember(permissionTick) { isIgnoringBatteryOptimizations(context) }

    // 高级执行权限（脚本/系统命令）
    var shizukuEnabled by remember { mutableStateOf(Settings2.shizukuEnabled(context)) }
    var rootEnabled by remember { mutableStateOf(Settings2.rootEnabled(context)) }
    val shizukuAvailable = remember(permissionTick) { ShizukuHelper.isAvailable(context) }
    val shizukuGranted = remember(permissionTick) { ShizukuHelper.isGranted(context) }
    val rootAvailable = remember(permissionTick) { RootHelper.isAvailable() }

    fun floatSwitch(on: Boolean) {
        floatWindow = on
        Settings.setFloatWindowEnabled(context, on)
        if (on) {
            if (AndroidSettings.canDrawOverlays(context)) {
                FloatWindowService.start(context)
            } else {
                onRequestOverlay()
            }
        } else {
            FloatWindowService.stop(context)
        }
    }
    val onFloatSwitch: (Boolean) -> Unit = { floatSwitch(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ===== 访问令牌 =====
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("访问令牌", style = MaterialTheme.typography.titleSmall)
                    Text("客户端连接时校验 Authorization: Bearer <token>",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用访问令牌", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (tokenEnabled) "令牌: $token" else "关闭后无需认证即可访问（局域网风险自负）",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = if (tokenEnabled) FontFamily.Monospace else null,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = tokenEnabled,
                            onCheckedChange = {
                                tokenEnabled = it
                                Settings.setTokenEnabled(context, it)
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前令牌", style = MaterialTheme.typography.bodyLarge)
                            Text(token, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { copyText(context, token) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        FilledTonalButton(onClick = {
                            Settings.setToken(context, java.util.UUID.randomUUID().toString().replace("-", "").take(16))
                            refresh++
                        }) { Text("重新生成") }
                    }
                }
            }

            // ===== 后台保活 =====
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("后台保活", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    ToggleRow(
                        icon = Icons.Outlined.Circle,
                        title = "WakeLock 保持运行",
                        subtitle = "屏幕熄灭后保持 CPU 唤醒，防止服务器被挂起",
                        checked = wakeLock,
                        onCheckedChange = {
                            wakeLock = it
                            Settings.setWakeLockEnabled(context, it)
                            if (it) {
                                WakeLockHelper.acquire(context)
                            } else {
                                WakeLockHelper.release()
                            }
                        },
                    )
                    ToggleRow(
                        icon = Icons.Outlined.Wallpaper,
                        title = "悬浮窗",
                        subtitle = "开启后，只要应用在运行就持续显示状态小窗（可拖动、点击回主页）",
                        checked = floatWindow,
                        onCheckedChange = onFloatSwitch,
                    )
                    ToggleRow(
                        icon = Icons.Outlined.Power,
                        title = "开机自启 MCP 服务",
                        subtitle = "开机后自动启动 MCP 服务器（依赖系统允许自启动/后台启动）",
                        checked = autoStartMcp,
                        onCheckedChange = {
                            autoStartMcp = it
                            Settings.setAutoStart(context, it)
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "说明：开机自启依赖系统设置中的自启动/后台启动权限，与悬浮窗等保活方式相互独立。若系统限制后台启动，请在各厂商管家中允许本应用自启动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ===== 内网穿透 =====
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("内网穿透", style = MaterialTheme.typography.titleSmall)
                    Text("将本地 MCP 服务器通过远程服务器暴露为公网地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    ToggleRow(
                        icon = Icons.Outlined.Public,
                        title = "启用内网穿透",
                        subtitle = "开启后，启动服务器时默认自动连接隧道",
                        checked = tunnelEnabled,
                        onCheckedChange = {
                            tunnelEnabled = it
                            Settings.setTunnelEnabled(context, it)
                            // 服务器运行中时，立即启停隧道
                            if (McpServerService.server?.isRunning == true) {
                                if (it) {
                                    McpServerService.restartTunnel(context)
                                } else {
                                    McpServerService.stopTunnel(context)
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = tunnelServer,
                        onValueChange = { tunnelServer = it },
                        label = { Text("远程服务器") },
                        placeholder = { Text("bore.pub") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tunnelSecret,
                        onValueChange = { tunnelSecret = it },
                        label = { Text("认证密钥（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        Settings.setTunnelServer(context, tunnelServer.trim().ifEmpty { "bore.pub" })
                        Settings.setTunnelSecret(context, tunnelSecret.trim())
                        if (McpServerService.server?.isRunning == true) {
                            McpServerService.restartTunnel(context)
                            android.widget.Toast.makeText(context, "隧道已重启", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "服务器未运行，配置已保存，启动服务器后生效",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }) { Text("保存并应用") }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "提示：隧道需要 MCP 服务器运行才会建立连接。若长时间\u201C连接中\u201D，请检查手机网络能否访问远程服务器的 7835 端口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ===== 权限 =====
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("权限", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    PermissionRow(
                        "WakeLock",
                        "允许保持 CPU 唤醒",
                        hasPermission(context, Manifest.permission.WAKE_LOCK),
                        "申请",
                    ) { /* WAKE_LOCK 为普通权限，安装时自动授予，无需手动申请 */ }
                    PermissionRow(
                        "电池无限制",
                        "忽略电池优化，防止系统杀后台",
                        hasBattery,
                        "申请",
                        onRequestBattery,
                    )
                    PermissionRow(
                        "悬浮窗权限（非保活核心）",
                        "允许在其他应用上层显示",
                        hasOverlay,
                        "申请",
                        onRequestOverlay,
                    )
                    PermissionRow(
                        "开机自启",
                        "开机广播自动启动服务",
                        true,
                        "申请",
                    ) {
                        // 系统层面无统一开关，跳转应用信息页
                        try {
                            context.startActivity(Intent(
                                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "开机自启需系统允许自启动/后台启动，请同时在系统设置或厂商管家中允许本应用自动启动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ===== 高级执行权限（脚本/系统命令） =====
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("高级执行权限（脚本/系统命令）", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))

                    // Shizuku
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Shizuku（推荐）", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    !shizukuAvailable -> "未安装 Shizuku（需要安装并启动 Shizuku 应用）"
                                    shizukuGranted -> "已授权 · 可用 shell 权限执行命令"
                                    else -> "需要授权（点击右侧按钮）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (shizukuAvailable && !shizukuGranted) {
                            FilledTonalButton(onClick = onRequestShizuku) { Text("授权") }
                        } else {
                            Switch(
                                checked = shizukuEnabled && shizukuGranted,
                                onCheckedChange = {
                                    shizukuEnabled = it
                                    Settings2.setShizukuEnabled(context, it)
                                },
                            )
                        }
                    }

                    // Root
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Root（Magisk/KernelSU）", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (rootAvailable) "检测到 root，开启后以 root 权限执行命令"
                                else "未检测到 root 环境（需要 Magisk/KernelSU 等）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            enabled = rootAvailable,
                            checked = rootEnabled,
                            onCheckedChange = {
                                rootEnabled = it
                                Settings2.setRootEnabled(context, it)
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "注意：开启高级权限后，shell 可执行任意命令，请仅在可信网络/本机使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionText: String,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Text("已授权", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelLarge)
        } else {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

private fun checkPermission(context: Context, perm: String): Boolean =
    try {
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

private fun hasPermission(context: Context, perm: String): Boolean = checkPermission(context, perm)

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("mcp", text))
}
