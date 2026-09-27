/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import com.android.virtualization.terminal.new2.ui.MainActivity as NewUiMainActivity

/** Normal apps need development grants before loading the stock terminal workflow. */
class LauncherActivity : Activity() {
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }

    override fun onResume() {
        super.onResume()
        if (dialog?.isShowing == true) return
        val supported = packageManager.hasSystemFeature("android.software.virtualization_framework")
        val missing = AVF_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (supported && missing.isEmpty()) {
            startActivity(Intent(this, NewUiMainActivity::class.java))
            finish()
            return
        }
        val commands = AVF_PERMISSIONS.joinToString("\n") { "adb shell pm grant $packageName $it" }
        val message = if (supported) getString(R.string.plus_grant_instructions, commands)
            else getString(R.string.plus_avf_unavailable)
        val text = TextView(this).apply {
            setText(message)
            setTextIsSelectable(true)
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        dialog = AlertDialog.Builder(this).setTitle(R.string.app_name).setView(text)
            .setPositiveButton(R.string.plus_check_again) { _, _ -> recreate() }
            .setNeutralButton(android.R.string.copy, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ADB", commands))
        }
    }

    companion object {
        val AVF_PERMISSIONS = arrayOf(
            "android.permission.MANAGE_VIRTUAL_MACHINE",
            "android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
        )
    }
}
