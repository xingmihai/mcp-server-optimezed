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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.mcp.server.bridge.BridgeToolInfo
import com.mcp.server.server.McpAppCtx
import com.mcp.server.tools.ToolKit
import com.mcp.server.ui.theme.McpTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个桥接服务的工具管理页：
 * 显示该桥接的所有工具，可单独控制每个工具的开关。
 */
class BridgeToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridgeId = intent.getStringExtra("bridgeId") ?: run {
            finish()
            return
        }
        setContent {
            McpTheme {
                BridgeToolsScreen(bridgeId = bridgeId, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeToolsScreen(bridgeId: String, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val bridge: BridgeConfig? = remember(refresh) {
        BridgeManager.load(context).find { it.id == bridgeId }
    }
    val tools: List<BridgeToolInfo> = remember(refresh) {
        ToolKit.bridgeTools.filter { it.bridgeId == bridgeId }
    }

    // 进入时若还没拉取过桥接工具，尝试拉取一次
    LaunchedEffect(Unit) {
        if (!ToolKit.bridgeLoaded) {
            loading = true
            val (_, errors) = withContext(Dispatchers.IO) {
                BridgeManager.refresh(McpAppCtx.app)
            }
            ToolKit.rebuildBridgeTools()
            loading = false
            error = errors[bridgeId]
            refresh++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bridge?.name ?: "桥接工具", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            loading = true
                            val (_, errors) = withContext(Dispatchers.IO) {
                                BridgeManager.refresh(McpAppCtx.app)
                            }
                            ToolKit.rebuildBridgeTools()
                            loading = false
                            error = errors[bridgeId]
                            refresh++
                        }
                    }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "前缀: ${bridge?.prefix ?: ""} · 共 ${tools.size} 个工具",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Text(
                    "连接失败: $it（点击右上角刷新重试）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (loading) {
                Text("正在拉取工具...", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (tools.isEmpty() && !loading) {
                Text(
                    if (bridge == null) "桥接不存在或已删除"
                    else if (error != null) "工具拉取失败，请检查桥接地址/令牌后重试"
                    else "该桥接暂无工具",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tools, key = { it.id }) { tool ->
                        ToolSwitchCard(
                            tool = tool,
                            enabled = ToolKit.isBridgeEnabled(tool.id),
                            onToggle = {
                                ToolKit.setBridgeEnabled(tool.id, !ToolKit.isBridgeEnabled(tool.id))
                                ToolKit.rebuildBridgeTools()
                                refresh++
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolSwitchCard(
    tool: BridgeToolInfo,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tool.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(10.dp))
            // 圆圈开关按钮（与工具列表页一致）
            IconButton(onClick = onToggle) {
                Icon(
                    if (enabled) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (enabled) "关闭工具" else "开启工具",
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
