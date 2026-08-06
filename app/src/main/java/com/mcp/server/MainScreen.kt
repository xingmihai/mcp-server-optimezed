package com.mcp.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mcp.server.server.LogEntry
import com.mcp.server.server.LogStore
import com.mcp.server.server.McpServerService
import com.mcp.server.server.Net
import com.mcp.server.server.Settings
import com.mcp.server.tools.Workspace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class StatusInfo(
    val running: Boolean = false,
    val ips: List<String> = emptyList(),
    val wifi: String? = null,
    val tunnelUrl: String? = null,
    val tunnelActive: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onClearLogs: () -> Unit = {},
    showStorageDialog: Boolean = false,
    onStorageDialogDismiss: () -> Unit = {},
    onRequestStorage: () -> Unit = {},
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0=主页 1=日志
    var status by remember { mutableStateOf(StatusInfo()) }
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var showStorageDialogState by remember { mutableStateOf(showStorageDialog) }
    var workspaceDisplay by remember { mutableStateOf(Settings.workspace(context)) }
    var portText by remember { mutableStateOf(Settings.port(context).toString()) }
    var showPortDialog by remember { mutableStateOf(false) }
    var showRestartHint by remember { mutableStateOf(false) }

    // 系统文件夹选择器：选择应用允许访问的文件夹作为工作区（SAF）
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            // 保存 SAF URI + 解析出的真实路径（供路径校验与显示）
            Settings.setWorkspaceUri(context, uri.toString())
            val realPath = Workspace.pathFromTreeUri(context, uri)
            if (realPath != null) {
                Settings.setWorkspace(context, realPath)
                workspaceDisplay = realPath
            } else {
                workspaceDisplay = uri.toString()
            }
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 轮询服务器状态 + 日志（含隧道状态，动态刷新）
    LaunchedEffect(Unit) {
        while (true) {
            val srv = McpServerService.server
            status = StatusInfo(
                running = srv?.isRunning == true,
                ips = Net.ipv4Addresses(),
                wifi = Net.wifiSsid(context),
                tunnelUrl = McpServerService.tunnelUrl,
                tunnelActive = McpServerService.tunnelActive,
            )
            logs = LogStore.snapshot()
            delay(1000)
        }
    }

    val currentPort = Settings.port(context)
    // 显示真实路径（SAF 模式下也显示文件系统路径，不显示 content:// URI）
    val currentWorkspace = workspaceDisplay

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                // 侧栏头部：图标 + 应用名 + 版本号
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "应用图标",
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                CircleShape,
                            )
                            .padding(10.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("MCP 服务器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(
                    label = { Text("工具列表") },
                    icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenTools()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("设置") },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("MCP 桥接") },
                    icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        context.startActivity(Intent(context, BridgeActivity::class.java))
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("关于应用") },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenAbout()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (selectedTab) {
                                0 -> "MCP 服务器"
                                else -> "运行日志"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                    actions = {
                        if (selectedTab == 1 && logs.isNotEmpty()) {
                            TextButton(onClick = {
                                val text = logs.joinToString("\n") {
                                    "${it.timeText} [${it.level}] ${it.message}"
                                }
                                copyText(context, text)
                                android.widget.Toast.makeText(
                                    context,
                                    "已复制全部日志",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }) { Text("复制") }
                            TextButton(onClick = onClearLogs) { Text("清空") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("主页") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                        label = { Text("日志") },
                    )
                }
            },
        ) { padding ->
            if (selectedTab == 0) {
                HomeTab(
                    context = context,
                    status = status,
                    currentPort = currentPort,
                    currentWorkspace = currentWorkspace,
                    onStartServer = onStartServer,
                    onStopServer = onStopServer,
                    onPickWorkspace = { folderPicker.launch(null) },
                    onEditPort = {
                        portText = Settings.port(context).toString()
                        showPortDialog = true
                    },
                    modifier = Modifier.padding(padding),
                )
            } else {
                LogTab(
                    logs = logs,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    // ===== 修改端口弹窗（无遮罩：Dialog 默认不绘制 dim scrim） =====
    if (showPortDialog) {
        var portError by remember { mutableStateOf<String?>(null) }
        Dialog(
            onDismissRequest = { showPortDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "修改端口",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "默认端口 1145。修改后需重启服务器才能生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it; portError = null },
                        label = { Text("端口号") },
                        singleLine = true,
                        isError = portError != null,
                        supportingText = { portError?.let { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showPortDialog = false }) { Text("取消") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = {
                            val p = portText.trim().toIntOrNull()
                            if (p == null || p !in 1..65535) {
                                portError = "请输入 1-65535 之间的端口号"
                                return@TextButton
                            }
                            Settings.setPort(context, p)
                            showPortDialog = false
                            // 服务器运行中提示重启
                            if (McpServerService.server?.isRunning == true) {
                                showRestartHint = true
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "端口已保存为 $p，启动服务器后生效",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }) { Text("保存") }
                    }
                }
            }
        }
    }

    // ===== 端口修改后重启提示弹窗 =====
    if (showRestartHint) {
        AlertDialog(
            onDismissRequest = { showRestartHint = false },
            title = { Text("端口已修改") },
            text = { Text("新端口将在下次启动时生效。建议先停止再重新启动服务器。") },
            confirmButton = {
                TextButton(onClick = { showRestartHint = false }) { Text("知道了") }
            },
        )
    }

    // ===== 首次启动引导弹窗 =====
    if (showStorageDialogState) {
        AlertDialog(
            onDismissRequest = {
                showStorageDialogState = false
                onStorageDialogDismiss()
            },
            title = { Text("选择工作区文件夹") },
            text = {
                Text("客户端文件操作需要一个工作区文件夹。点击确定后使用系统文件夹选择器选择工作区（无需额外存储权限）。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showStorageDialogState = false
                    onRequestStorage()
                }) { Text("选择文件夹") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStorageDialogState = false
                    onStorageDialogDismiss()
                }) { Text("稍后") }
            },
        )
    }
}

