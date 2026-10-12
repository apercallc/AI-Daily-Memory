package com.apercallc.aidailymemory.update

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppReleasesTest {
    private fun releaseJson(
        tag: String,
        vararg assets: Pair<String, String>,
        body: String = "",
    ): String {
        val array = org.json.JSONArray()
        assets.forEach { (name, url) -> array.put(JSONObject().put("name", name).put("browser_download_url", url)) }
        return JSONObject()
            .put("tag_name", tag)
            .put("body", body)
            .put("assets", array)
            .toString()
    }

    @Test
    fun parsesTheApkAssetAndStripsTheVPrefix() {
        val json = releaseJson(
            "v0.2.0",
            "checksums.txt" to "https://example.com/checksums.txt",
            "ai-daily-memory-phone.apk" to "https://example.com/app.apk",
            body = "Fixes",
        )
        val release = AppReleases.parseLatest(json, "ai-daily-memory-phone.apk")!!
        assertEquals("0.2.0", release.versionName)
        assertEquals("https://example.com/app.apk", release.apkUrl)
        assertEquals("Fixes", release.notes)
    }

    @Test
    fun missingAssetOrMalformedBodyMeansNoUpdate() {
        assertNull(AppReleases.parseLatest(releaseJson("v0.2.0"), "ai-daily-memory-phone.apk"))
        assertNull(AppReleases.parseLatest("{ not json", "ai-daily-memory-phone.apk"))
        assertNull(AppReleases.parseLatest("[]", "ai-daily-memory-phone.apk"))
    }

    @Test
    fun tagWithoutAPrefixStillParses() {
        val json = releaseJson("0.3.0", "ai-daily-memory-phone.apk" to "https://example.com/app.apk")
        assertEquals("0.3.0", AppReleases.parseLatest(json, "ai-daily-memory-phone.apk")!!.versionName)
    }

    @Test
    fun newerVersionsAreDetectedComponentWise() {
        assertTrue(AppReleases.isNewer("0.1.0", "0.1.1"))
        assertTrue(AppReleases.isNewer("0.1.9", "0.2.0"))
        assertTrue(AppReleases.isNewer("0.1", "0.1.1"))
        assertTrue(AppReleases.isNewer("1.9.9", "2.0.0"))
    }

    @Test
    fun equalOlderOrUnparseableIsNotNewer() {
        assertFalse(AppReleases.isNewer("0.2.0", "0.2.0"))
        assertFalse(AppReleases.isNewer("0.2.1", "0.2.0"))
        assertFalse(AppReleases.isNewer("0.2.0", "0.2.0-debug"))
        assertFalse(AppReleases.isNewer("0.2.0", ""))
        assertFalse(AppReleases.isNewer("", "0.2.0"))
        assertFalse(AppReleases.isNewer("0.2.0", "latest"))
    }
}

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    private class FakeTransport(private val json: String?) : ReleaseTransport {
        override fun latestReleaseJson(): String? = json

        override fun downloadApk(url: String, destination: File) {
            destination.writeText("apk-bytes")
        }
    }

    private val apkJson = JSONObject()
        .put(
            "tag_name",
            "v9.9.9",
        )
        .put(
            "assets",
            org.json.JSONArray().put(
                JSONObject()
                    .put("name", UpdateChecker.ASSET_NAME)
                    .put("browser_download_url", "https://example.com/app.apk"),
            ),
        )
        .toString()

    @Test
    fun newerReleaseIsReportedAndOlderIsNot() {
        val checker = UpdateChecker(FakeTransport(apkJson))
        assertTrue(checker.check("0.1.0") != null)
        assertNull(checker.check("9.9.9"))
    }

    @Test
    fun offlineOrUnreadableAnswersAreNoUpdateNeverAnException() {
        assertNull(UpdateChecker(FakeTransport(null)).check("0.1.0"))
        assertNull(UpdateChecker(FakeTransport("garbage")).check("0.1.0"))
    }

    @Test
    fun downloadGoesThroughTheTransport() {
        val checker = UpdateChecker(FakeTransport(apkJson))
        val release = checker.check("0.1.0")!!
        val file = File.createTempFile("update", ".apk")
        try {
            checker.download(release, file)
            assertEquals("apk-bytes", file.readText())
        } finally {
            file.delete()
        }
    }
}
