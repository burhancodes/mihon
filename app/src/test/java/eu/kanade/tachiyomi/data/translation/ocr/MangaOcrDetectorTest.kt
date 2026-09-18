package eu.kanade.tachiyomi.data.translation.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MangaOcrDetectorTest {

    private lateinit var detector: MangaOcrDetector

    @BeforeEach
    fun setUp() {
        detector = MangaOcrDetector()
    }

    private fun createRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
        val rect = unsafe.allocateInstance(Rect::class.java) as Rect
        rect.left = left
        rect.top = top
        rect.right = right
        rect.bottom = bottom
        return rect
    }

    private fun createOcrBlock(text: String): OcrBlock {
        return OcrBlock(
            text = text,
            boundingBox = createRect(0, 0, 100, 50),
            lines = listOf(OcrLine(text, createRect(0, 0, 100, 50))),
        )
    }

    @Test
    fun `probeBestLanguage skips near-empty first tile and scores text-rich second tile`() {
        // Tile 1: near-empty splash/logo panel (< 8 characters)
        val tile1Blocks = listOf(createOcrBlock("©"))

        // Tile 2: text-rich Korean manhwa panel
        val tile2KoreanBlocks = listOf(
            createOcrBlock("이것은 한국어 웹툰 텍스트입니다"),
            createOcrBlock("주인공이 새로운 던전에 들어섰습니다"),
        )
        val tile2LatinBlocks = listOf(
            createOcrBlock("random noise letters"),
        )
        val tile2JapaneseBlocks = emptyList<OcrBlock>()

        val dummyBitmap = unsafe.allocateInstance(Bitmap::class.java) as Bitmap
        detector.tileCropper = { _, _, _, _, _ -> dummyBitmap }

        var callCount = 0
        detector.ocrRunner = { _, lang ->
            val tileIndex = callCount / 3
            callCount++

            if (tileIndex == 0) {
                // Tile 1: returns near-empty text for all languages
                tile1Blocks
            } else {
                // Tile 2: returns Korean text for "ko", Latin for "latin", empty for "ja"
                when (lang) {
                    "ko" -> tile2KoreanBlocks
                    "latin" -> tile2LatinBlocks
                    else -> tile2JapaneseBlocks
                }
            }
        }

        // Simulating a tall webtoon strip: height 6000, tileHeight 2000, step 1800
        val detected = detector.probeBestLanguage(
            bitmap = dummyBitmap,
            width = 720,
            height = 6000,
            tileHeight = 2000,
            step = 1800,
            sourceLang = "en",
        )

        assertEquals("ko", detected)
    }

    @Test
    fun `probeBestLanguage trusts explicit CJK source language directly`() {
        val dummyBitmap = unsafe.allocateInstance(Bitmap::class.java) as Bitmap
        assertEquals("ja", detector.probeBestLanguage(dummyBitmap, 720, 3000, 2000, 1800, "ja"))
        assertEquals("ko", detector.probeBestLanguage(dummyBitmap, 720, 3000, 2000, 1800, "ko"))
        assertEquals("zh", detector.probeBestLanguage(dummyBitmap, 720, 3000, 2000, 1800, "zh"))
    }

    companion object {
        private val unsafe: sun.misc.Unsafe by lazy {
            val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as sun.misc.Unsafe
        }
    }
}
