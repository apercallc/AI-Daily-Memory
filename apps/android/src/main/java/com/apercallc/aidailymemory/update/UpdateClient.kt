package com.apercallc.aidailymemory.update

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
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    override fun downloadApk(url: String, destination: File) {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("download_status_${connection.responseCode}")
            }
            destination.parentFile?.mkdirs()
            // Download to a .part file so a killed process can never leave a truncated APK
            // that a later tap would hand to the system installer.
            val partial = File(destination.parentFile, destination.name + ".part")
            try {
                connection.inputStream.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                if (!partial.renameTo(destination)) throw IOException("download_rename_failed")
            } finally {
                partial.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        // Asset URLs redirect within HTTPS, which HttpURLConnection follows.
        connection.instanceFollowRedirects = true
        return connection
    }
}

/**
 * The update decision. Returns the release only when it is strictly newer than the
 * installed [versionName]; every failure mode (offline, malformed, rate-limited) is null.
 */
class UpdateChecker(
    private val transport: ReleaseTransport,
    private val assetName: String = ASSET_NAME,
) {
    fun check(versionName: String): AppRelease? {
        val body = transport.latestReleaseJson() ?: return null
        val release = AppReleases.parseLatest(body, assetName) ?: return null
        return if (AppReleases.isNewer(versionName, release.versionName)) release else null
    }

    fun download(release: AppRelease, destination: File) =
        transport.downloadApk(release.apkUrl, destination)

    companion object {
        /** Stable across releases: the website links `/releases/latest/download/<name>`. */
        const val ASSET_NAME = "ai-daily-memory-phone.apk"

        /** Same stable-name rule for the Wear companion APK. */
        const val WEAR_ASSET_NAME = "ai-daily-memory-wear.apk"
    }
}
