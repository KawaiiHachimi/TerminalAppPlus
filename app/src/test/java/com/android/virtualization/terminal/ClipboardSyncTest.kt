package com.android.virtualization.terminal

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class ClipboardSyncTest {
    @Test(timeout = 5000) fun stuckGuestDoesNotBlockCallerOrAccumulateFocusRequests() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val calls = AtomicInteger()
        val sync = ClipboardSync(executor)
        try {
            sync.submit { entered.countDown(); release.await(); calls.incrementAndGet() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(100) { sync.submit { calls.incrementAndGet() } }
            release.countDown()
            executor.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(1, calls.get())
            sync.submit { calls.incrementAndGet(); done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
