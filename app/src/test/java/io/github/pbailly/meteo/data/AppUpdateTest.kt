package io.github.pbailly.meteo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AppUpdateTest {

    private val body = """
        {"tag_name":"apk-latest","target_commitish":"041e8476181d3623d1b56318900ce684d511ce78","published_at":"2026-10-20T00:08:49Z",
         "assets":[{"name":"meteo.apk","size":24636377,
           "browser_download_url":"https://github.com/p-bailly/Meteo/releases/download/apk-latest/meteo.apk"}]}
    """.trimIndent()

    @Test
    fun `latest release gives its commit and apk`() {
        val release = AppUpdate.parse(body)
        assertEquals("041e8476181d3623d1b56318900ce684d511ce78", release.commit)
        assertEquals(Instant.parse("2026-10-20T00:08:49Z"), release.publishedAt)
        assertEquals(24636377L, release.size)
        assertTrue(release.apkUrl.endsWith("/meteo.apk"))
    }

    @Test
    fun `an update is offered only for another commit`() {
        val release = AppUpdate.parse(body)
        assertFalse(AppUpdate.isNewer(release, "041e8476181d3623d1b56318900ce684d511ce78"))
        assertTrue(AppUpdate.isNewer(release, "9903fd19cff275c592e525e3bb621e2467d204b9"))
        // Appli compilée hors de GitHub : on propose toujours la version publiée.
        assertTrue(AppUpdate.isNewer(release, ""))
    }
}
