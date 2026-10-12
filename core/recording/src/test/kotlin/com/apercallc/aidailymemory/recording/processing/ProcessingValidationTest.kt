package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessingValidationTest {

    // --- Transcript validation ---

    @Test
    fun validTranscriptPasses() {
        val result = TranscriptResult(
            language = "en",
            spans = listOf(
                TranscribedSpan(0, 1000, "hello"),
                TranscribedSpan(1000, 2000, "world"),
            ),
        )
        ProcessingValidation.validateTranscript(result, durationMs = 2000)
    }

    @Test
    fun spanEndWithinTolerancePasses() {
        val result = TranscriptResult(
            language = null,
            spans = listOf(TranscribedSpan(0, 2001, "clip ends at 2000 + 1ms tolerance")),
        )
        ProcessingValidation.validateTranscript(result, durationMs = 2000)
    }

    @Test
    fun negativeStartMsRejected() {
        val result = TranscriptResult(null, listOf(TranscribedSpan(-1, 100, "bad")))
        val ex = assertFailsWith<ProcessingException> {
            ProcessingValidation.validateTranscript(result, durationMs = 1000)
        }
        assertEquals("invalid_provider_result", ex.code)
        assertTrue(!ex.retryable)
    }

    @Test
    fun endBeforeStartRejected() {
        val result = TranscriptResult(null, listOf(TranscribedSpan(100, 50, "bad")))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateTranscript(result, durationMs = 1000)
        }
    }

    @Test
    fun endBeyondToleranceRejected() {
        val result = TranscriptResult(null, listOf(TranscribedSpan(0, 5000, "bad")))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateTranscript(result, durationMs = 2000)
        }
    }

    @Test
    fun outOfOrderSpansRejected() {
        val result = TranscriptResult(null, listOf(
            TranscribedSpan(1000, 2000, "second"),
            TranscribedSpan(0, 500, "first but appears second"),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateTranscript(result, durationMs = 3000)
        }
    }

    @Test
    fun confidenceOutOfRangeRejected() {
        val result = TranscriptResult(null, listOf(TranscribedSpan(0, 100, "bad", confidence = 1.5)))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateTranscript(result, durationMs = 1000)
        }
    }

    @Test
    fun confidenceAtBoundsPasses() {
        val result = TranscriptResult(null, listOf(
            TranscribedSpan(0, 100, "ok", confidence = 0.0),
            TranscribedSpan(100, 200, "ok", confidence = 1.0),
        ))
        ProcessingValidation.validateTranscript(result, durationMs = 200)
    }

    @Test
    fun emptySpansPasses() {
        val result = TranscriptResult(null, emptyList())
        ProcessingValidation.validateTranscript(result, durationMs = 1000)
    }

    // --- Extraction validation ---

    @Test
    fun validExtractionPasses() {
        val result = ExtractionResult(listOf(
            ExtractedItem(
                kind = "decision",
                text = "Ship auth before billing",
                confidence = "confirmed",
                evidenceSegmentIndices = listOf(0, 1),
                quote = "auth first",
            ),
            ExtractedItem(
                kind = "action",
                text = "Draft scope doc by Wednesday",
                confidence = "likely",
                evidenceSegmentIndices = listOf(2),
                owner = "Tommy",
            ),
        ))
        ProcessingValidation.validateExtraction(result, segmentCount = 5)
    }

    @Test
    fun validCommitmentPasses() {
        val result = ExtractionResult(listOf(
            ExtractedItem(
                kind = "commitment",
                text = "I will send the PR by Friday",
                confidence = "confirmed",
                evidenceSegmentIndices = listOf(0),
                direction = "i_owe",
                counterpart = "Marcus",
            ),
        ))
        ProcessingValidation.validateExtraction(result, segmentCount = 1)
    }

    @Test
    fun invalidKindRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("foo", "text", "confirmed", listOf(0)),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun invalidConfidenceRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("decision", "text", "definitely", listOf(0)),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun emptyTextRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("decision", "", "confirmed", listOf(0)),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun textOverLimitRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("decision", "x".repeat(281), "confirmed", listOf(0)),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun quoteOverLimitRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("decision", "text", "confirmed", listOf(0), quote = "x".repeat(281)),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun ownerOnNonActionRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("decision", "text", "confirmed", listOf(0), owner = "Tommy"),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun ownerOnActionPasses() {
        val result = ExtractionResult(listOf(
            ExtractedItem("action", "text", "confirmed", listOf(0), owner = "Tommy"),
        ))
        ProcessingValidation.validateExtraction(result, segmentCount = 1)
    }

    @Test
    fun ownerOverLimitRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("action", "text", "confirmed", listOf(0), owner = "x".repeat(81)),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun emptyEvidenceRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("decision", "text", "confirmed", emptyList()),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun evidenceOutOfBoundsRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("decision", "text", "confirmed", listOf(5)),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 3)
        }
    }

    @Test
    fun tooMuchEvidenceRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("decision", "text", "confirmed", listOf(0, 1, 2, 3, 4, 5)),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 10)
        }
    }

    @Test
    fun commitmentWithoutDirectionRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("commitment", "text", "confirmed", listOf(0), counterpart = "Bob"),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun commitmentWithoutCounterpartRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("commitment", "text", "confirmed", listOf(0), direction = "i_owe"),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun directionOnNonCommitmentRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("decision", "text", "confirmed", listOf(0), direction = "i_owe"),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun counterpartOnNonCommitmentRejected() {
        val result = ExtractionResult(listOf(
            ExtractedItem("idea", "text", "confirmed", listOf(0), counterpart = "Bob"),
        ))
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun tooManyItemsRejected() {
        val items = (1..51).map {
            ExtractedItem("decision", "text$it", "confirmed", listOf(0))
        }
        val result = ExtractionResult(items)
        assertFailsWith<ProcessingException> {
            ProcessingValidation.validateExtraction(result, segmentCount = 1)
        }
    }

    @Test
    fun maxItemsBoundaryPasses() {
        val items = (1..50).map {
            ExtractedItem("decision", "text$it", "confirmed", listOf(0))
        }
        val result = ExtractionResult(items)
        ProcessingValidation.validateExtraction(result, segmentCount = 1)
    }

    @Test
    fun emptyItemsPasses() {
        ProcessingValidation.validateExtraction(ExtractionResult(emptyList()), segmentCount = 5)
    }
}