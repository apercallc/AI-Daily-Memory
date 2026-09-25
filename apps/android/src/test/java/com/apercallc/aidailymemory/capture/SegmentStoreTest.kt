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
import com.apercallc.aidailymemory.recording.segment.SegmentDefect
import com.apercallc.aidailymemory.recording.segment.SegmentLifecycle
import com.apercallc.aidailymemory.recording.segment.SegmentState
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SegmentStoreTest {
    private lateinit var db: CaptureDatabase
    private lateinit var directory: File
    private lateinit var cipher: SegmentCipher
    private lateinit var store: SegmentStore
    private val session = SessionId("session-1")
    private val format = SegmentFormat("aac-lc-adts", 16_000, 1)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        directory = File(context.cacheDir, "segments-test").apply { deleteRecursively() }
        // The production keyset lives behind the Android Keystore, which Robolectric lacks.
        // The streaming AEAD itself is the same primitive.
        StreamingAeadConfig.register()
        cipher = SegmentCipher(
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
                .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java),
        )
        store = SegmentStore(
            directory = directory,
            dao = db.segments(),
            cipher = { cipher },
            lifecycle = SegmentLifecycle(recoveryWindowMs = 0),
            ids = UuidV7Generator(),
            clock = DeviceClock(context.contentResolver as ContentResolver),
        )
    }

    @After
    fun tearDown() {
        db.close()
        directory.deleteRecursively()
    }

    private fun OpenSegment.writeFrames(count: Int, size: Int = 200) {
        repeat(count) { i ->
            writeFrame(ByteBuffer.wrap(ByteArray(size) { (it + i).toByte() }), size)
        }
    }

    @Test
    fun `a sealed segment is encrypted, checksummed over plaintext, and decodable`() = runTest {
        val open = store.begin(session, continuesPrevious = false, format)
        assertEquals(SegmentState.WRITING.name, db.segments().get(open.entity.segmentId)?.state)
        open.writeFrames(count = 50)

        val sealed = store.seal(open)

        assertEquals(SegmentState.SEALED.name, sealed.state)
        assertEquals(50L * 1024 * 1000 / 16_000, sealed.durationMs)
        val file = File(directory, sealed.fileName)
        assertTrue(file.exists())
        assertFalse(File(directory, "${sealed.segmentId}${SegmentStore.PART_SUFFIX}").exists())
        assertEquals(file.length(), sealed.byteSize)

        val plaintext = cipher.decrypting(file.inputStream(), sealed.segmentId).use { it.readBytes() }
        val scan = AdtsScanner.scan(plaintext)
        assertEquals(50, scan.completeFrames)
        assertFalse(scan.corrupt)
        val sha = MessageDigest.getInstance("SHA-256").digest(plaintext)
            .joinToString("") { "%02x".format(it) }
        assertEquals(sha, sealed.sha256)
        // Ciphertext must not contain the plaintext frames.
        assertFalse(file.readBytes().toList().windowed(64).contains(plaintext.take(64)))
    }

    @Test
    fun `ciphertext is bound to its segment id`() = runTest {
        val open = store.begin(session, continuesPrevious = false, format)
        open.writeFrames(count = 3)
        val sealed = store.seal(open)
        val file = File(directory, sealed.fileName)

        assertFailsWith<IOException> {
            cipher.decrypting(file.inputStream(), "some-other-segment").use { it.readBytes() }
        }
    }

    @Test
    fun `sequences continue across segments of a session`() = runTest {
        val first = store.begin(session, continuesPrevious = false, format)
        first.writeFrames(1)
        store.seal(first)
        val second = store.begin(session, continuesPrevious = true, format)

        assertEquals(0, first.entity.sequence)
        assertEquals(1, second.entity.sequence)
        assertTrue(second.entity.continuesPrevious)
    }

    @Test
    fun `an empty segment is quarantined, not counted as audio`() = runTest {
        val sealed = store.seal(store.begin(session, continuesPrevious = false, format))

        assertEquals(SegmentState.QUARANTINED.name, sealed.state)
        assertEquals(SegmentDefect.EMPTY.name, sealed.defect)
    }

    @Test
    fun `segments left writing by a dead process are quarantined and their tails removed`() =
        runTest {
            val torn = store.begin(session, continuesPrevious = false, format)
            torn.writeFrames(count = 10)
            val part = File(directory, "${torn.entity.segmentId}${SegmentStore.PART_SUFFIX}")
            assertTrue(part.exists())

            assertEquals(1, store.abandonUnfinished())

            val row = checkNotNull(db.segments().get(torn.entity.segmentId))
            assertEquals(SegmentState.QUARANTINED.name, row.state)
            assertEquals(SegmentDefect.INCOMPLETE.name, row.defect)
            assertFalse(part.exists())
            assertEquals(0, store.abandonUnfinished())
        }

    @Test
    fun `a key failure leaves no phantom segment row`() = runTest {
        val broken = SegmentStore(
            directory = directory,
            dao = db.segments(),
            cipher = { throw EncryptionUnavailableException("keystore gone") },
            lifecycle = SegmentLifecycle(recoveryWindowMs = 0),
            ids = UuidV7Generator(),
            clock = DeviceClock(
                ApplicationProvider.getApplicationContext<Context>().contentResolver,
            ),
        )

        assertFailsWith<EncryptionUnavailableException> {
            broken.begin(session, continuesPrevious = false, format)
        }
        assertTrue(db.segments().forSession(session.value).isEmpty())
    }

    @Test
    fun `sealing twice is refused instead of corrupting the file`() = runTest {
        val open = store.begin(session, continuesPrevious = false, format)
        open.writeFrames(2)
        store.seal(open)

        assertTrue(open.isClosed)
        assertFailsWith<IllegalStateException> { store.seal(open) }
    }

    /** The crash window: file closed, fsynced, and renamed, but the SEALED row never written. */
    private suspend fun crashAfterRename(frames: Int): OpenSegment {
        val open = store.begin(session, continuesPrevious = false, format)
        open.writeFrames(count = frames)
        open.close()
        val part = File(directory, "${open.entity.segmentId}${SegmentStore.PART_SUFFIX}")
        assertTrue(part.renameTo(File(directory, open.entity.fileName)))
        return open
    }

    @Test
    fun `a complete segment renamed just before a crash is recovered, not thrown away`() = runTest {
        // Regression: recovery used to mark it INCOMPLETE and orphan a perfectly good file.
        val open = crashAfterRename(frames = 40)

        val lost = store.abandonUnfinished()

        assertEquals(0, lost)
        val row = db.segments().get(open.entity.segmentId)!!
        assertEquals(SegmentState.SEALED.name, row.state)
        assertEquals(40L * 1024 * 1000 / 16_000, row.durationMs)
        val file = File(directory, row.fileName)
        val plaintext = cipher.decrypting(file.inputStream(), row.segmentId).use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(plaintext)
            .joinToString("") { "%02x".format(it) }
        assertEquals(sha, row.sha256)
    }

    @Test
    fun `a renamed segment that fails verification is quarantined and deleted`() = runTest {
        val open = crashAfterRename(frames = 10)
        val file = File(directory, open.entity.fileName)
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        val lost = store.abandonUnfinished()

        assertEquals(1, lost)
        assertEquals(SegmentState.QUARANTINED.name, db.segments().get(open.entity.segmentId)?.state)
        assertFalse(file.exists())
    }

    @Test
    fun `retention deletes acknowledged audio by the user's setting and never anything else`() =
        runTest {
            // Regression: the retention setting was never applied (security review).
            val kept = store.seal(store.begin(session, false, format).apply { writeFrames(5) })
            val acked = store.seal(store.begin(session, false, format).apply { writeFrames(5) })
            val ackedAt = 1_000L
            db.segments().update(
                acked.copy(state = SegmentState.ACKNOWLEDGED.name, acknowledgedAtEpochMs = ackedAt),
            )
            val day = 24 * 60 * 60 * 1000L

            // Keep-until-deleted: nothing goes.
            assertEquals(0, store.applyRetention(ackedAt + 365 * day, AudioRetention.KEEP_UNTIL_DELETED))
            // Keep 7 days, one day later: still kept.
            assertEquals(0, store.applyRetention(ackedAt + day, AudioRetention.KEEP_7_DAYS))
            // Transcripts only, after the recovery window: the acknowledged file goes.
            assertEquals(1, store.applyRetention(ackedAt + day, AudioRetention.TRANSCRIPT_ONLY))

            assertEquals(SegmentState.DELETED.name, db.segments().get(acked.segmentId)?.state)
            assertFalse(File(directory, acked.fileName).exists())
            // The unacknowledged segment is untouched, file and row.
            assertEquals(SegmentState.SEALED.name, db.segments().get(kept.segmentId)?.state)
            assertTrue(File(directory, kept.fileName).exists())
        }
}
