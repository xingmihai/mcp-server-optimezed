package com.mcp.server

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcp.server.bridge.BridgeConfig
import com.mcp.server.bridge.BridgeManager
import com.mcp.server.server.McpAppCtx
import com.mcp.server.tools.ToolKit
import com.mcp.server.ui.theme.McpTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BridgeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            McpTheme {
                BridgeScreen(onBack = { finish() })
            }
        }
    }
}

/** 桥接列表状态 */
private data class BridgeUiState(
    val bridges: List<BridgeConfig> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(BridgeUiState()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BridgeConfig?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<BridgeConfig?>(null) }

    fun doRefresh() {
        state = state.copy(loading = true)
        scope.launch {
            val (bridges, errors) = withContext(Dispatchers.IO) {
                BridgeManager.refresh(McpAppCtx.app)
            }
            ToolKit.rebuildBridgeTools()
            state = BridgeUiState(bridges = bridges, errors = errors, loading = false)
        }
    }
    // 防抖刷新：避免同一状态连续触发（如开关切换后），卡住的问题见 onToggleEnabled 注释
    var refreshJob: kotlinx.coroutines.Job? = null
    fun debouncedRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            kotlinx.coroutines.delay(200)
            doRefresh()
        }
    }

    // 首次进入自动拉取
    if (refreshTick == 0) {
        LaunchedEffect(Unit) { doRefresh() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 桥接", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { doRefresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            // 添加新桥接
            Button(onClick = {
                editing = null
                BridgeManager.load(context) // 刷新前缀分配计数器，保证自动命名不重复
                showEditDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加桥接")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) {
                Text("正在拉取桥接工具...", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (state.bridges.isEmpty() && !state.loading) {
                Text("暂无桥接 MCP 服务，点击右下角「添加桥接」开始。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.bridges, key = { it.id }) { bridge ->
                    BridgeCard(
                        bridge = bridge,
                        error = state.errors[bridge.id],
                        toolCount = ToolKit.bridgeTools.count { it.bridgeId == bridge.id },
                        onToggleEnabled = {
                            // 卡住修复：只做本地开关切换与缓存重建，不立即触发需要网络握手
                            // 的 doRefresh（开关状态不依赖远端），避免刷新风暴
                            val updated = state.bridges.map { b ->
                                if (b.id == bridge.id) b.withEnabled(!b.enabled) else b
                            }
                            BridgeManager.save(context, updated)
                            state = state.copy(bridges = updated)
                            ToolKit.rebuildBridgeTools()
                            debouncedRefresh()
                        },
                        onEdit = {
                            BridgeManager.load(context) // 刷新前缀分配计数器，保证自动命名不重复
                            editing = bridge
                            showEditDialog = true
                        },
                        onDelete = { showDeleteConfirm = bridge },
                        onManageTools = {
                            val intent = android.content.Intent(context, BridgeToolsActivity::class.java)
                                .putExtra("bridgeId", bridge.id)
                            context.startActivity(intent)
                        },
                    )
                }
            }
        }
    }

    // 编辑 / 添加弹窗
    if (showEditDialog) {
        BridgeEditDialog(
            initial = editing,
            onDismiss = { showEditDialog = false },
            onSave = { cfg ->
                // 保存后先立即关闭弹窗并本地刷新列表，远端工具拉取在后台进行，
                // 避免保存按钮因网络握手（超时可达 15s）而卡住
                val list = BridgeManager.load(context).toMutableList()
                val idx = list.indexOfFirst { it.id == cfg.id }
                if (idx >= 0) list[idx] = cfg else list.add(cfg)
                BridgeManager.save(context, list)
                showEditDialog = false
                refreshTick++
                scope.launch {
                    val (bridges, errors) = withContext(Dispatchers.IO) {
                        BridgeManager.refresh(McpAppCtx.app)
                    }
                    ToolKit.rebuildBridgeTools()
                    state = BridgeUiState(bridges = bridges, errors = errors, loading = false)
                }
            },
        )
    }

    // 删除确认弹窗
    showDeleteConfirm?.let { bridge ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除桥接") },
            text = { Text("确定删除桥接「${bridge.name}」吗？其下所有工具将不再可用。") },
            confirmButton = {
                TextButton(onClick = {
                    // 删除同样立即生效：本地保存 + 缓存清理，后台拉取，避免删除按钮卡住
                    val list = BridgeManager.load(context).filterNot { it.id == bridge.id }
                    BridgeManager.save(context, list)
                    BridgeManager.invalidate()
                    ToolKit.rebuildBridgeTools()
                    showDeleteConfirm = null
                    scope.launch {
                        val (bridges, errors) = withContext(Dispatchers.IO) {
                            BridgeManager.refresh(McpAppCtx.app)
                        }
                        ToolKit.rebuildBridgeTools()
                        state = BridgeUiState(bridges = bridges, errors = errors, loading = false)
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BridgeCard(
    bridge: BridgeConfig,
    error: String?,
    toolCount: Int,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onManageTools: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    tint = if (bridge.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(bridge.name, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium)
                    Text(
                        bridge.url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                // 桥接整体开关（滑块）
                Switch(checked = bridge.enabled, onCheckedChange = { onToggleEnabled() })            }
            Spacer(Modifier.height(4.dp))
            Text(
                "前缀: ${bridge.prefix.ifEmpty { "(无)" }} · 工具数: $toolCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null && bridge.enabled) {
                Text(
                    error,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onManageTools, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("管理工具")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 添加 / 编辑桥接弹窗 */
@Composable
private fun BridgeEditDialog(
    initial: BridgeConfig?,
    onDismiss: () -> Unit,
    onSave: (BridgeConfig) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var prefix by remember {
        mutableStateOf(initial?.prefix ?: BridgeManager.nextPrefix())
    }
    var urlError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加桥接" else "编辑桥接", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("如：桌面文件服务") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; urlError = null },
                    label = { Text("MCP 地址") },
                    placeholder = { Text("http://192.168.1.100:1145/mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = urlError != null,
                    supportingText = { urlError?.let { Text(it) } },
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("访问令牌（可选）") },
                    placeholder = { Text("Bearer Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("工具名前缀") },
                    placeholder = { Text("MCP1_") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "桥接服务的工具将以此前缀暴露（如 MCP1_read_file），可自行修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim().ifEmpty { url.trim().substringAfter("//").substringBefore("/").ifEmpty { "未命名" } }
                val u = url.trim()
                val p = prefix.trim()
                if (u.isEmpty() || !(u.startsWith("http://") || u.startsWith("https://"))) {
                    urlError = "请输入合法的 http(s) 地址"
                    return@TextButton
                }
                val base = BridgeConfig(
                    id = initial?.id ?: BridgeManager.newId(),
                    name = n,
                    url = u,
                    token = token.trim().ifEmpty { null },
                    prefix = p,
                    enabled = initial?.enabled ?: true,
                )
                onSave(base)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
