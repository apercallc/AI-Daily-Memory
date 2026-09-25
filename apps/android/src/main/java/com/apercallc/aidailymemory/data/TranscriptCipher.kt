package com.apercallc.aidailymemory.data

import com.apercallc.aidailymemory.platform.logFailure
import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import org.json.JSONException
import org.json.JSONObject

/** A transcript line opened for display. */
data class TranscriptText(
    val key: String,
    val startEpochMs: Long,
    val zoneId: String,
    val text: String,
    val speaker: String?,
)

/**
 * Seals transcript words at rest (SECURITY.md): AES-256-GCM under a Keystore-wrapped key, with
 * the upload id and line index as associated data, so a sealed line can't be moved to another
 * transcript or position. Opening happens only for display.
 */
class TranscriptCipher(private val aead: () -> Aead) {
    /** @throws GeneralSecurityException if the key is unusable (nothing is stored then). */
    fun seal(uploadId: String, idx: Int, text: String, speaker: String?): ByteArray {
        val plain = JSONObject().put("text", text).put("speaker", speaker ?: JSONObject.NULL).toString()
        return aead().encrypt(plain.toByteArray(Charsets.UTF_8), ad(uploadId, idx))
    }

    /** Null (logged) if the line can't be opened, e.g. after a Keystore reset. */
    fun open(line: TranscriptLine): TranscriptText? = try {
        val json = JSONObject(String(aead().decrypt(line.sealed, ad(line.uploadId, line.idx)), Charsets.UTF_8))
        TranscriptText(
            key = "${line.uploadId}:${line.idx}",
            startEpochMs = line.startEpochMs,
            zoneId = line.zoneId,
            text = json.getString("text"),
            speaker = if (json.isNull("speaker")) null else json.getString("speaker"),
        )
    } catch (e: GeneralSecurityException) {
        logFailure(TAG, "transcript_line_unreadable", e, warning = true)
        null
    } catch (e: JSONException) {
        logFailure(TAG, "transcript_line_invalid", e, warning = true)
        null
    }

    private fun ad(uploadId: String, idx: Int) = "aidm:transcript:v1:$uploadId:$idx".toByteArray(Charsets.UTF_8)

    private companion object {
        const val TAG = "TranscriptCipher"
    }
}
