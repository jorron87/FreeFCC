package com.freefcc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers HardwareLock's mutual exclusion — the guarantee S-006 relies on to stop
 * FccKeepaliveService applying the Home Point profile while FccViewModel holds the lock (or
 * vice versa). The held lock is only released after the concurrent tryBegin()
 * attempt has actually run, via CountDownLatch, so "exactly one wins" can't pass
 * by accident from a lock that was already free by the time the second call ran.
 */
class HardwareLockTest {

    @Test
    fun secondTryBeginFailsWhileFirstHoldsTheLock() {
        val firstLease = HardwareLock.tryBegin()
        try {
            assertTrue("lock must be free at test start", firstLease != null)
            assertTrue(HardwareLock.busy.value)

            val secondAttempted = CountDownLatch(1)
            val secondResult = AtomicReference<HardwareLock.Lease?>()

            val secondThread = Thread {
                secondResult.set(HardwareLock.tryBegin())
                secondAttempted.countDown()
            }
            secondThread.start()

            assertTrue(
                "second thread's tryBegin() never ran",
                secondAttempted.await(5, TimeUnit.SECONDS)
            )
            secondThread.join(5000)

            assertTrue("second tryBegin() must fail while the first op still holds the lock", secondResult.get() == null)
        } finally {
            // Only release what this test actually acquired — never unlock on behalf
            // of another op, and never leak the lock into later tests on assertion failure.
            firstLease?.close()
        }
        assertFalse(HardwareLock.busy.value)

        val reacquired = HardwareLock.tryBegin()
        try {
            assertTrue("lock must be free again after close()", reacquired != null)
        } finally {
            reacquired?.close()
        }
    }
}
