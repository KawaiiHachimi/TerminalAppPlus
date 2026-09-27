/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.android.virtualization.terminal.new2.core.VmController
import com.android.virtualization.terminal.new2.ui.main.DisplayResolution
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** AOSP screen/input UI with guest KMS frames replacing the restricted Surface Binder. */
internal class KmsDisplayProvider(
    private val mainView: SurfaceView,
    cursorView: SurfaceView,
    width: Int,
    height: Int,
    initialResolution: DisplayResolution,
    private val onFrameSize: (Int, Int) -> Unit,
    private val onStatus: (String?) -> Unit,
) : SurfaceHolder.Callback, DefaultLifecycleObserver {
    private val vm = checkNotNull(VmController.virtualMachine)
    private val generation = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor()
    private val lifecycle = (mainView.context as? LifecycleOwner)?.lifecycle
    @Volatile private var connection: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var closed = false
    private var resolution = initialResolution
    private var requestedSize = 0 to 0

    init {
        // KMS prototype captures the primary plane; there is no host cursor Binder stream.
        cursorView.visibility = android.view.View.GONE
        mainView.holder.addCallback(this)
        lifecycle?.addObserver(this)
    }
    override fun surfaceCreated(holder: SurfaceHolder) { updateResolution(); start() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { updateResolution() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { stop() }
    override fun onStart(owner: LifecycleOwner) { start() }
    override fun onStop(owner: LifecycleOwner) { stop() }

    fun setResolution(value: DisplayResolution) {
        if (resolution == value) return
        resolution = value
        updateResolution()
    }
    private fun updateResolution() {
        if (closed || mainView.width == 0 || mainView.height == 0) return
        val size = (mainView.width * resolution.scale).toInt().coerceAtLeast(1) to
            (mainView.height * resolution.scale).toInt().coerceAtLeast(1)
        if (requestedSize == size) return
        requestedSize = size
        VmController.resizeDisplay(size.first, size.second,
            (mainView.resources.configuration.densityDpi * resolution.scale).toInt(), 60)
        mainView.holder.setFixedSize(size.first, size.second)
    }
    private fun start() {
        if (closed || running || !mainView.holder.surface.isValid) return
        running = true
        val ticket = generation.incrementAndGet()
        onStatus(mainView.context.getString(R.string.plus_display_connecting))
        executor.execute {
            var bitmap: Bitmap? = null
            var buffer = ByteArray(0)
            try {
                while (generation.get() == ticket) {
                    try {
                        vm.connectVsock(7683).use { fd ->
                            if (generation.get() != ticket) return@use
                            connection = fd
                            val input = DataInputStream(FileInputStream(fd.fileDescriptor))
                            val output = FileOutputStream(fd.fileDescriptor)
                            while (generation.get() == ticket) {
                                output.write('R'.code); output.flush()
                                val w = input.readInt(); val h = input.readInt(); val length = input.readInt()
                                check(w in 1..4096 && h in 1..4096 && length == w * h * 4)
                                if (buffer.size != length) buffer = ByteArray(length)
                                input.readFully(buffer)
                                if (bitmap?.width != w || bitmap?.height != h) {
                                    bitmap?.recycle(); bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    mainView.post { if (generation.get() == ticket) onFrameSize(w, h) }
                                }
                                bitmap!!.copyPixelsFromBuffer(ByteBuffer.wrap(buffer))
                                if (mainView.holder.surface.isValid && generation.get() == ticket) {
                                    val canvas = mainView.holder.lockHardwareCanvas()
                                    try { canvas.drawBitmap(bitmap!!, null, Rect(0, 0, canvas.width, canvas.height), null) }
                                    finally { mainView.holder.unlockCanvasAndPost(canvas) }
                                    mainView.post { if (generation.get() == ticket) onStatus(null) }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (generation.get() != ticket) break
                        mainView.post {
                            if (generation.get() == ticket) onStatus(mainView.context.getString(R.string.plus_display_service_required))
                        }
                        Thread.sleep(1000)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally { bitmap?.recycle() }
        }
    }
    private fun stop() {
        running = false
        generation.incrementAndGet()
        connection?.let {
            runCatching { android.system.Os.shutdown(it.fileDescriptor, android.system.OsConstants.SHUT_RDWR) }
            runCatching { it.close() }
        }
        connection = null
    }
    fun close() {
        if (closed) return
        closed = true
        stop()
        mainView.holder.removeCallback(this)
        lifecycle?.removeObserver(this)
        executor.shutdownNow()
    }
}
