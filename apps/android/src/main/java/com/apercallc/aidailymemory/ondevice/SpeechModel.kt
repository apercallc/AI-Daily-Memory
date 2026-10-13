package com.apercallc.aidailymemory.ondevice

import com.apercallc.aidailymemory.platform.logFailure
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The free speech model the phone downloads once (ADR-0026). Size and SHA-256 are pinned, so a
 * changed or tampered file is refused no matter where it came from.
 */
data class SpeechModelSpec(
    val id: String,
    val language: String,
    val url: String,
    val zipBytes: Long,
    val sha256: String,
    /** Upper bound on what the archive may unpack to; a bigger archive is refused. */
    val maxUnpackedBytes: Long,
    /** Folder inside the archive that holds the model. */
    val archiveRoot: String,
    /** A file that must exist in the unpacked model for it to count as installed. */
    val marker: String,
) {
    companion object {
        /** Vosk small English model (Apache-2.0), 40 MB download. */
        val VoskSmallEnglish = SpeechModelSpec(
            id = "vosk-model-small-en-us-0.15",
            language = "en",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            zipBytes = 41_205_931L,
            sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
            maxUnpackedBytes = 80L * 1024 * 1024,
            archiveRoot = "vosk-model-small-en-us-0.15",
            marker = "am/final.mdl",
        )
    }
}

enum class ModelFailure { OFFLINE, SERVER, CORRUPT, STORAGE }

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val percent: Int) : ModelState
    data object Installed : ModelState
    data class Failed(val reason: ModelFailure) : ModelState
}

/**
 * Downloads, verifies and unpacks the speech model into app-private storage. Install is
 * all-or-nothing: the model folder appears only after the checksum and unpack both succeed, so a
 * killed download never leaves a half model that looks installed.
 */
class OnDeviceSpeechModel(
    private val baseDir: File,
    val spec: SpeechModelSpec = SpeechModelSpec.VoskSmallEnglish,
    /** Space the system could free for this app, as lint requires (clears other apps' caches). */
    private val freeBytes: () -> Long,
    private val open: (String) -> InputStream = ::openHttps,
) {
    private val installDir get() = File(baseDir, spec.id)
    private val _state = MutableStateFlow<ModelState>(initialState())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value == ModelState.Installed

    /** Where the recognizer loads the model from; only valid while [isReady]. */
    val modelPath: File get() = installDir

    private fun initialState(): ModelState {
        // Leftovers of an interrupted install are never reused.
        File(baseDir, "${spec.id}.part").delete()
        File(baseDir, "${spec.id}.tmp").deleteRecursively()
        return if (File(installDir, spec.marker).isFile) ModelState.Installed else ModelState.NotInstalled
    }

    /** Blocking work on IO; safe to cancel (the partial download is removed). */
    suspend fun download() = withContext(Dispatchers.IO) {
        if (_state.value == ModelState.Installed || _state.value is ModelState.Downloading) return@withContext
        _state.value = ModelState.Downloading(0)
        val part = File(baseDir, "${spec.id}.part")
        val tmp = File(baseDir, "${spec.id}.tmp")
        try {
            baseDir.mkdirs()
            if (freeBytes() < spec.zipBytes + spec.maxUnpackedBytes) throw StorageFull()
            fetch(part)
            unpack(part, tmp)
            if (!File(tmp, spec.marker).isFile) throw Corrupt()
            installDir.deleteRecursively()
            if (!tmp.renameTo(installDir)) throw StorageFull()
            _state.value = ModelState.Installed
        } catch (e: CancellationException) {
            _state.value = ModelState.NotInstalled
            throw e
        } catch (e: Failure) {
            _state.value = ModelState.Failed(e.reason)
        } catch (e: IOException) {
            logFailure(TAG, "model_download_failed", e, warning = true)
            _state.value = ModelState.Failed(ModelFailure.OFFLINE)
        } finally {
            part.delete()
            tmp.deleteRecursively()
        }
    }

    /** Frees the storage; transcription waits until the model is downloaded again. */
    fun delete() {
        installDir.deleteRecursively()
        _state.value = ModelState.NotInstalled
    }

    private suspend fun fetch(to: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        open(spec.url).use { input ->
            to.outputStream().use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > spec.zipBytes) throw Corrupt()
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    _state.value = ModelState.Downloading((total * PERCENT / spec.zipBytes).toInt())
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (total != spec.zipBytes || actual != spec.sha256) throw Corrupt()
    }

    private suspend fun unpack(zip: File, into: File) {
        into.deleteRecursively()
        into.mkdirs()
        val root = into.canonicalFile
        val prefix = spec.archiveRoot + "/"
        var unpacked = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zin.nextEntry ?: break
                if (!entry.name.startsWith(prefix) || entry.isDirectory) continue
                val target = File(root, entry.name.removePrefix(prefix)).canonicalFile
                // Zip-slip: nothing may land outside the model folder.
                if (!target.path.startsWith(root.path + File.separator)) throw Corrupt()
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = zin.read(buffer)
                        if (n < 0) break
                        unpacked += n
                        if (unpacked > spec.maxUnpackedBytes) throw Corrupt()
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    private sealed class Failure(val reason: ModelFailure) : Exception()
    private class Corrupt : Failure(ModelFailure.CORRUPT)
    private class StorageFull : Failure(ModelFailure.STORAGE)

    companion object {
        private const val TAG = "OnDeviceSpeechModel"
        private const val BUFFER = 64 * 1024
        private const val PERCENT = 100L
        private const val TIMEOUT_MS = 30_000

        /** HTTPS only, no redirects: the pinned checksum protects the content, this the channel. */
        private fun openHttps(address: String): InputStream {
            val url = URL(address)
            require(url.protocol == "https") { "https only" }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw ServerRefused()
            }
            return conn.inputStream
        }

        private class ServerRefused : IOException("model host refused")
    }
}
