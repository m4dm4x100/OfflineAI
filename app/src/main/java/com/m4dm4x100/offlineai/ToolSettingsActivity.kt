package com.m4dm4x100.offlineai

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ToolSettingsActivity : Activity() {
    private lateinit var runtime: ToolRuntime
    private lateinit var endpoint: EditText
    private lateinit var mcpNames: EditText
    private lateinit var online: CheckBox
    private val checks = mutableMapOf<String, CheckBox>()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = ToolRuntime(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "Tool calling"
            textSize = 26f
            setTextColor(0xFF202124.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Gemma can route requests to these allow-listed tools. Selected tools run automatically; no shell or unrestricted file access is exposed."
            textSize = 14f
            setPadding(0, 8, 0, 16)
        })
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        online = CheckBox(this).apply {
            text = "Enable online tools (web search and configured MCP)"
            isChecked = runtime.onlineEnabled()
        }
        content.addView(online)
        content.addView(TextView(this).apply { text = "Trusted tools"; textSize = 19f; setPadding(0, 14, 0, 4) })
        addTool(content, "notes_list", "List notes", true)
        addTool(content, "notes_read", "Read notes", true)
        addTool(content, "notes_write", "Write notes in app-private storage", true)
        addTool(content, "memory_get", "Read local personal memory", true)
        addTool(content, "memory_save", "Save personal memory (only when explicitly requested)", true)
        addTool(content, "android_open_settings", "Open Wi-Fi, Bluetooth, or app settings", true)
        addTool(content, "android_open_url", "Open HTTP(S) links in browser", true)
        addTool(content, "android_share", "Share text using Android share sheet", true)
        addTool(content, "web_search", "Search Wikipedia (online, read-only)", true)
        content.addView(TextView(this).apply { text = "MCP server (optional)"; textSize = 19f; setPadding(0, 18, 0, 4) })
        endpoint = EditText(this).apply {
            hint = "https://your-mcp-server.example/mcp"
            setSingleLine(true)
            setText(runtime.mcpEndpoint())
        }
        content.addView(endpoint)
        content.addView(TextView(this).apply {
            text = "MCP tool names allowed to run automatically (comma-separated). Leave blank to allow none. Discover names from your server first."
            textSize = 13f
            setPadding(0, 4, 0, 4)
        })
        mcpNames = EditText(this).apply {
            hint = "e.g. search_docs, read_calendar"
            setText(runtime.mcpTrustedNames().joinToString(", "))
        }
        content.addView(mcpNames)
        content.addView(Button(this).apply {
            text = "Discover MCP tools"
            setOnClickListener {
                saveSettings()
                status.text = "Contacting MCP server…"
                lifecycleScope.launch {
                    status.text = withContext(Dispatchers.IO) { runtime.execute("mcp_list_tools", org.json.JSONObject()) }
                }
            }
        })
        content.addView(Button(this).apply {
            text = "Save tool settings"
            setOnClickListener { saveSettings(); status.text = "Saved. Selected trusted tools can run automatically." }
        })
        status = TextView(this).apply { text = ""; textSize = 13f; setPadding(0, 10, 0, 10) }
        content.addView(status)
        content.addView(TextView(this).apply {
            text = "Privacy and safety: notes and memory stay in app-private storage. Web search sends the query to Wikipedia. MCP tools send arguments to the endpoint you configure; only allow servers you trust. Android actions are limited to opening settings, opening a web link, and the share sheet."
            textSize = 13f
            setPadding(0, 16, 0, 8)
        })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun addTool(parent: LinearLayout, key: String, label: String, defaultValue: Boolean) {
        val check = CheckBox(this).apply {
            text = label
            isChecked = runtime.isTrusted(key)
        }
        checks[key] = check
        parent.addView(check)
    }

    private fun saveSettings() {
        runtime.setOnlineEnabled(online.isChecked)
        runtime.setMcpEndpoint(endpoint.text.toString())
        checks.forEach { (key, check) -> runtime.setTrusted(key, check.isChecked) }
        val names = mcpNames.text.toString().split(',').map { it.trim() }.filter { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }.toSet()
        // MCP tools are opt-in; unlisted names are explicitly disabled.
        runtime.setMcpTrustedNames(names)
        status.text = if (names.isEmpty()) "Saved. No MCP tools are enabled for automatic execution." else "Saved. Enabled MCP tools: " + names.joinToString()
    }
}
