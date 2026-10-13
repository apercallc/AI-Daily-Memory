package com.apercallc.aidailymemory.ondevice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeechModelTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("speech-model-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun specFor(bytes: ByteArray, maxUnpacked: Long = 10_000L) = SpeechModelSpec(
        id = "test-model",
        language = "en",
        url = "https://example.test/model.zip",
        zipBytes = bytes.size.toLong(),
        sha256 = sha(bytes),
        maxUnpackedBytes = maxUnpacked,
        archiveRoot = "root",
        marker = "am/final.mdl",
    )

    private fun model(
        spec: SpeechModelSpec,
        open: (String) -> InputStream,
        free: Long = Long.MAX_VALUE,
    ) = OnDeviceSpeechModel(dir, spec, freeBytes = { free }, open = open)

    private val good = zip("root/am/final.mdl" to "weights", "root/conf/model.conf" to "x")

    @Test
    fun `a good download is verified, unpacked and installed`() = runBlocking {
        val model = model(specFor(good), { ByteArrayInputStream(good) })
        assertEquals(ModelState.NotInstalled, model.state.value)

        model.download()

        assertEquals(ModelState.Installed, model.state.value)
        assertTrue(model.isReady)
        assertEquals("weights", File(model.modelPath, "am/final.mdl").readText())
        assertFalse(File(dir, "test-model.part").exists())
        assertFalse(File(dir, "test-model.tmp").exists())
    }

    @Test
    fun `an installed model is found again after a restart`() = runBlocking {
        model(specFor(good), { ByteArrayInputStream(good) }).download()

        val reopened = model(specFor(good), { error("no network needed") })

        assertEquals(ModelState.Installed, reopened.state.value)
    }

    @Test
    fun `a file with the wrong checksum is thrown away and nothing is installed`() = runBlocking {
        val tampered = good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val model = model(specFor(good), { ByteArrayInputStream(tampered) })

        model.download()

        assertEquals(ModelState.Failed(ModelFailure.CORRUPT), model.state.value)
        assertFalse(File(dir, "test-model").exists())
        assertFalse(File(dir, "test-model.part").exists())
    }

    @Test
    fun `a download longer than the pinned size is stopped`() = runBlocking {
        val model = model(specFor(good), { ByteArrayInputStream(good + ByteArray(50)) })

        model.download()

        assertEquals(ModelState.Failed(ModelFailure.CORRUPT), model.state.value)
    }

    @Test
    fun `an archive entry that escapes the model folder is refused`() = runBlocking {
        val evil = zip("root/am/final.mdl" to "weights", "root/../../escape.txt" to "pwned")
        val model = model(specFor(evil), { ByteArrayInputStream(evil) })

        model.download()

        assertEquals(ModelState.Failed(ModelFailure.CORRUPT), model.state.value)
        assertFalse(File(dir.parentFile, "escape.txt").exists())
        assertFalse(File(dir, "escape.txt").exists())
    }

    @Test
    fun `an archive that unpacks bigger than allowed is refused`() = runBlocking {
        val bomb = zip("root/am/final.mdl" to "x".repeat(5_000))
        val model = model(specFor(bomb, maxUnpacked = 1_000L), { ByteArrayInputStream(bomb) })

        model.download()

        assertEquals(ModelState.Failed(ModelFailure.CORRUPT), model.state.value)
        assertFalse(File(dir, "test-model").exists())
    }

    @Test
    fun `an archive without the model file is not installed`() = runBlocking {
        val empty = zip("root/README" to "nothing here")
        val model = model(specFor(empty), { ByteArrayInputStream(empty) })

        model.download()

        assertEquals(ModelState.Failed(ModelFailure.CORRUPT), model.state.value)
    }

    @Test
    fun `not enough free space fails before downloading anything`() = runBlocking {
        var opened = false
        val model = model(specFor(good), { opened = true; ByteArrayInputStream(good) }, free = 10L)

        model.download()

        assertEquals(ModelState.Failed(ModelFailure.STORAGE), model.state.value)
        assertFalse(opened)
    }

    @Test
    fun `a network failure is reported as offline and can be retried`() = runBlocking {
        var fail = true
        val model = model(specFor(good), { if (fail) throw IOException("no route") else ByteArrayInputStream(good) })

        model.download()
        assertEquals(ModelState.Failed(ModelFailure.OFFLINE), model.state.value)

        fail = false
        model.download()
        assertEquals(ModelState.Installed, model.state.value)
    }

    @Test
    fun `deleting the model frees it and goes back to not installed`() = runBlocking {
        val model = model(specFor(good), { ByteArrayInputStream(good) })
        model.download()

        model.delete()

        assertEquals(ModelState.NotInstalled, model.state.value)
        assertFalse(File(dir, "test-model").exists())
    }

    @Test
    fun `leftovers of an interrupted install are cleaned on start and never reused`() {
        File(dir, "test-model.part").writeText("half")
        File(dir, "test-model.tmp").mkdirs()
        File(dir, "test-model.tmp/am").mkdirs()
        File(dir, "test-model.tmp/am/final.mdl").writeText("half")

        val model = model(specFor(good), { error("unused") })

        assertEquals(ModelState.NotInstalled, model.state.value)
        assertFalse(File(dir, "test-model.part").exists())
        assertFalse(File(dir, "test-model.tmp").exists())
    }

    @Test
    fun `the pinned Vosk model is https, sized and checksummed`() {
        val spec = SpeechModelSpec.VoskSmallEnglish

        assertTrue(spec.url.startsWith("https://"))
        assertEquals(64, spec.sha256.length)
        assertTrue(spec.zipBytes > 0)
    }
}
