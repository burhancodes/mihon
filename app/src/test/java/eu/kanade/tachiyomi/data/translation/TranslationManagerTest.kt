package eu.kanade.tachiyomi.data.translation

import android.graphics.Bitmap
import android.graphics.Rect
import eu.kanade.tachiyomi.data.translation.grammar.LanguageToolService
import eu.kanade.tachiyomi.data.translation.ocr.MangaBubbleClusterer
import eu.kanade.tachiyomi.data.translation.ocr.MangaOcrDetector
import eu.kanade.tachiyomi.data.translation.ocr.OcrBlock
import eu.kanade.tachiyomi.data.translation.ocr.SpeechBubble
import eu.kanade.tachiyomi.data.translation.ocr.TextType
import eu.kanade.tachiyomi.data.translation.render.MangaPageRenderer
import eu.kanade.tachiyomi.data.translation.rest.RestTranslationService
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TranslationManagerTest {

    private val ocrDetector = mockk<MangaOcrDetector>(relaxed = true)
    private val bubbleClusterer = mockk<MangaBubbleClusterer>(relaxed = true)
    private val restService = mockk<RestTranslationService>(relaxed = true)
    private val languageToolService = mockk<LanguageToolService>(relaxed = true)
    private val renderer = mockk<MangaPageRenderer>(relaxed = true)
    private val cache = mockk<TranslationCache>(relaxed = true)
    private val preferences = mockk<ReaderPreferences>(relaxed = true)

    private lateinit var translationManager: TranslationManager

    @BeforeEach
    fun setUp() {
        translationManager = TranslationManager(
            mangaOcrDetector = ocrDetector,
            mangaBubbleClusterer = bubbleClusterer,
            restTranslationService = restService,
            languageToolService = languageToolService,
            mangaPageRenderer = renderer,
            translationCache = cache,
            preferences = preferences,
        )
    }

    private fun createRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
        val rect = unsafe.allocateInstance(Rect::class.java) as Rect
        rect.left = left
        rect.top = top
        rect.right = right
        rect.bottom = bottom
        return rect
    }

    private fun createBubble(id: Int, text: String, textType: TextType = TextType.DIALOGUE): SpeechBubble {
        return SpeechBubble(
            id = id,
            boundingBox = createRect(0, 0, 100, 50),
            originalText = text,
            blocks = emptyList(),
            isVertical = false,
            textType = textType,
        )
    }

    @Test
    fun `runDetectionStage detects and caches bubbles`() = runBlocking {
        val dummyBitmap = unsafe.allocateInstance(Bitmap::class.java) as Bitmap
        val dummyBlock = mockk<OcrBlock>(relaxed = true)
        val bubble1 = createBubble(1, "Hello", TextType.DIALOGUE)
        val bubble2 = createBubble(2, "Chapter 1", TextType.NARRATION)

        coEvery { ocrDetector.detectText(any(), any()) } returns listOf(dummyBlock)
        every { bubbleClusterer.cluster(any(), any()) } returns listOf(bubble1, bubble2)

        val bubbles = translationManager.runDetectionStage(
            bitmap = dummyBitmap,
            sourceLang = "en",
            pageIndex = 0,
            detectionKey = "det_1_test_0",
            force = false,
        )

        assertEquals(2, bubbles.size)
        assertEquals(TextType.DIALOGUE, bubbles[0].textType)
        assertEquals(TextType.NARRATION, bubbles[1].textType)

        // Second call with force=false should return cached bubbles without calling OCR again
        coEvery { ocrDetector.detectText(any(), any()) } throws AssertionError("OCR should not be called again")
        val cachedBubbles = translationManager.runDetectionStage(
            bitmap = dummyBitmap,
            sourceLang = "en",
            pageIndex = 0,
            detectionKey = "det_1_test_0",
            force = false,
        )
        assertEquals(2, cachedBubbles.size)
    }

    @Test
    fun `runTranslationStage isolates per-bubble failures and falls back to original text`() = runBlocking {
        val bubble1 = createBubble(1, "Success text")
        val bubble2 = createBubble(2, "Failing text")

        coEvery {
            restService.translateBatch(listOf("Success text"), "ENG", any())
        } returns RestTranslationService.TranslationResult.Success(listOf("Translated text"))

        coEvery {
            restService.translateBatch(listOf("Failing text"), "ENG", any())
        } returns RestTranslationService.TranslationResult.Error("API 500 error")

        coEvery { languageToolService.correctGrammar(any(), any()) } answers { firstArg() }

        val stageResult = translationManager.runTranslationStage(
            bubbles = listOf(bubble1, bubble2),
            targetLang = "ENG",
            provider = "google",
            pageIndex = 0,
        )

        assertEquals(1, stageResult.succeeded)
        assertEquals(1, stageResult.failed)

        // Bubble 1 succeeded
        assertFalse(bubble1.translationFailed)
        assertEquals("Translated text", bubble1.translatedText)

        // Bubble 2 failed, falls back to originalText
        assertTrue(bubble2.translationFailed)
        assertEquals("Failing text", bubble2.translatedText)
    }

    @Test
    fun `runRenderStage passes through rawBytes if all translations failed or matched original`() {
        val dummyBitmap = unsafe.allocateInstance(Bitmap::class.java) as Bitmap
        val rawBytes = byteArrayOf(1, 2, 3)
        val bubble = createBubble(1, "Original text").apply {
            translationFailed = true
            translatedText = "Original text"
        }

        val output = translationManager.runRenderStage(
            bitmap = dummyBitmap,
            bubbles = listOf(bubble),
            rawBytes = rawBytes,
            pageIndex = 0,
            chapterId = 1L,
            chapterUrl = "chapter-1",
            targetLang = "ENG",
        )

        assertEquals(rawBytes, output)
    }

    companion object {
        private val unsafe: sun.misc.Unsafe by lazy {
            val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as sun.misc.Unsafe
        }
    }
}
