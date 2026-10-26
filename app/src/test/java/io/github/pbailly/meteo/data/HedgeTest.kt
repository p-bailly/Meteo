package io.github.pbailly.meteo.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class HedgeTest {
    private val fast = listOf(50L, 100L)

    @Test
    fun `a quick answer is not asked twice`() = runBlocking {
        val calls = AtomicInteger()
        assertEquals("ok", hedged(fast) { calls.incrementAndGet(); "ok" })
        assertEquals(1, calls.get())
    }

    @Test
    fun `a request left unanswered is asked again in parallel, and the first answer wins`() = runBlocking {
        val calls = AtomicInteger()
        val stalledCancelled = CompletableDeferred<Unit>()
        val start = System.currentTimeMillis()
        val body = hedged(fast) {
            if (calls.incrementAndGet() == 1) {
                try {
                    awaitCancellation()
                } finally {
                    stalledCancelled.complete(Unit)
                }
            }
            "second"
        }
        assertEquals("second", body)
        assertEquals(2, calls.get())
        // Sans attendre la première : bien avant les 20 s du délai de lecture.
        assertTrue(System.currentTimeMillis() - start < 2_000)
        // La requête bloquée est abandonnée.
        assertTrue(stalledCancelled.isCompleted)
    }

    @Test
    fun `at most three attempts, then the last error`() = runBlocking {
        val calls = AtomicInteger()
        val error = runCatching { hedged(fast) { calls.incrementAndGet(); throw IOException("coupé") } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(3, calls.get())
    }

    @Test
    fun `a broken connection is asked again at once`() = runBlocking {
        val calls = AtomicInteger()
        val body = hedged(listOf(10_000L, 10_000L)) {
            if (calls.incrementAndGet() == 1) throw IOException("coupé")
            "ok"
        }
        assertEquals("ok", body)
        assertEquals(2, calls.get())
    }

    @Test
    fun `an overloaded server is asked again`() = runBlocking {
        val calls = AtomicInteger()
        val body = hedged(fast) {
            if (calls.incrementAndGet() < 3) throw ServerBusyException(503)
            "ok"
        }
        assertEquals("ok", body)
        assertEquals(3, calls.get())
    }

    @Test
    fun `other server errors stop at once`() = runBlocking {
        val calls = AtomicInteger()
        val error = runCatching {
            hedged(fast) {
                if (calls.incrementAndGet() == 1) throw HttpStatusException(404)
                delay(10_000)
                "late"
            }
        }.exceptionOrNull()
        assertTrue(error is HttpStatusException)
        assertEquals(1, calls.get())
    }
}
