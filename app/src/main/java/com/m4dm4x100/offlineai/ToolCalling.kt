package com.m4dm4x100.offlineai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Allow-listed tools only: no shell, arbitrary intents, or unrestricted filesystem access. */
class ToolRuntime(private val context: Context) {
    private val prefs = context.getSharedPreferences("tool_runtime", Context.MODE_PRIVATE)
    private val notesDir = File(context.filesDir, "notes").apply { mkdirs() }
    private val memoryFile = File(context.filesDir, "personal_memory.txt")

    fun onlineEnabled() = prefs.getBoolean("online_enabled", true)
    fun mcpEndpoint() = prefs.getString("mcp_endpoint", "")?.trim().orEmpty()
    fun isTrusted(name: String) = prefs.getBoolean("trusted_$name", true)
    fun setOnlineEnabled(enabled: Boolean) { prefs.edit().putBoolean("online_enabled", enabled).apply() }
    fun setMcpEndpoint(endpoint: String) { prefs.edit().putString("mcp_endpoint", endpoint.trim()).apply() }
    fun setTrusted(name: String, enabled: Boolean) { prefs.edit().putBoolean("trusted_$name", enabled).apply() }

    fun toolsPrompt(): String = """
        You are the tool router for OfflineAI on Android. Return exactly one JSON object, no markdown.
        For ordinary conversation return {"tool":"none","answer":"your answer"}.
        To use a tool return {"tool":"registered_name","arguments":{...}}.
        Use tools only when useful; never invent tool results. Tool calls are automatically executed only
        for tools enabled in trusted-tools settings. If a tool is unavailable, answer without it.
        Local tools:
        - notes_list: no arguments; list saved note filenames
        - notes_read: {"name":"filename"}; read a saved note
        - notes_write: {"name":"filename","content":"text"}; save/update a note
        - memory_get: no arguments; read locally stored personal memory
        - memory_save: {"content":"text"}; only when the user explicitly asks to remember a fact
        - android_open_settings: {"page":"wifi|bluetooth|app"}
        - android_open_url: {"url":"https://..."}; only HTTP(S) URLs
        - android_share: {"text":"..."}; open Android share sheet
    """.trimIndent() + if (onlineEnabled()) "\n- web_search: {\"query\":\"...\"}; read-only Wikipedia search" else "" +
        if (mcpEndpoint().isNotBlank()) "\n- mcp_list_tools: no arguments\n- mcp_call: {\"name\":\"server tool name\",\"arguments\":{...}}; only use listed and trusted MCP tools" else ""

