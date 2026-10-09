package com.m4dm4x100.offlineai

import android.app.Activity
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class ModelManagerActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var modelList: LinearLayout
    private lateinit var remoteList: LinearLayout
    private lateinit var searchInput: EditText
    private val prefs by lazy { getSharedPreferences("offlineai", MODE_PRIVATE) }
    private val modelDir by lazy { File(filesDir, "models").apply { mkdirs() } }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importModel(uri) }

    private fun dark() = prefs.getBoolean("dark_theme", false)
    private fun bg() = if (dark()) 0xFF10131A.toInt() else 0xFFF4F6FB.toInt()
    private fun surface() = if (dark()) 0xFF1B202B.toInt() else 0xFFFFFFFF.toInt()
    private fun fg() = if (dark()) 0xFFF0F3FA.toInt() else 0xFF202636.toInt()
    private fun muted() = if (dark()) 0xFFB0B8C8.toInt() else 0xFF667085.toInt()
    private fun accent() = if (dark()) 0xFF9BB8FF.toInt() else 0xFF315FE8.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 18, 20, 18); setBackgroundColor(bg()) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = "Model Manager"; textSize = 27f; setTypeface(null, 1); setTextColor(fg())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(if (dark()) "☀ Light" else "☾ Dark") { prefs.edit().putBoolean("dark_theme", !dark()).apply(); recreate() })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Search Hugging Face and download compatible models directly to your device."
            textSize = 14f; setTextColor(muted()); setPadding(0, 8, 0, 10)
        })
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchInput = EditText(this).apply {
            hint = "Search models (e.g. Gemma 1B)"; singleLine = true; textSize = 15f
            setTextColor(fg()); setHintTextColor(muted()); setBackgroundColor(surface()); setPadding(12, 4, 12, 4)
            setText("litert-community Gemma")
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, _ -> if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { searchModels(); true } else false }
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, 52, 1f))
        searchRow.addView(button("Search") { searchModels() })
        root.addView(searchRow)
        root.addView(TextView(this).apply {
            text = "Only .task files are offered. GGUF and other formats are not compatible with this app's current MediaPipe runtime."
            textSize = 12f; setTextColor(muted()); setPadding(0, 6, 0, 8)
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, 8))
        status = TextView(this).apply { textSize = 13f; setTextColor(accent()); setPadding(0, 6, 0, 8) }
        root.addView(status)
        val scroll = ScrollView(this)
        val sections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sections.addView(sectionTitle("Hugging Face search results"))
        remoteList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sections.addView(remoteList)
        sections.addView(sectionTitle("Downloaded models"))
        modelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sections.addView(modelList)
        sections.addView(button("Import a .task file from device") { picker.launch(arrayOf("application/octet-stream", "*/*")) })
        scroll.addView(sections)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("Done") { setResult(Activity.RESULT_OK); finish() })
        setContentView(root)
        refreshModels()
        searchModels()
    }

    private fun sectionTitle(s: String) = TextView(this).apply { text = s; textSize = 18f; setTypeface(null, 1); setTextColor(fg()); setPadding(0, 14, 0, 8) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        setTextColor(if (dark()) 0xFF10131A.toInt() else 0xFFFFFFFF.toInt())
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent()))
        setOnClickListener { action() }
    }
    private fun info(s: String) = TextView(this).apply { text = s; textSize = 13f; setTextColor(muted()); setPadding(4, 8, 4, 8) }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12); setBackgroundColor(surface()); elevation = 2f }
    private fun addCard(parent: LinearLayout, view: View, at: Int = parent.childCount) {
        val p = LinearLayout.LayoutParams(-1, -2); p.bottomMargin = 10
        parent.addView(view, at.coerceIn(0, parent.childCount), p)
    }

    private fun searchModels() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) { status.text = "Enter a model name or keyword."; return }
        remoteList.removeAllViews()
        status.text = "Searching Hugging Face…"
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { JSONArray(httpGet("https://huggingface.co/api/models?search=" + enc(query) + "&limit=20&full=true")) }
                remoteList.removeAllViews()
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val id = item.optString("id", item.optString("modelId", ""))
                    if (id.isBlank()) continue
                    val c = card()
                    c.addView(TextView(this@ModelManagerActivity).apply { text = id; textSize = 15f; setTypeface(null, 1); setTextColor(fg()) })
                    c.addView(info("${item.optLong("downloads")} downloads  •  ${item.optInt("likes")} likes"))
                    c.addView(button("Find .task files") { loadFiles(id) })
                    addCard(remoteList, c)
                }
                if (remoteList.childCount == 0) remoteList.addView(info("No matching repositories found. Try another search."))
                status.text = "Found ${remoteList.childCount} repositories. Select a result to find downloadable .task files."
            } catch (e: Exception) {
                status.text = "Search failed: ${e.localizedMessage ?: e.javaClass.simpleName}. Check your internet connection."
                remoteList.addView(info("Could not reach Hugging Face. You can still import a local .task file."))
            }
        }
    }

    private fun loadFiles(repo: String) {
        status.text = "Checking files in $repo…"
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val id = repo.split("/").joinToString("/") { enc(it) }
                    val siblings = JSONObject(httpGet("https://huggingface.co/api/models/$id?files=true")).optJSONArray("siblings") ?: JSONArray()
                    (0 until siblings.length()).mapNotNull { i ->
                        val f = siblings.optJSONObject(i) ?: return@mapNotNull null
                        val name = f.optString("rfilename", "")
                        if (name.endsWith(".task", true)) name to f.optLong("size", f.optLong("size_on_disk", 0L)) else null
                    }
                }
                val c = card()
                c.addView(TextView(this@ModelManagerActivity).apply { text = repo; textSize = 15f; setTypeface(null, 1); setTextColor(fg()) })
                if (files.isEmpty()) c.addView(info("No .task files found in this repository. Try searching for MediaPipe or LiteRT .task models."))
                files.forEach { (name, size) ->
                    c.addView(info(name + if (size > 0) "  •  ${bytes(size)}" else ""))
                    c.addView(button("Download .task") { download(repo, name, size) })
                }
                addCard(remoteList, c, 0)
                status.text = if (files.isEmpty()) "No .task files found in $repo." else "Found ${files.size} .task file(s)."
            } catch (e: Exception) { status.text = "Could not list files: ${e.localizedMessage ?: e.javaClass.simpleName}" }
        }
    }

    private fun download(repo: String, path: String, expected: Long) {
        val prefix = repo.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileName = path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        var dest = File(modelDir, "$prefix-$fileName")
        var n = 2
        while (dest.exists()) { dest = File(modelDir, "$prefix-${fileName.removeSuffix(".task")}-$n.task"); n++ }
        val available = StatFs(filesDir.absolutePath).availableBytes
        if (expected > 0 && expected + 64L * 1024 * 1024 > available) { status.text = "Not enough free storage. Need ${bytes(expected)} plus working space."; return }
        progress.visibility = View.VISIBLE; progress.isIndeterminate = expected <= 0; progress.progress = 0
        lifecycleScope.launch {
            val temp = File(modelDir, dest.name + ".part")
            try {
                withContext(Dispatchers.IO) {
                    val id = repo.split("/").joinToString("/") { enc(it) }
                    val p = path.split("/").joinToString("/") { enc(it) }
                    val conn = (URL("https://huggingface.co/$id/resolve/main/$p?download=true").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "OfflineAI-Android/1.0")
                    }
                    try {
                        if (conn.responseCode !in 200..299) error("Hugging Face returned HTTP ${conn.responseCode}")
                        val total = conn.contentLengthLong.takeIf { it > 0 } ?: expected
                        conn.inputStream.use { input ->
                            FileOutputStream(temp).use { output ->
                                val buffer = ByteArray(256 * 1024); var copied = 0L
                                while (true) {
                                    val count = input.read(buffer); if (count < 0) break
                                    output.write(buffer, 0, count); copied += count
                                    if (total > 0) withContext(Dispatchers.Main) {
                                        progress.isIndeterminate = false
                                        progress.progress = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                                        status.text = "Downloading $fileName… ${progress.progress}% (${bytes(copied)} / ${bytes(total)})"
                                    }
                                }
                                output.fd.sync()
                            }
                        }
                    } finally { conn.disconnect() }
                }
                if (!temp.isFile || temp.length() == 0L) error("Downloaded file is empty")
                if (!temp.renameTo(dest)) { temp.copyTo(dest, overwrite = true); temp.delete() }
                prefs.edit().putString("active_model", dest.name).apply()
                status.text = "Downloaded ${dest.name} (${bytes(dest.length())}) and selected it. Tap Done to load it."
                setResult(Activity.RESULT_OK); refreshModels()
            } catch (e: Exception) {
                temp.delete(); status.text = "Download failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally { progress.visibility = View.GONE }
        }
    }

    private fun httpGet(address: String): String {
        val conn = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000; requestMethod = "GET"
            setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "OfflineAI-Android/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun bytes(n: Long) = when {
        n >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024))
        n >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", n / (1024.0 * 1024))
        n >= 1024 -> String.format(Locale.US, "%.0f KB", n / 1024.0)
        else -> "$n B"
    }

    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { displayName(uri) }
                if (!name.endsWith(".task", true)) { status.text = "Choose a MediaPipe .task file."; return@launch }
                val size = withContext(Dispatchers.IO) { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
                if (size > 0 && size + 64L * 1024 * 1024 > StatFs(filesDir.absolutePath).availableBytes) {
                    status.text = "Not enough free storage for this model."; return@launch
                }
                var safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                var dest = File(modelDir, safe); var n = 2
                while (dest.exists()) { dest = File(modelDir, safe.removeSuffix(".task") + "-$n.task"); n++ }
                progress.visibility = View.VISIBLE; progress.isIndeterminate = size <= 0
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { output ->
                            val buf = ByteArray(1024 * 1024); var copied = 0L
                            while (true) {
                                val count = input.read(buf); if (count < 0) break
                                output.write(buf, 0, count); copied += count
                                if (size > 0) withContext(Dispatchers.Main) {
                                    progress.isIndeterminate = false; progress.progress = ((copied * 100) / size).toInt().coerceIn(0, 100)
                                    status.text = "Importing… ${progress.progress}%"
                                }
                            }
                            output.fd.sync()
                        }
                    } ?: error("Cannot open selected file")
                }
                prefs.edit().putString("active_model", dest.name).apply()
                status.text = "Imported ${dest.name} and selected it."
                setResult(Activity.RESULT_OK); refreshModels()
            } catch (e: Exception) { status.text = "Import failed: ${e.localizedMessage ?: e.javaClass.simpleName}" }
            finally { progress.visibility = View.GONE }
        }
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.task"
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        return name
    }

    private fun refreshModels() {
        modelList.removeAllViews()
        val files = modelDir.listFiles { f -> f.isFile && f.extension.equals("task", true) }?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        val active = prefs.getString("active_model", null)
        if (files.isEmpty()) { modelList.addView(info("No downloaded models yet. Search Hugging Face above or import a .task file.")); return }
        files.forEach { file ->
            val selected = file.name == active
            val c = card()
            c.addView(TextView(this).apply { text = (if (selected) "✓ ACTIVE • " else "") + file.name; textSize = 15f; setTypeface(null, 1); setTextColor(if (selected) accent() else fg()) })
            c.addView(info("${bytes(file.length())} • ${if (selected) "Selected for chat" else "Stored locally"}"))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (!selected) row.addView(button("Use model") {
                prefs.edit().putString("active_model", file.name).apply()
                status.text = "Selected ${file.name}. Tap Done to load it."; setResult(Activity.RESULT_OK); refreshModels()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("Delete") {
                if (file.name == prefs.getString("active_model", null)) prefs.edit().remove("active_model").apply()
                file.delete(); status.text = "Deleted ${file.name}"; setResult(Activity.RESULT_OK); refreshModels()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            c.addView(row); addCard(modelList, c)
        }
    }
}
