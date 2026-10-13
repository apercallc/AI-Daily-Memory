package com.apercallc.aidailymemory.update

import android.util.Log
import com.apercallc.aidailymemory.platform.logFailure
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** How update checks and APK downloads reach GitHub Releases. Blocking: call on IO. */
interface ReleaseTransport {
    /** Latest-release JSON, or null when offline / rate-limited / server error. */
    fun latestReleaseJson(): String?

    /** Streams the APK to [destination]; throws [IOException] on anything but a full 2xx. */
    fun downloadApk(url: String, destination: File)
}

/**
 * Plain HttpURLConnection, like the backend client: two calls per release don't justify an
 * HTTP library. Anonymous access — the repo is public, and one check per app start is far
 * below GitHub's unauthenticated rate limits.
 */
class UpdateClient(
    private val releasesApiUrl: String =
        "https://api.github.com/repos/apercallc/AI-Daily-Memory/releases/latest",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : ReleaseTransport {
    override fun latestReleaseJson(): String? {
        val connection = open(releasesApiUrl)
        return try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val status = connection.responseCode
            if (status !in 200..299) {
                // 403/429 is GitHub's rate limit; keep it apart from "no update" in the logs.
                Log.w(TAG, "update_check_status status=$status")
                return null
            }
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } catch (e: IOException) {
            logFailure(TAG, "update_check_failed", e, warning = true)
            null
        } finally {
            connection.disconnect()
        }
    }

    override fun downloadApk(url: String, destination: File) {
        val connection = openFollowingAllowedRedirects(url)
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("download_status_${connection.responseCode}")
            }
            val expected = connection.contentLengthLong
            destination.parentFile?.mkdirs()
            // Download to a .part file so a killed process can never leave a truncated APK
            // that a later tap would hand to the system installer.
            val partial = File(destination.parentFile, destination.name + ".part")
            try {
                val copied = connection.inputStream.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                // A connection cut mid-stream can end the read without an error.
                if (expected >= 0 && copied != expected) throw IOException("download_truncated")
                if (!partial.renameTo(destination)) throw IOException("download_rename_failed")
            } finally {
                partial.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Follows redirects by hand so every hop stays https on a GitHub release host. */
    private fun openFollowingAllowedRedirects(startUrl: String): HttpURLConnection {
        var url = startUrl
        repeat(MAX_REDIRECTS) {
            if (!ApkVerification.isAllowedApkUrl(url)) throw IOException("download_url_not_allowed")
            val connection = open(url)
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code !in REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            url = location ?: throw IOException("download_redirect_missing")
        }
        throw IOException("download_too_many_redirects")
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        return connection
    }

    private companion object {
        const val TAG = "UpdateClient"
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** Outcome of an update check. A failed check is never reported as "up to date". */
sealed interface UpdateCheck {
    data class Newer(val release: AppRelease) : UpdateCheck
    data object UpToDate : UpdateCheck
    data object Failed : UpdateCheck
}

/**
 * The update decision. [UpdateCheck.Newer] only when the release is strictly newer than the
 * installed version; offline, rate-limited and unparseable responses are [UpdateCheck.Failed].
 * A well-formed release with no matching APK asset means nothing to install: up to date.
 */
class UpdateChecker(
    private val transport: ReleaseTransport,
    private val assetName: String = ASSET_NAME,
) {
    fun check(versionName: String): UpdateCheck {
        val body = transport.latestReleaseJson() ?: return UpdateCheck.Failed
        if (!isJsonObject(body)) return UpdateCheck.Failed
        val release = AppReleases.parseLatest(body, assetName) ?: return UpdateCheck.UpToDate
        return if (AppReleases.isNewer(versionName, release.versionName)) {
            UpdateCheck.Newer(release)
        } else {
            UpdateCheck.UpToDate
        }
    }

    /**
     * Downloads and verifies the APK. Throws [IOException] (and removes the file) when the URL
     * is not an allowed GitHub release URL or the size / SHA-256 do not match the release.
     */
    fun download(release: AppRelease, destination: File) {
        if (!ApkVerification.isAllowedApkUrl(release.apkUrl)) throw IOException("download_url_not_allowed")
        transport.downloadApk(release.apkUrl, destination)
        val result = ApkVerification.verify(destination, release.sizeBytes, release.sha256)
        if (result != ApkVerification.Result.Ok) {
            destination.delete()
            throw IOException("download_verification_failed")
        }
    }

    private fun isJsonObject(body: String): Boolean = try {
        org.json.JSONObject(body)
        true
    } catch (_: org.json.JSONException) {
        false
    }

    companion object {
        /** Stable across releases: the website links `/releases/latest/download/<name>`. */
        const val ASSET_NAME = "ai-daily-memory-phone.apk"

        /** Same stable-name rule for the Wear companion APK. */
        const val WEAR_ASSET_NAME = "ai-daily-memory-wear.apk"
    }
}
