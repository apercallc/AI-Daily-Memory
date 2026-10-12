package com.apercallc.aidailymemory.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A published app release; [versionName] is the tag without its "v" prefix, e.g. "0.1.1". */
data class AppRelease(val versionName: String, val apkUrl: String, val notes: String)

/**
 * Release-metadata parsing and version comparison. Pure — no Android or network types — so
 * the update decision is plain unit-testable logic (the illegal paths matter as much as the
 * legal ones: a bad payload must mean "no update", never a broken screen).
 */
object AppReleases {
    /**
     * Parses the body of `GET /repos/{owner}/{repo}/releases/latest`. Null when the payload
     * is malformed or the release carries no APK with [assetName] — GitHub pre-releases and
     * source-only tags included.
     */
    fun parseLatest(body: String, assetName: String): AppRelease? = try {
        val release = JSONObject(body)
        val tag = release.optString("tag_name")
        val assets: JSONArray = release.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length())
            .asSequence()
            .map { index -> assets.getJSONObject(index) }
            .firstOrNull { it.optString("name") == assetName }
            ?: return null
        val url = apk.optString("browser_download_url")
        if (tag.isEmpty() || url.isEmpty()) {
            null
        } else {
            AppRelease(
                versionName = tag.removePrefix("v"),
                apkUrl = url,
                notes = release.optString("body"),
            )
        }
    } catch (_: JSONException) {
        null
    }

    /**
     * True only when [latest] is strictly newer than [current] ("0.2.0" > "0.1.9"). Dotted
     * integers compared component-wise, missing components zero. Anything unparseable is
     * "not newer": the app never nags about a version it cannot understand.
     */
    fun isNewer(current: String, latest: String): Boolean {
        val here = components(current) ?: return false
        val there = components(latest) ?: return false
        for (index in 0 until maxOf(here.size, there.size)) {
            val left = here.getOrElse(index) { 0 }
            val right = there.getOrElse(index) { 0 }
            if (left != right) return right > left
        }
        return false
    }

    private fun components(version: String): List<Int>? {
        val parts = version.removePrefix("v").split('.')
        if (parts.isEmpty() || parts.any { part -> part.isEmpty() || part.any { !it.isDigit() } }) {
            return null
        }
        return parts.map { it.toInt() }
    }
}
