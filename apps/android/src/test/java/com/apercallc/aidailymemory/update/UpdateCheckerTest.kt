package com.apercallc.aidailymemory.update

import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeTransport(
        private val json: String?,
        private val apkBytes: ByteArray = ByteArray(0),
    ) : ReleaseTransport {
        var downloads = 0
        override fun latestReleaseJson() = json
        override fun downloadApk(url: String, destination: File) {
            downloads++
            destination.writeBytes(apkBytes)
        }
    }

    private fun json(tag: String = "v9.0.0", extra: String = "") = """
        {"tag_name":"$tag","body":"","assets":[{"name":"ai-daily-memory-phone.apk",
        "browser_download_url":"https://github.com/apercallc/AI-Daily-Memory/releases/download/$tag/a.apk"$extra}]}
    """.trimIndent()

    @Test
    fun `offline or malformed responses are failures not up to date`() {
        assertEquals(UpdateCheck.Failed, UpdateChecker(FakeTransport(null)).check("1.0.0"))
        assertEquals(UpdateCheck.Failed, UpdateChecker(FakeTransport("<html>")).check("1.0.0"))
    }

    @Test
    fun `newer release is reported and same version is up to date`() {
        assertIs<UpdateCheck.Newer>(UpdateChecker(FakeTransport(json("v2.0.0"))).check("1.0.0"))
        assertEquals(UpdateCheck.UpToDate, UpdateChecker(FakeTransport(json("v1.0.0"))).check("1.0.0"))
    }

    @Test
    fun `a release without the apk asset is up to date`() {
        val body = """{"tag_name":"v9.0.0","assets":[]}"""
        assertEquals(UpdateCheck.UpToDate, UpdateChecker(FakeTransport(body)).check("1.0.0"))
    }

    @Test
    fun `download rejects non github urls before any network call`() {
        val transport = FakeTransport(null)
        val release = AppRelease("2.0.0", "https://evil.example/a.apk", "")
        assertFailsWith<IOException> { UpdateChecker(transport).download(release, tmp.newFile("a.apk")) }
        assertEquals(0, transport.downloads)
    }

    @Test
    fun `download deletes the file when size or hash mismatch`() {
        val bytes = "apk-bytes".toByteArray()
        val url = "https://github.com/x/y/releases/download/v2/a.apk"
        val file = File(tmp.root, "a.apk")

        val wrongSize = AppRelease("2.0.0", url, "", sizeBytes = 3)
        assertFailsWith<IOException> { UpdateChecker(FakeTransport(null, bytes)).download(wrongSize, file) }
        assertFalse(file.exists())

        val wrongHash = AppRelease("2.0.0", url, "", sha256 = "0".repeat(64))
        assertFailsWith<IOException> { UpdateChecker(FakeTransport(null, bytes)).download(wrongHash, file) }
        assertFalse(file.exists())
    }

    @Test
    fun `download keeps a file that matches size and hash`() {
        val bytes = "apk-bytes".toByteArray()
        val sha = ApkVerification.sha256Hex(tmp.newFile().apply { writeBytes(bytes) })
        val release = AppRelease("2.0.0", "https://github.com/x/y/a.apk", "", sizeBytes = bytes.size.toLong(), sha256 = sha)
        val file = File(tmp.root, "ok.apk")

        UpdateChecker(FakeTransport(null, bytes)).download(release, file)

        assertTrue(file.isFile)
    }

    @Test
    fun `url allowlist requires https on a github host without credentials`() {
        assertTrue(ApkVerification.isAllowedApkUrl("https://objects.githubusercontent.com/a"))
        assertFalse(ApkVerification.isAllowedApkUrl("http://github.com/a"))
        assertFalse(ApkVerification.isAllowedApkUrl("https://github.com.evil.example/a"))
        assertFalse(ApkVerification.isAllowedApkUrl("https://u:p@github.com/a"))
        assertFalse(ApkVerification.isAllowedApkUrl("not a url"))
    }

    @Test
    fun `release digest and size are parsed`() {
        val sha = "a".repeat(64)
        val release = AppReleases.parseLatest(
            json(extra = ""","size":1234,"digest":"sha256:$sha""""),
            "ai-daily-memory-phone.apk",
        )!!
        assertEquals(1234L, release.sizeBytes)
        assertEquals(sha, release.sha256)
    }

    @Test
    fun `a ready download is never downgraded by a later check`() {
        val release = AppRelease("2.0.0", "https://github.com/a", "")
        val ready = UpdateState.Ready(release)
        assertEquals(ready, UpdateViewModel.reduceCheck(ready, UpdateCheck.Failed, "1.0.0"))
        assertEquals(UpdateState.CheckFailed, UpdateViewModel.reduceCheck(UpdateState.Checking, UpdateCheck.Failed, "1.0.0"))
        assertEquals(UpdateState.CheckFailed, UpdateViewModel.reduceCheck(UpdateState.Checking, UpdateCheck.UpToDate, null))
        assertEquals(UpdateState.UpToDate("1.0.0"), UpdateViewModel.reduceCheck(UpdateState.Checking, UpdateCheck.UpToDate, "1.0.0"))
    }
}
