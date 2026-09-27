package com.android.virtualization.terminal

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LatestFrameQueueTest {
    @Test fun slowRendererReceivesLatestFrameAndDroppedStorageIsReused() {
        val queue = LatestFrameQueue()
        val old = LatestFrameQueue.Frame(1, 1, ByteArray(4))
        val fresh = LatestFrameQueue.Frame(1, 1, ByteArray(4))
        queue.offer(old)
        queue.offer(fresh)
        assertSame(fresh, queue.take())
        assertEquals(1, queue.dropped)
        assertSame(old.bytes, queue.acquire(4))
    }
    @Test fun closeWakesWaitingRendererAndRejectsLateFrames() {
        val queue = LatestFrameQueue()
        val started = CountDownLatch(1)
        val result = AtomicReference<LatestFrameQueue.Frame?>(LatestFrameQueue.Frame(1, 1, ByteArray(4)))
        val thread = Thread { started.countDown(); result.set(queue.take()) }
        thread.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        queue.close()
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertNull(result.get())
        queue.offer(LatestFrameQueue.Frame(1, 1, ByteArray(4)))
        assertNull(queue.take())
    }
    @Test fun resolutionChangeDoesNotReuseWrongSizedStorage() {
        val queue = LatestFrameQueue()
        val old = queue.acquire(4)
        queue.recycle(old)
        val larger = queue.acquire(16)
        assertEquals(16, larger.size)
        assertNotSame(old, larger)
    }
}