@Composable
private fun HomeTab(
    context: Context,
    status: StatusInfo,
    currentPort: Int,
    currentWorkspace: String,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onPickWorkspace: () -> Unit,
    onEditPort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ===== 状态卡片 =====
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            color = if (status.running) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (status.running) "服务器运行中" else "服务器未运行",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (status.running) {
                        Text(
                            "端口 $currentPort · ${status.wifi ?: "未知网络"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
                Button(
                    onClick = if (status.running) onStopServer else onStartServer,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (status.running) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(
                        if (status.running) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (status.running) "停止" else "启动")
                }
            }
            // 端口编辑行（点击整行弹出修改弹窗）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditPort)
                    .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Dns, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "端口 $currentPort",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }

        // ===== 连接地址（仅运行时显示） =====
        if (status.running) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("连接地址", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    val ip = status.ips.firstOrNull() ?: "192.168.x.x"
                    val url = "http://$ip:$currentPort/mcp"
                    ConnectionRow(label = "局域网 MCP 端点", value = url) {
                        copyText(context, url)
                    }
                    val localUrl = "http://127.0.0.1:$currentPort/mcp"
                    ConnectionRow(label = "手机本机 MCP", value = localUrl) {
                        copyText(context, localUrl)
                    }
                    // 内网穿透公网地址（状态每秒轮询刷新）
                    val tunnelUrl = status.tunnelUrl
                    val tunnelActive = status.tunnelActive
                    if (tunnelActive) {
                        if (tunnelUrl != null) {
                            ConnectionRow(label = "公网 MCP 端点", value = tunnelUrl) {
                                copyText(context, tunnelUrl)
                            }
                            Text(
                                "隧道已连接",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF2E7D32),
                            )
                        } else {
                            Text(
                                "隧道连接中",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFF9A825),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "同一 Wi-Fi 下的电脑可通过局域网地址连接；开启内网穿透后可通过公网地址连接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ===== 工作区卡片 =====
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("工作区", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "客户端只能操作工作区内的文件。点击下方按钮使用系统文件夹选择器选择工作区文件夹（无需额外存储权限）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        currentWorkspace,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onPickWorkspace) { Text("选择工作区文件夹") }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LogTab(logs: List<LogEntry>, modifier: Modifier = Modifier) {
    if (logs.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "暂无日志",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        items(logs, key = { it.id }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    entry.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.level,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (entry.level) {
                        "E" -> Color(0xFFC62828)
                        "W" -> Color(0xFFF9A825)
                        else -> Color(0xFF2E7D32)
                    },
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConnectionRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = "复制",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onCopy))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
        }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("mcp", text))
}
