package eu.kanade.tachiyomi.data.translation.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class MangaOcrDetector {

    private val recognizers = ConcurrentHashMap<String, TextRecognizer>()

    internal var tileCropper: (Bitmap, Int, Int, Int, Int) -> Bitmap = { bmp, x, y, w, h ->
        Bitmap.createBitmap(bmp, x, y, w, h)
    }

    internal var ocrRunner: (Bitmap, String) -> List<OcrBlock> = { bmp, lang ->
        runOcr(bmp, lang)
    }

    fun getRecognizer(lang: String): TextRecognizer {
        val key = normalizeLangKey(lang)
        return recognizers.computeIfAbsent(key) {
            when (key) {
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    private fun normalizeLangKey(lang: String): String {
        val normalized = lang.lowercase().trim()
        return when {
            normalized.startsWith("ja") || normalized == "jpn" -> "ja"
            normalized.startsWith("ko") || normalized == "kor" -> "ko"
            normalized.startsWith("zh") || normalized == "chs" || normalized == "cht" -> "zh"
            else -> "latin"
        }
    }

    suspend fun detectText(
        bitmap: Bitmap,
        sourceLang: String = "ja",
    ): List<OcrBlock> = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height

        // If bitmap is within standard aspect ratios and reasonable height, process directly
        if (height <= 2200 && width <= 2200) {
            return@withContext detectTextSingle(bitmap, sourceLang)
        }

        return@withContext detectTextTiled(bitmap, width, height, sourceLang)
    }

    private fun detectTextTiled(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        sourceLang: String,
    ): List<OcrBlock> {
        // For long strip webtoons/manhwa (e.g. 720x26694), ML Kit downsamples the entire strip
        // into a low-res image, destroying readable text. Tiling at 2000px height with 200px overlap
        // preserves original 1:1 crisp resolution.
        val tileHeight = 2000
        val overlap = 200
        val step = tileHeight - overlap

        val bestLang = probeBestLanguage(bitmap, width, height, tileHeight, step, sourceLang)
        logcat(LogPriority.INFO) {
            "Selected OCR language '$bestLang' for image ${width}x$height (hint: '$sourceLang')"
        }

        val allBlocks = mutableListOf<OcrBlock>()
        var y = 0
        while (y < height) {
            val h = minOf(tileHeight, height - y)
            val tileBitmap = tileCropper(bitmap, 0, y, width, h)
            try {
                val tileBlocks = ocrRunner(tileBitmap, bestLang)
                for (block in tileBlocks) {
                    val adjustedBox = Rect(
                        block.boundingBox.left,
                        block.boundingBox.top + y,
                        block.boundingBox.right,
                        block.boundingBox.bottom + y,
                    )
                    val adjustedLines = block.lines.map { line ->
                        val lineBox = Rect(
                            line.boundingBox.left,
                            line.boundingBox.top + y,
                            line.boundingBox.right,
                            line.boundingBox.bottom + y,
                        )
                        OcrLine(text = line.text, boundingBox = lineBox)
                    }
                    allBlocks.add(OcrBlock(text = block.text, boundingBox = adjustedBox, lines = adjustedLines))
                }
            } finally {
                tileBitmap.recycle()
            }

            if (y + h >= height) break
            y += step
        }

        val deduplicated = deduplicateBlocks(allBlocks)
        logcat(LogPriority.INFO) {
            "Tiled OCR detected ${allBlocks.size} raw blocks, ${deduplicated.size} after deduplication"
        }
        return deduplicated
    }

    private fun detectTextSingle(
        bitmap: Bitmap,
        sourceLang: String,
    ): List<OcrBlock> {
        val primaryLangKey = normalizeLangKey(sourceLang)

        // When sourceLang is latin/en, raw manga/manhwa on English aggregator sites often contains Korean or Japanese text.
        val candidates = if (primaryLangKey == "latin") {
            listOf("ko", "ja", "latin", "zh")
        } else {
            listOf(primaryLangKey) + listOf("ko", "ja", "latin", "zh").filterNot { it == primaryLangKey }
        }

        var bestBlocks = emptyList<OcrBlock>()
        var bestScore = -1

        for (lang in candidates) {
            val blocks = runOcr(bitmap, lang)
            val score = scoreBlocks(blocks)
            if (score > bestScore) {
                bestScore = score
                bestBlocks = blocks
                if (score >= 20 && lang == primaryLangKey) {
                    break
                }
            }
        }

        return bestBlocks
    }

    internal fun probeBestLanguage(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        tileHeight: Int,
        step: Int,
        sourceLang: String,
    ): String {
        val primaryLangKey = normalizeLangKey(sourceLang)

        // If sourceLang is explicitly a CJK language, trust it
        if (primaryLangKey in listOf("ja", "ko", "zh")) {
            return primaryLangKey
        }

        // When sourceLang is "latin" or "en", raw manhwa/manga is commonly uploaded to English sources.
        // Probe early tiles in sequence, skipping near-empty/text-sparse splash tiles,
        // and aggregate scores across the first 2-3 meaningful text tiles.
        var y = 0
        var attempts = 0
        val maxAttempts = 8
        val targetMeaningfulTiles = 3
        var meaningfulTilesSampled = 0

        var totalKoScore = 0
        var totalJaScore = 0
        var totalLatinScore = 0

        while (y < height && attempts < maxAttempts && meaningfulTilesSampled < targetMeaningfulTiles) {
            val h = minOf(tileHeight, height - y)
            val tileBitmap = tileCropper(bitmap, 0, y, width, h)
            try {
                val koBlocks = ocrRunner(tileBitmap, "ko")
                val jaBlocks = ocrRunner(tileBitmap, "ja")
                val latinBlocks = ocrRunner(tileBitmap, "latin")

                val maxChars = maxOf(
                    koBlocks.sumOf { it.text.trim().length },
                    jaBlocks.sumOf { it.text.trim().length },
                    latinBlocks.sumOf { it.text.trim().length },
                )

                // Skip tiles with near-zero text (e.g. splash panel, logo, empty space)
                if (maxChars >= MIN_TILE_CHAR_COUNT) {
                    totalKoScore += scoreBlocks(koBlocks)
                    totalJaScore += scoreBlocks(jaBlocks)
                    totalLatinScore += scoreBlocks(latinBlocks)
                    meaningfulTilesSampled++
                }
            } finally {
                try {
                    tileBitmap.recycle()
                } catch (_: Throwable) {}
            }
            y += step
            attempts++
        }

        if (meaningfulTilesSampled > 0) {
            return when {
                totalKoScore >= totalJaScore && totalKoScore >= totalLatinScore -> "ko"
                totalJaScore >= totalLatinScore -> "ja"
                else -> "latin"
            }
        }

        return if (primaryLangKey.isNotEmpty()) primaryLangKey else "ko"
    }

    private fun scoreBlocks(blocks: List<OcrBlock>): Int {
        var score = 0
        for (block in blocks) {
            val text = block.text.trim()
            val cjkCount = text.count { char ->
                val code = char.code
                (code in 0xAC00..0xD7AF) || // Hangul Syllables
                    (code in 0x1100..0x11FF) || // Hangul Jamo
                    (code in 0x3040..0x309F) || // Hiragana
                    (code in 0x30A0..0x30FF) || // Katakana
                    (code in 0x4E00..0x9FFF) // CJK Unified Ideographs
            }
            score += cjkCount * 5

            val words = text.split(Regex("[\\s\\p{Punct}]+")).filter { it.length >= 2 }
            score += words.size * 2
        }
        return score
    }

    private fun deduplicateBlocks(blocks: List<OcrBlock>): List<OcrBlock> {
        val result = mutableListOf<OcrBlock>()
        for (block in blocks) {
            val duplicateIndex = result.indexOfFirst { existing ->
                val intersection = Rect()
                if (intersection.setIntersect(existing.boundingBox, block.boundingBox)) {
                    val intersectionArea = intersection.width().toLong() * intersection.height()
                    val minArea = minOf(
                        existing.boundingBox.width().toLong() * existing.boundingBox.height(),
                        block.boundingBox.width().toLong() * block.boundingBox.height(),
                    )
                    minArea > 0 && (intersectionArea.toDouble() / minArea > 0.4)
                } else {
                    false
                }
            }
            if (duplicateIndex >= 0) {
                val existing = result[duplicateIndex]
                if (block.text.length > existing.text.length) {
                    result[duplicateIndex] = block
                }
            } else {
                result.add(block)
            }
        }
        return result
    }

    private fun runOcr(bitmap: Bitmap, langKey: String): List<OcrBlock> {
        return try {
            val recognizer = getRecognizer(langKey)
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image), 15, TimeUnit.SECONDS)
            val blocks = mutableListOf<OcrBlock>()
            for (block in result.textBlocks) {
                val box = block.boundingBox ?: continue
                val text = block.text.trim()
                if (text.isEmpty()) continue
                val lines = block.lines.mapNotNull { line ->
                    val lineBox = line.boundingBox ?: return@mapNotNull null
                    val lineText = line.text.trim()
                    if (lineText.isEmpty()) return@mapNotNull null
                    OcrLine(text = lineText, boundingBox = lineBox)
                }.ifEmpty {
                    listOf(OcrLine(text = text, boundingBox = box))
                }
                blocks.add(OcrBlock(text = text, boundingBox = box, lines = lines))
            }
            blocks
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "ML Kit OCR failed for language $langKey" }
            emptyList()
        }
    }

    companion object {
        internal const val MIN_TILE_CHAR_COUNT = 8
    }
}

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
    val lines: List<OcrLine>,
)

data class OcrLine(
    val text: String,
    val boundingBox: Rect,
)
