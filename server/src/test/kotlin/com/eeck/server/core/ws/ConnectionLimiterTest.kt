package com.eeck.server.core.ws

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionLimiterTest {

    @Test
    fun `refuses past the limit per key and frees a slot on release`() {
        val limiter = ConnectionLimiter(maxPerKey = 2)

        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"), "keys are counted independently")

        limiter.release("a")
        assertTrue(limiter.tryAcquire("a"))
    }

    @Test
    fun `a refused acquire reserves nothing`() {
        val limiter = ConnectionLimiter(maxPerKey = 1)
        limiter.tryAcquire("a")
        limiter.tryAcquire("a")

        limiter.release("a")

        assertEquals(0, limiter.openCount("a"))
    }

    @Test
    fun `concurrent acquires never exceed the limit`() = runBlocking {
        val limiter = ConnectionLimiter(maxPerKey = 10)

        val granted = (1..1_000).map { async(Dispatchers.Default) { limiter.tryAcquire("a") } }.awaitAll()

        assertEquals(10, granted.count { it })
        assertEquals(10, limiter.openCount("a"))
    }
}
