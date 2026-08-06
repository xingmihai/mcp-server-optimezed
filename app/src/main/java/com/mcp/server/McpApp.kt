package com.mcp.server

import android.app.Application
import com.mcp.server.bridge.BridgeManager
import com.mcp.server.server.McpAppCtx
import com.mcp.server.tools.ToolKit

class McpApp : Application() {
    companion object {
        lateinit var instance: McpApp
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        McpAppCtx.app = this
        // 启动时载入桥接配置，使自动前缀分配能感知列表中已有的命名
        BridgeManager.load(this)
        ToolKit.init(this)
    }
}