    fun execute(name: String, args: JSONObject): String {
        if (!isTrusted(name)) return "Tool '$name' is disabled in trusted-tools settings."
        return try {
            when (name) {
                "notes_list" -> notesDir.listFiles()?.filter { it.isFile }?.joinToString("\n") { it.name }?.ifBlank { "No notes saved." } ?: "No notes saved."
                "notes_read" -> {
                    val file = noteFile(args.optString("name"))
                    if (!file.isFile) "Note not found." else file.readText().take(20000)
                }
                "notes_write" -> {
                    val file = noteFile(args.optString("name"))
                    val text = args.optString("content")
                    require(text.length <= 20000) { "Note is too long (20,000 characters maximum)." }
                    file.writeText(text)
                    "Saved note '${file.name}' (${text.length} characters)."
                }
                "memory_get" -> if (memoryFile.exists()) memoryFile.readText().take(12000) else "No personal memory saved on this device."
                "memory_save" -> {
                    val text = args.optString("content").trim()
                    require(text.isNotBlank() && text.length <= 2000) { "Memory must be 1–2,000 characters." }
                    memoryFile.appendText((if (memoryFile.exists() && memoryFile.length() > 0) "\n" else "") + "- " + text)
                    "Saved to this device's personal memory."
                }
                "android_open_settings" -> openSettings(args.optString("page"))
                "android_open_url" -> openUrl(args.optString("url"))
                "android_share" -> share(args.optString("text"))
                "web_search" -> if (onlineEnabled()) webSearch(args.optString("query")) else "Online tools are disabled."
                "mcp_list_tools" -> listMcpTools()
                "mcp_call" -> callMcp(args.optString("name"), args.optJSONObject("arguments") ?: JSONObject())
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) { "Tool error: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun noteFile(raw: String): File {
        val safe = raw.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
        require(safe.isNotBlank() && safe != "." && safe != "..") { "Choose a valid note filename." }
        return File(notesDir, if (safe.endsWith(".txt", true)) safe else "$safe.txt").canonicalFile.also {
            require(it.parentFile == notesDir.canonicalFile) { "Invalid note path." }
        }
    }

    private fun openSettings(page: String): String {
        val action = when (page.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "app" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> return "Unknown settings page. Use wifi, bluetooth, or app."
        }
        val intent = if (page.lowercase() == "app") Intent(action, Uri.parse("package:${context.packageName}")) else Intent(action)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opened Android $page settings."
    }

    private fun openUrl(raw: String): String {
        val uri = Uri.parse(raw.trim())
        require(uri.scheme == "https" || uri.scheme == "http") { "Only http/https links are allowed." }
        require(!uri.host.isNullOrBlank()) { "URL must include a host." }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened $uri in the browser."
    }

    private fun share(text: String): String {
        require(text.isNotBlank() && text.length <= 10000) { "Share text must be 1–10,000 characters." }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened Android share sheet."
    }

    private fun webSearch(query: String): String {
        require(query.isNotBlank() && query.length <= 300) { "Enter a short search query." }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val conn = URL("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&utf8=1&srlimit=5").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "OfflineAI-Android/1.0")
        return try {
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val results = json.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
            if (results.length() == 0) "No Wikipedia search results found for: $query"
            else buildString {
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    append("- ").append(item.optString("title")).append(": ")
                        .append(android.text.Html.fromHtml(item.optString("snippet"), android.text.Html.FROM_HTML_MODE_LEGACY).toString())
                        .append("\nhttps://en.wikipedia.org/wiki/")
                        .append(URLEncoder.encode(item.optString("title").replace(' ', '_'), "UTF-8")).append("\n")
                }
            }.take(12000)
        } finally { conn.disconnect() }
    }

    private fun mcpRequest(method: String, params: JSONObject = JSONObject()): JSONObject {
        require(onlineEnabled()) { "Online tools are disabled." }
        val endpoint = mcpEndpoint()
        require(endpoint.isNotBlank()) { "Configure an MCP server endpoint in Tools first." }
        val uri = Uri.parse(endpoint)
        require(uri.scheme == "https" || uri.scheme == "http") { "MCP endpoint must use http/https." }
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", System.currentTimeMillis()).put("method", method).put("params", params)
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        return try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonLine = body.lineSequence().lastOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: body
            JSONObject(jsonLine).also { response ->
                if (response.has("error")) throw IllegalStateException(response.optJSONObject("error")?.optString("message") ?: "MCP error")
            }
        } finally { conn.disconnect() }
    }

    private fun listMcpTools(): String {
        val response = mcpRequest("tools/list")
        val tools = response.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        if (tools.length() == 0) return "MCP server returned no tools."
        return buildString {
            for (i in 0 until tools.length()) {
                val item = tools.getJSONObject(i)
                append(item.optString("name")).append(": ").append(item.optString("description")).append("\n")
            }
        }.take(10000)
    }

    private fun callMcp(name: String, args: JSONObject): String {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid MCP tool name." }
        val listed = mcpRequest("tools/list").optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        if ((0 until listed.length()).none { listed.getJSONObject(it).optString("name") == name }) {
            return "That tool was not listed by the configured MCP server."
        }
        require(isTrusted("mcp_$name")) { "MCP tool '$name' is not enabled in trusted-tools settings." }
        val response = mcpRequest("tools/call", JSONObject().put("name", name).put("arguments", args))
        return response.optJSONObject("result")?.toString()?.take(12000) ?: "MCP tool returned no result."
    }
}

data class ToolDecision(val name: String, val arguments: JSONObject, val answer: String?)

fun parseToolDecision(raw: String): ToolDecision? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return try {
        val json = JSONObject(raw.substring(start, end + 1))
        ToolDecision(json.optString("tool", "none"), json.optJSONObject("arguments") ?: JSONObject(), json.optString("answer").takeIf { it.isNotBlank() })
    } catch (_: Exception) { null }
}
