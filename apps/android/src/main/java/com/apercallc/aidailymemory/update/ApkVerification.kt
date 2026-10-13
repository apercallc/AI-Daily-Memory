package com.apercallc.aidailymemory.update

import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * Guards for the self-update path. An APK that will be handed to the system installer must come
 * from GitHub over https, and must match the size and SHA-256 the release advertises when it
 * advertises them. Pure JVM so every branch is unit-testable.
 */
object ApkVerification {
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    /** True for an https URL on a GitHub release host, without embedded credentials. */
    fun isAllowedApkUrl(url: String): Boolean {
        val uri = try {
            URI(url.trim())
        } catch (_: URISyntaxException) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            host in ALLOWED_HOSTS
    }

    sealed interface Result {
        data object Ok : Result
        data object SizeMismatch : Result
        data object HashMismatch : Result
    }

    /**
     * Checks [file] against what the release published. Unknown (null) expectations are not
     * checked, so older releases without a digest still install; known ones must match.
     */
    fun verify(file: File, expectedSize: Long?, expectedSha256: String?): Result {
        if (expectedSize != null && file.length() != expectedSize) return Result.SizeMismatch
        if (expectedSha256 != null && sha256Hex(file) != expectedSha256.lowercase()) return Result.HashMismatch
        return Result.Ok
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val BUFFER = 64 * 1024
}
