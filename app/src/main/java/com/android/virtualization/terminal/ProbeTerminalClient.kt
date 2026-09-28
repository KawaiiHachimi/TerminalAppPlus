/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import com.termux.terminal.*
import com.termux.view.*
import android.view.*
import android.content.*
import android.view.inputmethod.InputMethodManager

internal class ProbeTerminalClient(private val view: com.termux.view.TerminalView) : TerminalSessionClient, TerminalViewClient {
    var control = false
    var alt = false
    var onModifiersChanged: () -> Unit = {}
    override fun onTextChanged(session: TerminalSession) { view.onScreenUpdated() }
    override fun onTitleChanged(session: TerminalSession) {}
    override fun onSessionFinished(session: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        view.context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Console", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = view.context.getSystemService(ClipboardManager::class.java).primaryClip
        if (clip != null && clip.itemCount > 0) session?.emulator?.paste(clip.getItemAt(0).coerceToText(view.context).toString())
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { view.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) { view.invalidate() }
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int = 0
    private val appearance = view.context.getSharedPreferences("terminal_console_appearance", Context.MODE_PRIVATE)
    private var fontSizeSp = runCatching { appearance.getFloat("font_size_sp", 12f) }
        .getOrDefault(12f).let { if (it.isFinite()) it.coerceIn(6f, 32f) else 12f }
    init { applyFontSize() }
    private fun applyFontSize() {
        view.setTextSize(android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, fontSizeSp, view.resources.displayMetrics,
        ).toInt().coerceAtLeast(1))
    }
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            fontSizeSp = (fontSizeSp + if (scale > 1f) 0.5f else -0.5f).coerceIn(6f, 32f)
            applyFontSize()
            appearance.edit().putFloat("font_size_sp", fontSizeSp).apply()
            return 1f
        }
        return scale
    }
    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        view.context.getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = control
    override fun readAltKey() = alt
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "Terminal", e) }
}
