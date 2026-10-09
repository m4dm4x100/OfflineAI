package com.m4dm4x100.offlineai

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ModelManagerActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var modelList: LinearLayout
    private val prefs by lazy { getSharedPreferences("offlineai", MODE_PRIVATE) }
    private val modelDir by lazy { File(filesDir, "models").apply { mkdirs() } }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    private fun dark() = prefs.getBoolean("dark_theme", false)
    private fun bg() = if (dark()) 0xFF10131A.toInt() else 0xFFF4F6FB.toInt()
    private fun surface() = if (dark()) 0xFF1B202B.toInt() else 0xFFFFFFFF.toInt()
    private fun fg() = if (dark()) 0xFFF0F3FA.toInt() else 0xFF202636.toInt()
    private fun muted() = if (dark()) 0xFFB0B8C8.toInt() else 0xFF667085.toInt()
    private fun accent() = if (dark()) 0xFF9BB8FF.toInt() else 0xFF315FE8.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(bg())
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = "Your models"
            textSize = 27f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(fg())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(if (dark()) "☀ Light" else "☾ Dark") {
            prefs.edit().putBoolean("dark_theme", !dark()).apply()
            recreate()
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Keep models on your device and switch between them. Model files are large and stay in private app storage."
            textSize = 14f; setTextColor(muted()); setPadding(0, 8, 0, 14)
        })
        root.addView(button("Import a .task model") { picker.launch(arrayOf("application/octet-stream", "*/*")) })
        root.addView(button("Get compatible Gemma model") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/litert-community/Gemma3-1B-IT")))
        })
        root.addView(TextView(this).apply {
            text = "Official source: Gemma 3 1B (MediaPipe-compatible variants). Accept the model licence on Hugging Face, download a compatible .task file, then import it here. GGUF and .litertlm files are not accepted by this app's current MediaPipe runtime."
            textSize = 13f; setTextColor(muted()); setPadding(0, 10, 0, 12)
        })
        status = TextView(this).apply { textSize = 13f; setTextColor(accent()); setPadding(0, 6, 0, 8) }
        root.addView(status)
        val scroll = ScrollView(this)
        modelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(modelList)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("Done") { setResult(Activity.RESULT_OK); finish() })
        setContentView(root)
        refreshModels()
        root.alpha = 0f
        root.translationY = 16f
        root.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        setTextColor(if (dark()) 0xFF10131A.toInt() else 0xFFFFFFFF.toInt())
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent()))
        setOnClickListener { action() }
    }

    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { displayName(uri) }
                if (!name.lowercase(Locale.ROOT).endsWith(".task")) {
                    status.text = "Choose a MediaPipe .task file. Other formats are not supported by this runtime."
                    return@launch
                }
                val available = StatFs(filesDir.absolutePath).availableBytes
                val size = withContext(Dispatchers.IO) { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
                if (size > 0 && size + 64L * 1024 * 1024 > available) {
                    status.text = "Not enough free storage. Need about ${size / (1024 * 1024)} MB plus working space."
                    return@launch
                }
                var destName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (!destName.lowercase(Locale.ROOT).endsWith(".task")) destName += ".task"
                var dest = File(modelDir, destName)
                var n = 2
                while (dest.exists()) { dest = File(modelDir, destName.removeSuffix(".task") + "-$n.task"); n++ }
                status.text = "Importing model…"
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri) ?: error("Cannot open selected file")
                    input.use { source ->
                        FileOutputStream(dest).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                }
                prefs.edit().putString("active_model", dest.name).apply()
                status.text = "Imported ${dest.name} (${dest.length() / (1024 * 1024)} MB). Selected for chat."
                setResult(Activity.RESULT_OK)
                refreshModels()
            } catch (e: Exception) {
                status.text = "Import failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
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
        val files = modelDir.listFiles { file -> file.isFile && file.extension.equals("task", true) }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        val active = prefs.getString("active_model", null)
        if (files.isEmpty()) {
            modelList.addView(TextView(this).apply {
                text = "No models installed yet. Download a compatible .task model from the official source, then import it."
                textSize = 15f; setTextColor(fg()); setPadding(8, 20, 8, 20)
            })
            status.text = "No local model selected"
            return
        }
        files.forEach { file ->
            val selected = file.name == active
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                setBackgroundColor(surface())
                elevation = 2f
            }
            card.addView(TextView(this).apply {
                text = (if (selected) "✓  ACTIVE  •  " else "") + file.name
                textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(if (selected) accent() else fg())
            })
            card.addView(TextView(this).apply {
                text = "${file.length() / (1024 * 1024)} MB  •  ${if (selected) "Used by chat" else "Stored locally"}"
                textSize = 13f; setTextColor(muted()); setPadding(0, 4, 0, 8)
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (!selected) actions.addView(button("Use model") {
                prefs.edit().putString("active_model", file.name).apply()
                status.text = "Selected ${file.name}. Tap Done to load it for chat."
                setResult(Activity.RESULT_OK)
                refreshModels()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(button("Delete") {
                if (file.name == prefs.getString("active_model", null)) {
                    prefs.edit().remove("active_model").apply()
                }
                file.delete()
                status.text = "Deleted ${file.name}"
                setResult(Activity.RESULT_OK)
                refreshModels()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(actions)
            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = 12
            modelList.addView(card, params)
            card.alpha = 0f
            card.translationY = 12f
            card.animate().alpha(1f).translationY(0f).setDuration(220).setStartDelay((modelList.childCount * 35L)).start()
        }
    }
}
