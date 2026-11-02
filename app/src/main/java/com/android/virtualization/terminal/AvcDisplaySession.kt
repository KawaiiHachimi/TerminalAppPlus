/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import java.io.DataInputStream
import java.nio.ByteBuffer

/** One connection owns its codec on its reader thread; closing the socket cancels reads. */
internal object AvcDisplaySession {
    fun play(input: DataInputStream, surface: Surface, alive: () -> Boolean, onFrame: () -> Unit) {
        val size = AvcStream.header(input)
        var packet = AvcStream.packet(input)
        val sets = AvcStream.parameterSets(packet)
        check(sets.keys.containsAll(listOf(7, 8))) { "AVC stream has no SPS/PPS" }
        val format = MediaFormat.createVideoFormat("video/avc", size.width, size.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sets.getValue(7)))
            setByteBuffer("csd-1", ByteBuffer.wrap(sets.getValue(8)))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AvcStream.MAX_PACKET)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { type -> type.equals("video/avc", true) } &&
                runCatching { it.getCapabilitiesForType("video/avc").isFormatSupported(format) }.getOrDefault(false)
        }?.name ?: error("No supported hardware AVC decoder")
        android.util.Log.i("PlusAvc", "Hardware decoder=$name ${size.width}x${size.height}")
        val codec = MediaCodec.createByCodecName(name)
        var started = false
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        var announced = false
        try {
            codec.configure(format, surface, null, 0)
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            codec.start(); started = true
            fun drain() {
                while (alive()) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (index < 0) break
                    val show = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    codec.releaseOutputBuffer(index, show)
                    if (show && !announced) {
                        announced = true
                        android.util.Log.i("PlusAvc", "First decoded frame submitted to Surface")
                        onFrame()
                    }
                }
            }
            while (alive()) {
                var slot = -1
                val deadline = System.nanoTime() + 2_000_000_000L
                while (slot < 0 && alive()) {
                    drain()
                    check(System.nanoTime() < deadline) { "AVC decoder stalled" }
                    slot = codec.dequeueInputBuffer(10_000)
                }
                if (!alive()) break
                val target = checkNotNull(codec.getInputBuffer(slot))
                check(packet.size <= target.capacity())
                target.clear(); target.put(packet)
                codec.queueInputBuffer(slot, 0, packet.size, pts, 0)
                pts += 33_333
                drain()
                packet = AvcStream.packet(input)
            }
        } finally {
            if (started) runCatching { codec.stop() }
            codec.release()
        }
    }
}
