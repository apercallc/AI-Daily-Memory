package com.apercallc.aidailymemory.capture

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.platform.DeviceClock
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.audio.AdtsScanner
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.segment.SegmentLifecycle
import com.apercallc.aidailymemory.recording.segment.SegmentState
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors SegmentStoreTest.kt's setup: a software Tink cipher stands in for the Android
 * Keystore, which Robolectric doesn't have. */
@RunWith(RobolectricTestRunner::class)
class RetainedAudioExporterTest {
    private lateinit var db: CaptureDatabase
    private lateinit var segmentDirectory: File
    private lateinit var exportDirectory: File
    private lateinit var store: SegmentStore
    private lateinit var exporter: RetainedAudioExporter
    private val session = SessionId("session-1")
    private val format = SegmentFormat("aac-lc-adts", 16_000, 1)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        segmentDirectory = File(context.cacheDir, "segments-export-test").apply { deleteRecursively() }
        exportDirectory = File(context.cacheDir, "audio-export-test").apply { deleteRecursively() }
        StreamingAeadConfig.register()
        val cipher = SegmentCipher(
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
                .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java),
        )
        store = SegmentStore(
            directory = segmentDirectory,
            dao = db.segments(),
            cipher = { cipher },
            lifecycle = SegmentLifecycle(recoveryWindowMs = 0),
            ids = UuidV7Generator(),
            clock = DeviceClock(context.contentResolver as ContentResolver),
        )
        exporter = RetainedAudioExporter(db.segments(), store, exportDirectory)
    }

    @After
    fun tearDown() {
        db.close()
        segmentDirectory.deleteRecursively()
        exportDirectory.deleteRecursively()
    }

    private suspend fun seal(frames: Int, continuesPrevious: Boolean = false) =
        store.seal(
            store.begin(session, continuesPrevious, format).apply {
                repeat(frames) { i ->
                    writeFrame(ByteBuffer.wrap(ByteArray(100) { (it + i).toByte() }), 100)
                }
            },
        )

    @Test
    fun `no retained segments means no export`() = runTest {
        assertNull(exporter.export(session.value))
        assertFalse(exporter.hasRetainedAudio(session.value))
    }

    @Test
    fun `a sealed session's audio decrypts into one playable combined stream`() = runTest {
        seal(frames = 20)
        seal(frames = 15, continuesPrevious = true)

        assertTrue(exporter.hasRetainedAudio(session.value))
        val exported = exporter.export(session.value)

        assertTrue(exported != null)
        val scan = AdtsScanner.scan(exported.readBytes())
        assertEquals(35, scan.completeFrames)
        assertFalse(scan.corrupt)
    }

    @Test
    fun `acknowledged audio still exports, but deleted audio does not`() = runTest {
        val acknowledged = seal(frames = 5)
        db.segments().update(acknowledged.copy(state = SegmentState.ACKNOWLEDGED.name))
        val deleted = seal(frames = 5, continuesPrevious = true)
        db.segments().update(deleted.copy(state = SegmentState.DELETED.name))

        val exported = exporter.export(session.value)

        assertTrue(exported != null)
        assertEquals(5, AdtsScanner.scan(exported.readBytes()).completeFrames)
    }

    @Test
    fun `quarantined audio is never exported`() = runTest {
        // An empty segment is quarantined on seal (ADR-0007) — nothing to retain or export.
        store.seal(store.begin(session, false, format))

        assertNull(exporter.export(session.value))
    }

    @Test
    fun `re-exporting a session overwrites, never appends to, its earlier export`() = runTest {
        seal(frames = 10)
        val first = exporter.export(session.value)
        val second = exporter.export(session.value)

        assertEquals(first!!.absolutePath, second!!.absolutePath)
        assertEquals(10, AdtsScanner.scan(second.readBytes()).completeFrames)
    }

    @Test
    fun `an unreadable segment leaves no partial export file behind`() = runTest {
        val sealed = seal(frames = 5)
        val file = File(segmentDirectory, sealed.fileName)
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertNull(exporter.export(session.value))
        assertFalse(File(exportDirectory, "${session.value}.aac").exists())
    }

    @Test
    fun `audio for another session is never included`() = runTest {
        seal(frames = 4)
        val otherSession = SessionId("session-2")
        store.seal(store.begin(otherSession, false, format).apply {
            writeFrame(ByteBuffer.wrap(ByteArray(100)), 100)
        })

        val exported = exporter.export(session.value)

        assertEquals(4, AdtsScanner.scan(exported!!.readBytes()).completeFrames)
        assertFalse(exporter.hasRetainedAudio("nonexistent-session"))
    }
}
