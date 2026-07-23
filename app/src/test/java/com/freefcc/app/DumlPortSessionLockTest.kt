package com.freefcc.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DumlPortSessionLockTest {
    @Test
    fun `same port is exclusive while different port remains available`() {
        val capture = DumlPortSessionLock.tryBegin(40007)
        assertNotNull(capture)
        try {
            assertNull(DumlPortSessionLock.tryBegin(40007))
            val commandLease = DumlPortSessionLock.tryBegin(40009)
            assertNotNull(commandLease)
            commandLease?.use { command ->
                assertNotNull(command)
            }
        } finally {
            capture?.close()
        }

        val reacquiredLease = DumlPortSessionLock.tryBegin(40007)
        assertNotNull(reacquiredLease)
        reacquiredLease?.use { reacquired ->
            assertNotNull(reacquired)
        }
    }
}
