/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.app.Activity
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.virtualization.terminal.new2.core.VmController
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Read-only capture viewer; no separate desktop session or native display Binder. */
class VmScreenProbeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var connection: ParcelFileDescriptor? = null
    @Volatile private var stopped = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val status = TextView(this).apply { text = "当前 VM 屏幕 · 只读实验（需要来宾采集服务）" }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(status)
            addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
        setContentView(root)
        worker.execute {
            try {
                val vm = checkNotNull(VmController.virtualMachine) { "请先启动 VM" }
                vm.connectVsock(7683).use { fd ->
                    connection = fd
                    if (stopped) return@execute
                    val input = DataInputStream(FileInputStream(fd.fileDescriptor))
                    val output = FileOutputStream(fd.fileDescriptor)
                    var count = 0
                    while (!stopped) {
                        output.write('F'.code); output.flush()
                        val size = input.readInt()
                        check(size in 1..(16 * 1024 * 1024)) { "Invalid frame size" }
                        val bytes = ByteArray(size); input.readFully(bytes)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        check(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        count++
                        val label = "当前 VM 屏幕 · ${bitmap.width}×${bitmap.height} · 帧 $count · 只读"
                        runOnUiThread { if (!stopped) { image.setImageBitmap(bitmap); status.text = label } }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { if (!stopped) status.text = "无法读取屏幕：${e.message}\n请在来宾运行实验采集服务。" }
            }
        }
    }
    override fun onDestroy() {
        stopped = true
        connection?.let { runCatching { android.system.Os.shutdown(it.fileDescriptor, android.system.OsConstants.SHUT_RDWR) }; runCatching { it.close() } }
        worker.shutdownNow()
        super.onDestroy()
    }
}
