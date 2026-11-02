/* SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.appearance

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AlacrittyThemeParserTest {
    @Test fun importsAllQprPresetsWithCompletePalette() {
        File("src/main/assets/themes").listFiles()!!.forEach { file ->
            val theme = AlacrittyThemeParser.parse(file.readText(), file.nameWithoutExtension, "fallback")
            assertEquals(16, TerminalTheme.ansi.count { theme.colors.containsKey(it) })
            assertTrue(theme.background.matches(Regex("#[0-9a-f]{6}")))
            assertTrue(theme.name.isNotBlank())
        }
    }
    @Test fun respectsSectionsQuotesCommentsAndSafeColorValues() {
        val theme = AlacrittyThemeParser.parse("""
            [metadata]
            name = 'A # theme'
            [colors.primary] # comment
            background = '#ABCDEF' # inline
            foreground = "0x123456"
            [colors.bright]
            blue = '#010203'
            [colors.cursor]
            text = '#000000'
        """.trimIndent(), "import", "fallback")
        assertEquals("A # theme", theme.name)
        assertEquals("#abcdef", theme.background)
        assertEquals("#123456", theme.colors["cursor"])
        assertEquals("#010203", theme.colors["brightBlue"])
        assertEquals("#000000", theme.colors["cursorAccent"])
    }
    @Test fun rejectsBrokenMissingAndOversizedInput() {
        for (text in listOf("[colors.primary]\nforeground='#123456'", "[colors.primary]\nbackground='red'\nforeground='#ffffff'", "x".repeat(131073))) {
            assertThrows(IllegalArgumentException::class.java) { AlacrittyThemeParser.parse(text,"test","fallback") }
        }
    }
    @Test fun choosesLightDarkOrFixedByStableIds() {
        val state = AppearanceState()
        assertEquals("standard_light", state.theme(false).id)
        assertEquals("standard_dark", state.theme(true).id)
        assertEquals("standard_dark", state.copy(mode="dark").theme(false).id)
        assertEquals("standard_light", state.copy(automatic=false,fixed="standard_light").theme(true).id)
        assertEquals("standard_light", state.copy(light="deleted").theme(false).id)
    }
}
