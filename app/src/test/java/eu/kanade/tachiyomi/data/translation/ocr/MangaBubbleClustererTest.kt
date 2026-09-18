package eu.kanade.tachiyomi.data.translation.ocr

import android.graphics.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MangaBubbleClustererTest {

    private lateinit var clusterer: MangaBubbleClusterer

    @BeforeEach
    fun setUp() {
        clusterer = MangaBubbleClusterer()
        clusterer.rectFactory = { l, t, r, b -> createRect(l, t, r, b) }
    }

    private fun createOcrBlock(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        lines: List<OcrLine> = emptyList(),
    ): OcrBlock {
        val rect = createRect(left, top, right, bottom)
        val blockLines = lines.ifEmpty {
            listOf(OcrLine(text = text, boundingBox = rect))
        }
        return OcrBlock(
            text = text,
            boundingBox = rect,
            lines = blockLines,
        )
    }

    @Test
    fun `compact dialogue bubbles are classified as DIALOGUE`() {
        val block1 = createOcrBlock(
            text = "Hello there!",
            left = 100,
            top = 100,
            right = 180,
            bottom = 140,
        )
        val block2 = createOcrBlock(
            text = "How are you?",
            left = 105,
            top = 145,
            right = 175,
            bottom = 185,
        )

        val bubbles = clusterer.cluster(listOf(block1, block2), isRtl = false)

        assertEquals(1, bubbles.size)
        val bubble = bubbles[0]
        assertEquals(TextType.DIALOGUE, bubble.textType)
        assertTrue(bubble.originalText.contains("Hello there!"))
        assertTrue(bubble.originalText.contains("How are you?"))
    }

    @Test
    fun `boxed narration paragraph with multi-line shared left edge is grouped into single NARRATION unit`() {
        val line1 = OcrLine("Chapter 1: The Beginning.", createRect(50, 60, 320, 85))
        val line2 = OcrLine("Long ago in a forgotten kingdom,", createRect(52, 90, 340, 115))
        val line3 = OcrLine("peace reigned for three centuries.", createRect(50, 120, 335, 145))

        val narrationBlock = createOcrBlock(
            text = "Chapter 1: The Beginning.\nLong ago in a forgotten kingdom,\npeace reigned for three centuries.",
            left = 50,
            top = 60,
            right = 350,
            bottom = 150,
            lines = listOf(line1, line2, line3),
        )

        val bubbles = clusterer.cluster(listOf(narrationBlock), isRtl = false)

        assertEquals(1, bubbles.size)
        val bubble = bubbles[0]
        assertEquals(TextType.NARRATION, bubble.textType)
        assertEquals(
            "Chapter 1: The Beginning.\nLong ago in a forgotten kingdom,\npeace reigned for three centuries.",
            bubble.originalText,
        )
    }

    @Test
    fun `unboxed wide narration text directly over artwork is grouped into single NARRATION unit without bubble`() {
        // Individual raw OCR blocks returned per line by ML Kit over open artwork
        val lineBlock1 = createOcrBlock(
            text = "In the twilight of the fading era,",
            left = 80,
            top = 400,
            right = 380,
            bottom = 425,
        )
        val lineBlock2 = createOcrBlock(
            text = "a solitary wanderer crossed the frontier.",
            left = 82,
            top = 433,
            right = 390,
            bottom = 458,
        )
        val lineBlock3 = createOcrBlock(
            text = "The long journey had only begun.",
            left = 80,
            top = 466,
            right = 360,
            bottom = 491,
        )

        val bubbles = clusterer.cluster(listOf(lineBlock1, lineBlock2, lineBlock3), isRtl = false)

        assertEquals(1, bubbles.size)
        val bubble = bubbles[0]
        assertEquals(TextType.NARRATION, bubble.textType)
        val expectedLines = listOf(
            "In the twilight of the fading era,",
            "a solitary wanderer crossed the frontier.",
            "The long journey had only begun.",
        )
        assertEquals(expectedLines.joinToString("\n"), bubble.originalText)
    }

    @Test
    fun `mixed page with boxed narration, unboxed narration, and dialogue bubbles are kept as distinct units`() {
        // 1. Boxed narration at top left
        val boxedNarration = createOcrBlock(
            text = "Meanwhile, in the capital city...",
            left = 40,
            top = 40,
            right = 260,
            bottom = 80,
        )

        // 2. Dialogue bubble at top right
        val dialogue1 = createOcrBlock(
            text = "Wait for me!",
            left = 360,
            top = 100,
            right = 440,
            bottom = 150,
        )

        // 3. Unboxed narration over art at bottom
        val unboxedNarr1 = createOcrBlock(
            text = "Neither knowing what darkness awaited them,",
            left = 60,
            top = 500,
            right = 380,
            bottom = 525,
        )
        val unboxedNarr2 = createOcrBlock(
            text = "they continued into the shadows.",
            left = 62,
            top = 533,
            right = 350,
            bottom = 558,
        )

        val bubbles = clusterer.cluster(
            listOf(boxedNarration, dialogue1, unboxedNarr1, unboxedNarr2),
            isRtl = false,
        )

        assertEquals(3, bubbles.size)

        // Find each by text content
        val boxBubble = bubbles.first { it.originalText.contains("Meanwhile") }
        val dialogueBubble = bubbles.first { it.originalText.contains("Wait for me") }
        val unboxedBubble = bubbles.first { it.originalText.contains("Neither knowing") }

        assertEquals(TextType.NARRATION, boxBubble.textType)
        assertEquals(TextType.DIALOGUE, dialogueBubble.textType)
        assertEquals(TextType.NARRATION, unboxedBubble.textType)

        assertTrue(unboxedBubble.originalText.contains("Neither knowing what darkness awaited them,"))
        assertTrue(unboxedBubble.originalText.contains("they continued into the shadows."))
    }

    @Test
    fun `isNoiseText preserves legitimate manga short reactions`() {
        org.junit.jupiter.api.Assertions.assertFalse(clusterer.isNoiseText("!!"))
        org.junit.jupiter.api.Assertions.assertFalse(clusterer.isNoiseText("?!"))
        org.junit.jupiter.api.Assertions.assertFalse(clusterer.isNoiseText("..."))
        org.junit.jupiter.api.Assertions.assertFalse(clusterer.isNoiseText("?"))
        org.junit.jupiter.api.Assertions.assertFalse(clusterer.isNoiseText("!"))
        org.junit.jupiter.api.Assertions.assertFalse(clusterer.isNoiseText("!?"))
        org.junit.jupiter.api.Assertions.assertFalse(clusterer.isNoiseText("…"))
    }

    @Test
    fun `isNoiseText filters stray non-reaction punctuation and single line-art letters`() {
        assertTrue(clusterer.isNoiseText(")"))
        assertTrue(clusterer.isNoiseText("("))
        assertTrue(clusterer.isNoiseText("【"))
        assertTrue(clusterer.isNoiseText("--"))
        assertTrue(clusterer.isNoiseText("R"))
        assertTrue(clusterer.isNoiseText("l"))
    }

    @Test
    fun `isNarrationCandidate respects loosened aspect ratio and leftDiff thresholds`() {
        // Aspect ratio: width 190, height 100 -> 1.9 (>= 1.8)
        val wideBlock = createOcrBlock("This is a summary of events in chapter two.", 10, 10, 200, 110)
        assertTrue(clusterer.isNarrationCandidate(wideBlock))

        // Multi-line block with leftDiff = 45 (<= 50)
        val line1 = OcrLine("Line one of the story begins here.", createRect(20, 20, 250, 45))
        val line2 = OcrLine("Line two continues the narrative text.", createRect(65, 50, 260, 75))
        val multiLineBlock = createOcrBlock(
            text = "Line one of the story begins here.\nLine two continues the narrative text.",
            left = 20,
            top = 20,
            right = 260,
            bottom = 75,
            lines = listOf(line1, line2),
        )
        assertTrue(clusterer.isNarrationCandidate(multiLineBlock))
    }

    companion object {
        private val unsafe: sun.misc.Unsafe by lazy {
            val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as sun.misc.Unsafe
        }

        fun createRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
            val rect = unsafe.allocateInstance(Rect::class.java) as Rect
            rect.left = left
            rect.top = top
            rect.right = right
            rect.bottom = bottom
            return rect
        }
    }
}
