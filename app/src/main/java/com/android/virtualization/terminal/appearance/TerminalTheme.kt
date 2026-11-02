/* SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.appearance

/** QPR3-style Alacritty color subset, shared by xterm.js and the native console. */
data class TerminalTheme(val id: String, val name: String, val colors: Map<String, String>) {
    val background get() = colors.getValue("background")
    val foreground get() = colors.getValue("foreground")
    companion object {
        val ansi = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white",
            "brightBlack", "brightRed", "brightGreen", "brightYellow", "brightBlue", "brightMagenta", "brightCyan", "brightWhite")
        private val defaults = listOf("#000000", "#cd0000", "#00cd00", "#cdcd00", "#6495ed", "#cd00cd", "#00cdcd", "#e5e5e5",
            "#7f7f7f", "#ff0000", "#00ff00", "#ffff00", "#5c5cff", "#ff00ff", "#00ffff", "#ffffff")
        fun standard(light: Boolean) = TerminalTheme(if (light) "standard_light" else "standard_dark",
            if (light) "Standard Light" else "Standard Dark", (ansi.zip(defaults).toMap() + mapOf(
                "background" to if (light) "#ffffff" else "#000000", "foreground" to if (light) "#000000" else "#ffffff",
                "cursor" to if (light) "#000000" else "#ffffff", "cursorAccent" to if (light) "#ffffff" else "#000000",
                "selectionBackground" to if (light) "#cccccc" else "#333333")))
    }
}

object AlacrittyThemeParser {
    fun parse(text: String, id: String, fallbackName: String): TerminalTheme {
        require(text.length <= 131072) { "Theme exceeds 128 KiB" }
        var section = ""
        var name = fallbackName
        val colors = linkedMapOf<String, String>()
        val assignment = Regex("""^([A-Za-z_]+)\s*=\s*([\"'])(.*?)\2\s*(?:#.*)?$""")
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isBlank() || line.startsWith('#')) continue
            if (line.startsWith('[')) {
                section = line.substringBefore(']').removePrefix("[").trim()
                continue
            }
            val match = assignment.matchEntire(line)
            if (match == null) {
                require(!section.startsWith("colors.")) { "Invalid color assignment" }
                continue
            }
            val (key, _, value) = match.destructured
            if (key == "name" && section in listOf("", "metadata")) name = value
            val target = when (section) {
                "colors.primary" -> key.takeIf { it in listOf("background", "foreground") }
                "colors.normal" -> key.takeIf { it in TerminalTheme.ansi.take(8) }
                "colors.bright" -> key.takeIf { it in TerminalTheme.ansi.take(8) }?.let { "bright" + it.replaceFirstChar(Char::uppercaseChar) }
                "colors.cursor" -> when (key) { "cursor" -> "cursor"; "text" -> "cursorAccent"; else -> null }
                "colors.selection" -> when (key) { "background" -> "selectionBackground"; "text" -> "selectionForeground"; else -> null }
                else -> null
            }
            if (target != null) {
                val color = value.replace(Regex("^0[xX]"), "#")
                require(Regex("#[0-9a-fA-F]{6}").matches(color)) { "Use #RRGGBB colors" }
                colors[target] = color.lowercase()
            }
        }
        require(colors.keys.containsAll(listOf("background", "foreground"))) { "Missing background/foreground" }
        val defaults = TerminalTheme.standard(false).colors.toMutableMap().apply {
            putAll(colors); putIfAbsent("cursor", colors.getValue("foreground"))
            if ("cursor" !in colors) put("cursor", colors.getValue("foreground"))
            if ("cursorAccent" !in colors) put("cursorAccent", colors.getValue("background"))
        }
        return TerminalTheme(id, name.filter { !it.isISOControl() }.take(80).ifBlank { fallbackName }, defaults)
    }
}
