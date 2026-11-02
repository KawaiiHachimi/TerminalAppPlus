/* SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.appearance

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppearanceState(
    val mode: String = "system", val automatic: Boolean = true,
    val light: String = "standard_light", val dark: String = "standard_dark", val fixed: String = "standard_dark",
    val themes: List<TerminalTheme> = listOf(TerminalTheme.standard(false), TerminalTheme.standard(true)),
    val fontId: String = "", val fontName: String = "", val fontData: String? = null, val fontSize: Int = 13, val fontReady: Boolean = false,
) {
    fun isDark(systemDark: Boolean) = when (mode) { "light" -> false; "dark" -> true; else -> systemDark }
    fun theme(systemDark: Boolean): TerminalTheme {
        val darkMode = isDark(systemDark)
        val id = if (automatic) { if (darkMode) dark else light } else fixed
        return themes.find { it.id == id } ?: TerminalTheme.standard(!darkMode)
    }
}

/** App-owned settings; file/font IO stays off the UI thread. No Guest changes. */
class AppearanceRepository private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("terminal_appearance", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(readSettings(AppearanceState()))
    val state = mutable.asStateFlow()
    @Volatile var typeface: Typeface = Typeface.MONOSPACE
        private set
    private val themeDir = File(context.filesDir, "themes")
    private val fontDir = File(context.filesDir, "terminal-fonts")
    private fun fontFile(id: String): File? = id.takeIf { it.matches(Regex("[0-9a-f]{32}")) }?.let { File(fontDir, "$it.ttf") }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val before = mutable.value
        val next = readSettings(before)
        mutable.value = if (before.fontId == next.fontId) next else next.copy(fontData = null, fontReady = false)
        if (before.fontId != next.fontId) loadFont()
    }
    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        scope.launch { reloadThemes() }
        loadFont()
    }
    private fun readSettings(previous: AppearanceState) = previous.copy(
        mode = prefs.getString("mode", "system") ?: "system", automatic = prefs.getBoolean("automatic", true),
        light = prefs.getString("light", "standard_light") ?: "standard_light",
        dark = prefs.getString("dark", "standard_dark") ?: "standard_dark", fixed = prefs.getString("fixed", "standard_dark") ?: "standard_dark",
        fontId = prefs.getString("font_id", "") ?: "", fontName = prefs.getString("font_name", "") ?: "",
        fontSize = prefs.getInt("font_size", 13).coerceIn(5, 200),
    )
    fun setMode(value: String) { require(value in listOf("system", "light", "dark")); prefs.edit().putString("mode", value).apply() }
    fun setAutomatic(value: Boolean) { prefs.edit().putBoolean("automatic", value).apply() }
    fun select(key: String, id: String) { require(key in listOf("light", "dark", "fixed")); require(state.value.themes.any { it.id == id }); prefs.edit().putString(key, id).apply() }
    fun setFontSize(size: Int) { prefs.edit().putInt("font_size", size.coerceIn(5,200)).apply() }
    private suspend fun reloadThemes() {
        val themes = withContext(Dispatchers.IO) {
            val presets = context.assets.list("themes").orEmpty().sorted().mapNotNull { file ->
                runCatching { context.assets.open("themes/$file").bufferedReader().use { AlacrittyThemeParser.parse(it.readText(), file.removeSuffix(".toml"), file.removeSuffix(".toml")) } }.getOrNull()
            }
            presets + themeDir.listFiles().orEmpty().filter { it.name.endsWith(".toml") && it.length() <= 131072 }.sortedBy { it.name }.mapNotNull { file ->
                runCatching { AlacrittyThemeParser.parse(file.readText(), file.nameWithoutExtension, file.nameWithoutExtension) }.getOrNull()
            }
        }
        mutable.update { it.copy(themes = themes.ifEmpty { it.themes }) }
    }
    suspend fun importTheme(uri: Uri): String {
        val id = withContext(Dispatchers.IO) {
            val bytes = readLimited(uri, 131072)
            val id = "import_" + digest(bytes)
            AlacrittyThemeParser.parse(bytes.toString(Charsets.UTF_8), id, displayName(uri).removeSuffix(".toml"))
            themeDir.mkdirs(); atomicWrite(File(themeDir, "$id.toml"), bytes)
            id
        }
        reloadThemes()
        return id
    }
    suspend fun deleteTheme(id: String) {
        require(id.matches(Regex("import_[0-9a-f]{32}")))
        withContext(Dispatchers.IO) { File(themeDir, "$id.toml").delete() }
        val edit = prefs.edit()
        for (key in listOf("light", "dark", "fixed")) if (prefs.getString(key, "") == id)
            edit.putString(key, if (key == "light") "standard_light" else "standard_dark")
        edit.apply(); reloadThemes()
    }
    suspend fun importFont(uri: Uri) {
        val oldId = state.value.fontId
        val result = withContext(Dispatchers.IO) {
            val bytes = readLimited(uri, 15 * 1024 * 1024)
            // Native console and WebView share TTF/OTF fonts, including their actual font signature.
            require(bytes.size > 4 && (bytes.take(4) == listOf<Byte>(0,1,0,0) || bytes.copyOfRange(0,4).toString(Charsets.US_ASCII) in listOf("OTTO", "true"))) { "Use TTF or OTF" }
            val temp = File.createTempFile("font-", ".ttf", context.cacheDir)
            val id = digest(bytes)
            try { temp.writeBytes(bytes); Typeface.createFromFile(temp); fontDir.mkdirs(); atomicWrite(fontFile(id)!!, bytes) } finally { temp.delete() }
            id to displayName(uri)
        }
        withContext(Dispatchers.IO) {
            check(prefs.edit().putString("font_id", result.first).putString("font_name", result.second).commit()) { "Unable to save font preference" }
        }
        if (oldId != result.first) withContext(Dispatchers.IO) { fontFile(oldId)?.delete() }
    }
    suspend fun resetFont() {
        val oldId = state.value.fontId
        withContext(Dispatchers.IO) {
            check(prefs.edit().remove("font_id").remove("font_name").commit()) { "Unable to save font preference" }
            fontFile(oldId)?.delete()
        }
    }
    private fun loadFont() = scope.launch {
        val id = state.value.fontId
        val result = withContext(Dispatchers.IO) {
            val file = fontFile(id)?.takeIf { it.isFile } ?: File("/system/fonts/DroidSansMono.ttf")
            runCatching { require(file.length() in 1..15L*1024*1024); Typeface.createFromFile(file) to Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }.getOrElse { Typeface.MONOSPACE to null }
        }
        if (state.value.fontId == id) { typeface = result.first; mutable.update { it.copy(fontData = result.second, fontReady = true) } }
    }
    private fun readLimited(uri: Uri, limit: Int): ByteArray = context.contentResolver.openInputStream(uri)!!.use {
        val data = it.readNBytes(limit + 1); require(data.size <= limit) { "File too large" }; data
    }
    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.filter { !it.isISOControl() }?.take(120)
    }.getOrNull() ?: "Imported"
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).take(16).joinToString("") { "%02x".format(it) }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (error: Throwable) { atomic.failWrite(out); throw error }
    }
    companion object {
        @Volatile private var instance: AppearanceRepository? = null
        fun get(context: Context): AppearanceRepository = instance ?: synchronized(this) {
            instance ?: AppearanceRepository(context.applicationContext).also { instance = it }
        }
    }
}
