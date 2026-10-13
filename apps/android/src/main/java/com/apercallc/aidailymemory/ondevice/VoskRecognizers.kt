package com.apercallc.aidailymemory.ondevice

import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.processing.ProcessingException
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Vosk (Kaldi) recognizers over the downloaded model. The model is loaded on first use and held
 * until [close], so one processing run pays for loading once.
 */
class VoskRecognizers(private val modelDir: () -> File?) : WordRecognizerFactory {
    private var model: Model? = null

    @Synchronized
    override fun open(): WordRecognizer {
        val loaded = model ?: load().also { model = it }
        return try {
            VoskWordRecognizer(Recognizer(loaded, RATE_HZ).apply { setWords(true) })
        } catch (e: IOException) {
            logFailure(TAG, "recognizer_open_failed", e)
            throw ProcessingException("speech_engine_unavailable", retryable = true, cause = e)
        }
    }

    private fun load(): Model {
        val dir = modelDir() ?: throw ProcessingException("speech_model_missing", retryable = true)
        return try {
            Model(dir.path)
        } catch (e: IOException) {
            logFailure(TAG, "model_load_failed", e)
            throw ProcessingException("speech_model_unreadable", retryable = true, cause = e)
        }
    }

    @Synchronized
    override fun close() {
        model?.close()
        model = null
    }

    private companion object {
        const val TAG = "VoskRecognizers"
        const val RATE_HZ = 16_000f
    }
}

private class VoskWordRecognizer(private val recognizer: Recognizer) : WordRecognizer {
    private val words = mutableListOf<RecognizedWord>()

    override fun accept(pcm: ShortArray, count: Int) {
        if (recognizer.acceptWaveForm(pcm, count)) collect(recognizer.result)
    }

    override fun finish(): List<RecognizedWord> {
        collect(recognizer.finalResult)
        return words.toList()
    }

    override fun close() = recognizer.close()

    private fun collect(json: String) {
        words += VoskResult.words(json)
    }
}

/** Reads the `result` array of a Vosk JSON reply (seconds in, milliseconds out). */
internal object VoskResult {
    private val parser = Json { ignoreUnknownKeys = true }

    fun words(json: String): List<RecognizedWord> {
        val root = try {
            parser.parseToJsonElement(json).jsonObject
        } catch (e: IllegalArgumentException) {
            throw ProcessingException("invalid_provider_result", retryable = false, cause = e)
        }
        val array = root["result"]?.jsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val word = element as? JsonObject ?: return@mapNotNull null
            val text = word["word"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val start = word["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = word["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (text.isEmpty()) return@mapNotNull null
            RecognizedWord(
                text = text,
                startMs = (start * MS).toLong(),
                endMs = (end * MS).toLong().coerceAtLeast((start * MS).toLong()),
                confidence = word["conf"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0),
            )
        }
    }

    private const val MS = 1000.0
}
