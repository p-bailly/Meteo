package io.github.pbailly.meteo.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class WeatherRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val place = Place("Lyon", 45.75, 4.85)
    private val body = """{"utc_offset_seconds":0,"elevation":1.0,"hourly":{"time":[]},"daily":{"time":[]}}"""

    @Test
    fun `second call within max age is served from cache`() = runBlocking {
        var calls = 0
        val repo = WeatherRepository(tmp.root) { calls++; body }
        val first = repo.forecast(place, force = false)
        val second = repo.forecast(place, force = false)
        assertEquals(1, calls)
        assertFalse(second.stale)
        assertTrue(first.downloaded)
        assertFalse(second.downloaded)
    }

    @Test
    fun `force refresh calls the api again`() = runBlocking {
        var calls = 0
        val repo = WeatherRepository(tmp.root) { calls++; body }
        repo.forecast(place, force = false)
        repo.forecast(place, force = true)
        assertEquals(2, calls)
    }

    @Test
    fun `network failure falls back to stale cache`() = runBlocking {
        var online = true
        val repo = WeatherRepository(tmp.root) { if (online) body else throw IOException("offline") }
        repo.forecast(place, force = false)
        online = false
        val offline = repo.forecast(place, force = true)
        assertTrue(offline.stale)
        assertFalse(offline.downloaded)
    }

    @Test
    fun `api error never overwrites a good cache`() = runBlocking {
        var reply = body
        val repo = WeatherRepository(tmp.root) { reply }
        repo.forecast(place, force = false)
        reply = """{"error":true,"reason":"Too many requests"}"""
        val result = repo.forecast(place, force = true)
        assertTrue(result.stale)
        reply = body
        assertFalse(repo.forecast(place, force = false).stale)
    }

    @Test(expected = IOException::class)
    fun `failure without cache is reported`() {
        runBlocking {
            WeatherRepository(tmp.root) { throw IOException("offline") }.forecast(place, force = false)
        }
    }

    @Test
    fun `an old forecast is shown while the new one loads, a fresh one is not`() = runBlocking {
        val repo = WeatherRepository(tmp.root) { body }
        assertEquals(null, repo.lastForecast(place))
        repo.forecast(place, force = false)
        // Fraîche : forecast() la rend sans réseau, inutile de la montrer en attendant.
        assertEquals(null, repo.lastForecast(place))
        val file = java.io.File(tmp.root, "forecast_${place.id}.json")
        file.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        assertTrue(repo.lastForecast(place)!!.stale)
        file.setLastModified(System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L)
        assertEquals(null, repo.lastForecast(place))
    }

    @Test
    fun `an overloaded server is asked again`() {
        var calls = 0
        val body = retryOnNetworkError {
            calls++
            if (calls < 3) throw ServerBusyException(503)
            "ok"
        }
        assertEquals("ok", body)
        assertEquals(3, calls)
    }

    @Test
    fun `other server errors are not asked again`() {
        var calls = 0
        runCatching { retryOnNetworkError { calls++; throw HttpStatusException(404) } }
        assertEquals(1, calls)
    }
}
