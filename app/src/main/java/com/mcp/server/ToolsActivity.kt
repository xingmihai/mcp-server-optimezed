package com.mcp.server

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcp.server.tools.ToolDefinition
import com.mcp.server.tools.ToolKit
import com.mcp.server.ui.theme.McpTheme
import org.json.JSONArray
import org.json.JSONObject

class ToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            McpTheme {
                ToolsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit = {}) {
    // 强制重组刷新（切换开关后）
    var refresh by remember { mutableStateOf(0) }
    val groups = remember(refresh) { ToolKit.groupedTools() }
    var selected by remember { mutableStateOf<ToolDefinition?>(null) }
    val bridgeTools = remember(refresh) { ToolKit.bridgeTools }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具列表", fontWeight = FontWeight.SemiBold) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            groups.forEach { (category, tools) ->
                item(key = "header_$category") {
                    Text(
                        category,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                items(tools, key = { it.name }) { tool ->
                    ToolRow(
                        tool = tool,
                        enabled = ToolKit.isEnabled(tool.name),
                        onToggle = {
                            ToolKit.setEnabled(tool.name, !ToolKit.isEnabled(tool.name))
                            refresh++
                        },
                        onInfo = { selected = tool },
                    )
                }
            }
            // 桥接工具分类
            if (ToolKit.bridgeLoaded && bridgeTools.isNotEmpty()) {
                item(key = "header_bridge") {
                    Text(
                        "桥接工具",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                items(bridgeTools, key = { it.id }) { tool ->
                    BridgeToolToggleRow(
                        tool = tool,
                        enabled = ToolKit.isBridgeEnabled(tool.id),
                        onToggle = {
                            ToolKit.setBridgeEnabled(tool.id, !ToolKit.isBridgeEnabled(tool.id))
                            refresh++
                        },
                    )
                }
            }
        }
    }

    // 工具详情弹窗
    selected?.let { tool ->
        ToolDetailDialog(tool = tool, onDismiss = { selected = null })
    }
}

@Composable
private fun ToolRow(
    tool: ToolDefinition,
    enabled: Boolean,
    onToggle: () -> Unit,
    onInfo: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onInfo)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tool.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(10.dp))
            // 圆圈开关按钮
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

@Composable
private fun BridgeToolToggleRow(
    tool: com.mcp.server.bridge.BridgeToolInfo,
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

@Composable
private fun ToolDetailDialog(tool: ToolDefinition, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tool.name, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "分类：${tool.category}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(6.dp))
                Text("功能描述", style = MaterialTheme.typography.titleSmall)
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(8.dp))
                Text("参数说明", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.padding(4.dp))
                val schema = tool.inputSchema
                val props = schema.optJSONObject("properties")
                val required = schema.optJSONArray("required")
                if (props != null && props.length() > 0) {
                    props.keys().forEach { key ->
                        val p = props.optJSONObject(key) ?: return@forEach
                        val type = p.optString("type", "any")
                        val req = required?.let { contains(it, key) } == true
                        Text(
                            "• $key (${type})${if (req) " *必填*" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                } else {
                    Text("无参数", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.padding(8.dp))
                Text(
                    "当前状态：${if (ToolKit.isEnabled(tool.name)) "已开启" else "已关闭"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (ToolKit.isEnabled(tool.name)) Color(0xFF2E7D32) else Color(0xFFC62828),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

private fun contains(arr: JSONArray?, key: String): Boolean {
    if (arr == null) return false
    for (i in 0 until arr.length()) {
        if (arr.optString(i) == key) return true
    }
    return false
}
