/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.permission.IPermissionManager
import android.graphics.Typeface
import android.view.View
import android.graphics.drawable.GradientDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.google.android.material.color.DynamicColors
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.Executors
import com.android.virtualization.terminal.new2.ui.MainActivity as NewUiMainActivity

/** Normal apps need development grants before loading the stock terminal workflow. */
class LauncherActivity : androidx.appcompat.app.AppCompatActivity() {
    private var dialog: AlertDialog? = null
    private var status: TextView? = null
    private var configureButton: Button? = null
    private var configuring = false
    private val worker = Executors.newSingleThreadExecutor()
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_REQUEST) runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                if (result == PackageManager.PERMISSION_GRANTED) grantWithShizuku()
                else showStatus(R.string.plus_shizuku_denied)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun onResume() {
        super.onResume()
        checkAndShow()
    }

    private fun checkAndShow() {
        if (isDestroyed || isFinishing) return
        val supported = packageManager.hasSystemFeature("android.software.virtualization_framework")
        if (supported && AVF_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            dialog?.dismiss()
            startActivity(Intent(this, NewUiMainActivity::class.java))
            finish()
            return
        }
        if (dialog?.isShowing == true) return
        val commands = AVF_PERMISSIONS.joinToString("\n") { "adb shell pm grant $packageName $it" }
        val builder = MaterialAlertDialogBuilder(this)
        val themed = builder.context
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun label(value: Int, size: Float = 14f) = TextView(themed).apply {
            setText(value)
            textSize = size
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        fun action(value: Int, style: Int, click: () -> Unit) = MaterialButton(themed, null, style).apply {
            setText(value)
            isAllCaps = false
            cornerRadius = dp(24)
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            setOnClickListener { click() }
        }
        content.addView(label(if (supported) R.string.plus_setup_summary else R.string.plus_avf_unavailable))
        if (supported) {
            configureButton = action(R.string.plus_shizuku_configure,
                com.google.android.material.R.attr.materialButtonStyle) { requestShizuku() }
            content.addView(configureButton)
            content.addView(label(R.string.plus_shizuku_hint, 12f).apply {
                setPadding(0, dp(4), 0, dp(16))
            })
            content.addView(View(themed).apply {
                setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant))
                layoutParams = LinearLayout.LayoutParams(-1, dp(1))
            })
            content.addView(label(R.string.plus_adb_alternative).apply { setPadding(0, dp(16), 0, 0) })
            val commandText = TextView(themed).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                text = commands
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(MaterialColors.getColor(content, com.google.android.material.R.attr.colorSurfaceContainerHighest))
                }
            }
            content.addView(action(R.string.plus_copy_commands,
                com.google.android.material.R.attr.materialButtonOutlinedStyle) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ADB", commands))
                showStatus(R.string.plus_commands_copied)
            })
            content.addView(commandText)
            status = label(R.string.plus_shizuku_hint, 13f).apply {
                text = ""
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(0, dp(8), 0, 0)
            }.also { content.addView(it) }
        }
        dialog = builder.setTitle(R.string.plus_setup_title)
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton(R.string.plus_check_again, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { checkAndShow() }
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                showStatus(R.string.plus_shizuku_unavailable)
                return
            }
            if (Shizuku.isPreV11()) {
                showStatus(R.string.plus_shizuku_update)
                return
            }
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> grantWithShizuku()
                Shizuku.shouldShowRequestPermissionRationale() -> showStatus(R.string.plus_shizuku_denied)
                else -> Shizuku.requestPermission(SHIZUKU_REQUEST)
            }
        } catch (e: Exception) {
            status?.text = getString(R.string.plus_shizuku_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun grantWithShizuku() {
        if (configuring) return
        configuring = true
        configureButton?.isEnabled = false
        showStatus(R.string.plus_shizuku_working)
        val ownPackage = packageName
        val ownUser = Process.myUid() / 100000
        worker.execute {
            val result = runCatching {
                val binder = checkNotNull(SystemServiceHelper.getSystemService("permissionmgr"))
                val permissions = IPermissionManager.Stub.asInterface(ShizukuBinderWrapper(binder))
                // Android 17 signature: package, permission, persistent device ID, Android user.
                // Only this app's two fixed AVF permissions are granted; no command execution.
                AVF_PERMISSIONS.forEach {
                    permissions.grantRuntimePermission(ownPackage, it, "default:0", ownUser)
                }
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    configuring = false
                    configureButton?.isEnabled = true
                    status?.text = result.exceptionOrNull()?.let {
                        getString(R.string.plus_shizuku_failed, it.message ?: it.javaClass.simpleName)
                    } ?: getString(R.string.plus_shizuku_verify)
                    checkAndShow()
                }
            }
        }
    }

    private fun showStatus(message: Int) { status?.setText(message) }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        dialog?.dismiss()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val SHIZUKU_REQUEST = 4101
        val AVF_PERMISSIONS = arrayOf(
            "android.permission.MANAGE_VIRTUAL_MACHINE",
            "android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
        )
    }
}
