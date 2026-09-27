/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.app.Activity
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.graphics.Rect
import android.widget.*
import android.view.Gravity
import com.android.virtualization.terminal.new2.core.VmController
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Existing KMS screen + AOSP input. No system display Binder or new desktop. */
class VmScreenProbeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var connection: ParcelFileDescriptor? = null
    @Volatile private var stopped = false
    private var inputForwarder: InputForwarder? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val vm = VmController.virtualMachine
        val status = TextView(this).apply { text = "KMS → Surface · AOSP 输入实验" }
        val surface = DisplaySurfaceView(this, null)
        val area = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK); addView(surface) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(area, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(this@VmScreenProbeActivity).apply {
                addView(Button(context).apply { text = "键盘"; setOnClickListener { surface.showSoftInput() } })
                addView(Button(context).apply { text = "捕获鼠标"; setOnClickListener { surface.requestFocus(); surface.requestPointerCapture() } })
                addView(Button(context).apply { text = "返回"; setOnClickListener { finish() } })
            })
            setOnApplyWindowInsetsListener { view, insets ->
                val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(b.left, b.top, b.right, b.bottom); insets
            }
        }
        setContentView(root)
        if (vm == null) { status.text = "请先启动虚拟机"; return }
        inputForwarder = InputForwarder(this, vm, surface, surface, surface) { surface.releasePointerCapture() }
        surface.requestFocus()
        worker.execute {
            var bitmap: Bitmap? = null
            try {
                vm.connectVsock(7683).use { fd ->
                    connection = fd
                    val input = DataInputStream(FileInputStream(fd.fileDescriptor))
                    val output = FileOutputStream(fd.fileDescriptor)
                    var buffer = ByteArray(0)
                    var frames = 0
                    var changes = 0
                    var lastHash = -1L
                    val start = System.nanoTime()
                    while (!stopped) {
                        output.write('R'.code); output.flush()
                        val w = input.readInt(); val h = input.readInt(); val length = input.readInt()
                        check(w in 1..4096 && h in 1..4096 && length == w*h*4)
                        if (buffer.size != length) buffer = ByteArray(length)
                        input.readFully(buffer)
                        val crc = java.util.zip.CRC32().apply { update(buffer) }.value
                        if (crc != lastHash) { changes++; lastHash = crc }
                        if (bitmap?.width != w || bitmap?.height != h) {
                            bitmap?.recycle(); bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        }
                        bitmap!!.copyPixelsFromBuffer(ByteBuffer.wrap(buffer))
                        if (surface.holder.surface.isValid) {
                            val canvas = surface.holder.lockHardwareCanvas()
                            try { canvas.drawBitmap(bitmap!!, null, Rect(0, 0, canvas.width, canvas.height), null) }
                            finally { surface.holder.unlockCanvasAndPost(canvas) }
                        }
                        frames++
                        if (frames % 10 == 1) {
                            val fps = frames * 1e9 / (System.nanoTime() - start)
                            runOnUiThread {
                                if (!stopped) {
                                    val fit = minOf(area.width.toFloat()/w, area.height.toFloat()/h)
                                    surface.layoutParams = FrameLayout.LayoutParams((w*fit).toInt(), (h*fit).toInt(), Gravity.CENTER)
                                    status.text = "${w}×${h} · 接收 %.1f fps · 内容变化 $changes 次".format(fps)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { if (!stopped) status.text = "画面不可用：${e.message}" }
            } finally { bitmap?.recycle() }
        }
    }
    override fun onStop() {
        super.onStop()
        finish() // Release the capture session when leaving this experimental viewer.
    }
    override fun onDestroy() {
        stopped = true
        inputForwarder?.cleanUp()
        connection?.let { runCatching { android.system.Os.shutdown(it.fileDescriptor, android.system.OsConstants.SHUT_RDWR) }; runCatching { it.close() } }
        worker.shutdownNow()
        super.onDestroy()
    }
}
