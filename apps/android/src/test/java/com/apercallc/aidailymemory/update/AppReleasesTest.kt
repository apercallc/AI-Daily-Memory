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
