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
    initialResolution: DisplayResolution,
    private val onStatus: (String?) -> Unit,
) : SurfaceHolder.Callback, DefaultLifecycleObserver {
    private val vm = checkNotNull(VmController.virtualMachine)
    private val generation = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor()
    private val renderer = Executors.newSingleThreadExecutor()
    @Volatile private var frames: LatestFrameQueue? = null
    private val lifecycle = (mainView.context as? LifecycleOwner)?.lifecycle
    @Volatile private var connection: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var closed = false
    private var resolution = initialResolution
    private var requestedSize = 0 to 0

    init {
        // Guest capture composites the cursor into the frame; no host cursor Binder is needed.
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
        val queue = LatestFrameQueue()
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        frames = queue
        renderer.execute { render(queue, ticket, ready) }
        executor.execute {
            val decoder = CaptureFrameDecoder()
            var compressed = ByteArray(0)
            try {
                while (generation.get() == ticket) {
                    try {
                        vm.connectVsock(7683).use { fd ->
                            if (generation.get() != ticket) return@use
                            connection = fd
                            val input = DataInputStream(FileInputStream(fd.fileDescriptor))
                            val output = FileOutputStream(fd.fileDescriptor)
                            while (generation.get() == ticket) {
                                output.write('Q'.code); output.flush()
                                val w = input.readInt(); val h = input.readInt(); val length = input.readInt()
                                check(w in 1..4096 && h in 1..4096 && length == w * h * 4 && length <= 32 * 1024 * 1024)
                                val wireLength = input.readInt()
                                val codec = input.readInt()
                                check(wireLength in 1..length && codec in 0..2)
                                val buffer = queue.acquire(length)
                                if (codec == 0) {
                                    check(wireLength == length)
                                    input.readFully(buffer)
                                } else {
                                    if (compressed.size < wireLength) {
                                        compressed = ByteArray(minOf(length, maxOf(wireLength, compressed.size * 2, 65536)))
                                    }
                                    input.readFully(compressed, 0, wireLength)
                                    decoder.decode(compressed, wireLength, buffer, codec)
                                }
                                queue.offer(LatestFrameQueue.Frame(w, h, buffer))
                            }
                        }
                    } catch (e: Exception) {
                        if (generation.get() != ticket) break
                        ready.set(false)
                        mainView.post {
                            if (generation.get() == ticket) onStatus(mainView.context.getString(R.string.plus_display_service_required))
                        }
                        Thread.sleep(1000)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally { decoder.close(); queue.close() }
        }
    }
    private fun render(queue: LatestFrameQueue, ticket: Int, ready: java.util.concurrent.atomic.AtomicBoolean) {
        var bitmap: Bitmap? = null
        val paint = android.graphics.Paint().apply {
            // The guest sends native BGRX. Swizzle on the GPU instead of in guest Python.
            colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                0f, 0f, 1f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 255f,
            ))
        }
        var count = 0
        var sampleStart = System.nanoTime()
        var drawNanos = 0L
        try {
            while (generation.get() == ticket) {
                val frame = queue.take() ?: break
                val start = System.nanoTime()
                try {
                    if (bitmap?.width != frame.width || bitmap?.height != frame.height) {
                        bitmap?.recycle()
                        bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
                    }
                    bitmap!!.copyPixelsFromBuffer(ByteBuffer.wrap(frame.bytes))
                    if (mainView.holder.surface.isValid && generation.get() == ticket) {
                        val canvas = mainView.holder.lockHardwareCanvas()
                        try { canvas.drawBitmap(bitmap!!, null, Rect(0, 0, canvas.width, canvas.height), paint) }
                        finally { mainView.holder.unlockCanvasAndPost(canvas) }
                        if (ready.compareAndSet(false, true)) {
                            mainView.post { if (generation.get() == ticket) onStatus(null) }
                        }
                    }
                } finally { queue.recycle(frame.bytes) }
                drawNanos += System.nanoTime()-start
                count++
                val elapsed = System.nanoTime()-sampleStart
                if (elapsed >= 5_000_000_000L) {
                    android.util.Log.d("KmsDisplayProvider", "${frame.width}x${frame.height}: %.1f drawn fps, %.1f ms draw, %d dropped".format(
                        count*1e9/elapsed, drawNanos/1e6/count, queue.dropped))
                    sampleStart=System.nanoTime(); count=0; drawNanos=0
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (generation.get() == ticket) android.util.Log.w("KmsDisplayProvider", "Render stopped", e)
        } finally { bitmap?.recycle() }
    }
    private fun stop() {
        running = false
        generation.incrementAndGet()
        frames?.close()
        frames = null
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
        renderer.shutdownNow()
    }
}
