package com.apercallc.aidailymemory.recording.design

import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrandTokensTest {
    private fun channel(value: Long, shift: Int): Double {
        val c = ((value shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Long) =
        0.2126 * channel(color, 16) + 0.7152 * channel(color, 8) + 0.0722 * channel(color, 0)

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertReadable(label: String, text: Long, on: Long, minimum: Double = 4.5) {
        val ratio = contrast(text, on)
        assertTrue(ratio >= minimum, "$label: contrast %.2f is below %.1f".format(ratio, minimum))
    }

    @Test
    fun `light text pairs are readable`() = with(BrandTokens.Light) {
        assertReadable("ink on paper", INK, PAPER)
        assertReadable("ink on card", INK, CARD)
        assertReadable("soft ink on paper", INK_SOFT, PAPER)
        assertReadable("faint ink on paper", INK_FAINT, PAPER)
        assertReadable("faint ink on card", INK_FAINT, CARD)
        assertReadable("red link on paper", RED, PAPER)
        assertReadable("white on red button", WHITE, RED)
        assertReadable("slate on paper", SLATE, PAPER)
        assertReadable("ok on paper", OK, PAPER)
        assertReadable("warn on paper", WARN, PAPER)
        assertReadable("on red container", ON_RED_CONTAINER, RED_CONTAINER)
        assertReadable("on slate container", ON_SLATE_CONTAINER, SLATE_CONTAINER)
        assertReadable("on warn container", ON_WARN_CONTAINER, WARN_CONTAINER)
        assertReadable("on error container", ON_ERROR_CONTAINER, ERROR_CONTAINER)
        assertReadable("white on error", WHITE, ERROR)
        assertReadable("ink on layered surfaces", INK, SURFACE_HIGHEST)
        assertReadable("outline against paper (non-text)", OUTLINE, PAPER, minimum = 3.0)
    }

    @Test
    fun `dark text pairs are readable`() = with(BrandTokens.Dark) {
        assertReadable("ink on paper", INK, PAPER)
        assertReadable("ink on card", INK, CARD)
        assertReadable("soft ink on paper", INK_SOFT, PAPER)
        assertReadable("faint ink on paper", INK_FAINT, PAPER)
        assertReadable("red link on paper", RED, PAPER)
        assertReadable("on red button", ON_RED, RED)
        assertReadable("slate on paper", SLATE, PAPER)
        assertReadable("ok on paper", OK, PAPER)
        assertReadable("warn on paper", WARN, PAPER)
        assertReadable("on red container", ON_RED_CONTAINER, RED_CONTAINER)
        assertReadable("on slate", ON_SLATE, SLATE)
        assertReadable("on slate container", ON_SLATE_CONTAINER, SLATE_CONTAINER)
        assertReadable("on warn", ON_WARN, WARN)
        assertReadable("on warn container", ON_WARN_CONTAINER, WARN_CONTAINER)
        assertReadable("on error", ON_ERROR, ERROR)
        assertReadable("on error container", ON_ERROR_CONTAINER, ERROR_CONTAINER)
        assertReadable("ink on layered surfaces", INK, SURFACE_HIGHEST)
        assertReadable("ink on the watch background", INK, WATCH_BACKGROUND)
        assertReadable("outline against paper (non-text)", OUTLINE, PAPER, minimum = 3.0)
    }

    @Test
    fun `the recording dot stays visible on every background`() {
        for (background in listOf(BrandTokens.Light.PAPER, BrandTokens.Light.CARD, BrandTokens.Dark.PAPER, BrandTokens.Dark.WATCH_BACKGROUND)) {
            assertReadable("recording dot", BrandTokens.RECORDING_DOT, background, minimum = 3.0)
        }
    }

    // --- the web app's stylesheet is the other half of the same palette ---------------------------

    private fun css(): String {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "website/app/app.css") }
            .firstOrNull { it.isFile }
        assertTrue(file != null, "website/app/app.css not found above ${File("").absolutePath}")
        return file.readText()
    }

    private fun variables(block: String): Map<String, Long> =
        Regex("--([a-z-]+):\\s*#([0-9a-fA-F]{6})").findAll(block).associate { match ->
            match.groupValues[1] to (0xFF000000L or match.groupValues[2].toLong(16))
        }

    @Test
    fun `the web stylesheet and the shared tokens use the same colors`() {
        val sheet = css()
        val light = variables(sheet.substringAfter(":root {").substringBefore("}"))
        val dark = variables(sheet.substringAfter("prefers-color-scheme: dark").substringAfter(":root {").substringBefore("}"))

        assertEquals(
            mapOf(
                "paper" to BrandTokens.Light.PAPER, "paper-deep" to BrandTokens.Light.PAPER_DEEP,
                "card" to BrandTokens.Light.CARD, "ink" to BrandTokens.Light.INK,
                "ink-soft" to BrandTokens.Light.INK_SOFT, "ink-faint" to BrandTokens.Light.INK_FAINT,
                "rule" to BrandTokens.Light.RULE, "red" to BrandTokens.Light.RED,
                "red-deep" to BrandTokens.Light.RED_DEEP, "slate" to BrandTokens.Light.SLATE,
                "ok" to BrandTokens.Light.OK, "warn" to BrandTokens.Light.WARN,
                "focus" to BrandTokens.Light.FOCUS,
            ),
            light,
        )
        assertEquals(
            mapOf(
                "paper" to BrandTokens.Dark.PAPER, "paper-deep" to BrandTokens.Dark.PAPER_DEEP,
                "card" to BrandTokens.Dark.CARD, "ink" to BrandTokens.Dark.INK,
                "ink-soft" to BrandTokens.Dark.INK_SOFT, "ink-faint" to BrandTokens.Dark.INK_FAINT,
                "rule" to BrandTokens.Dark.RULE, "red" to BrandTokens.Dark.RED,
                "red-deep" to BrandTokens.Dark.RED_DEEP, "slate" to BrandTokens.Dark.SLATE,
                "ok" to BrandTokens.Dark.OK, "warn" to BrandTokens.Dark.WARN,
                "focus" to BrandTokens.Dark.FOCUS,
            ),
            dark,
        )
    }
}
