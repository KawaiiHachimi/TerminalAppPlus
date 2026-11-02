/* SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.content.Context
import android.util.Base64
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TerminalStyleState(val fontData: String? = null, val fontSize: Int = 13)

/** Fixed Dracula terminal colors, independent of Android light/dark mode. */
object Dracula {
    const val background = "#282a36"
    val colors = mapOf(
        "background" to background, "foreground" to "#f8f8f2",
        "black" to "#21222c", "red" to "#ff5555", "green" to "#50fa7b", "yellow" to "#f1fa8c",
        "blue" to "#bd93f9", "magenta" to "#ff79c6", "cyan" to "#8be9fd", "white" to "#f8f8f2",
        "brightBlack" to "#6272a4", "brightRed" to "#ff6e6e", "brightGreen" to "#69ff94", "brightYellow" to "#ffffa5",
        "brightBlue" to "#d6acff", "brightMagenta" to "#ff92df", "brightCyan" to "#a4ffff", "brightWhite" to "#ffffff",
        "cursor" to "#f8f8f2", "cursorAccent" to background, "selectionBackground" to "#44475a", "selectionForeground" to "#f8f8f2",
    )
}

class FixedTerminalStyle private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("terminal_appearance", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(TerminalStyleState(fontSize = prefs.getInt("font_size",13).coerceIn(5,200)))
    val state = mutable.asStateFlow()
    init {
        // Preserve the font already chosen in the first dev build. No chooser/import remains.
        val id = prefs.getString("font_id", "").orEmpty()
        val chosen = id.takeIf { it.matches(Regex("[0-9a-f]{32}")) }?.let { File(context.filesDir,"terminal-fonts/$it.ttf") }
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            val file = chosen?.takeIf { it.isFile } ?: File("/system/fonts/DroidSansMono.ttf")
            val data = runCatching {
                require(file.length() in 1..15L*1024*1024)
                Base64.encodeToString(file.readBytes(),Base64.NO_WRAP)
            }.getOrNull()
            mutable.update { it.copy(fontData=data) }
        }
    }
    fun setFontSize(size: Int) {
        val value = size.coerceIn(5,200)
        prefs.edit().putInt("font_size",value).apply()
        mutable.update { it.copy(fontSize=value) }
    }
    companion object {
        @Volatile private var instance: FixedTerminalStyle? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: FixedTerminalStyle(context.applicationContext).also { instance=it }
        }
    }
}
