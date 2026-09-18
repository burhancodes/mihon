package eu.kanade.tachiyomi.data.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.translation.grammar.LanguageToolService
import eu.kanade.tachiyomi.data.translation.ocr.MangaBubbleClusterer
import eu.kanade.tachiyomi.data.translation.ocr.MangaOcrDetector
import eu.kanade.tachiyomi.data.translation.ocr.SpeechBubble
import eu.kanade.tachiyomi.data.translation.ocr.TextType
import eu.kanade.tachiyomi.data.translation.render.MangaPageRenderer
import eu.kanade.tachiyomi.data.translation.rest.RestTranslationService
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

@Inject
@SingleIn(AppScope::class)
class TranslationManager(
    val mangaOcrDetector: MangaOcrDetector,
    val mangaBubbleClusterer: MangaBubbleClusterer,
    val restTranslationService: RestTranslationService,
    val languageToolService: LanguageToolService,
    val mangaPageRenderer: MangaPageRenderer,
    val translationCache: TranslationCache,
    val preferences: ReaderPreferences,
) {

    /** Throttles concurrent processing to max 2 in-flight requests. */
    private val semaphore = Semaphore(permits = 2)

    private val activeRequests = ConcurrentHashMap<String, Deferred<TranslationResult>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isPageTranslated(chapterId: Long?, chapterUrl: String, pageIndex: Int, targetLang: String): Boolean {
        return translationCache.isPageInCache(chapterId, chapterUrl, pageIndex, targetLang)
    }

    fun getCachedStream(chapterId: Long?, chapterUrl: String, pageIndex: Int, targetLang: String): InputStream? {
        return if (translationCache.isPageInCache(chapterId, chapterUrl, pageIndex, targetLang)) {
            translationCache.getImageStream(chapterId, chapterUrl, pageIndex, targetLang)
        } else {
            null
        }
    }

    private val detectionCache = ConcurrentHashMap<String, List<SpeechBubble>>()

    private fun getDetectionCacheKey(chapterId: Long?, chapterUrl: String, pageIndex: Int): String {
        val id = chapterId ?: -1L
        return "det_${id}_${chapterUrl}_$pageIndex"
    }

    fun clearDetectionCache() {
        detectionCache.clear()
    }

    /**
     * Stage 1: OcrDetectionStage
     * Runs MangaOcrDetector + MangaBubbleClusterer, outputs a persisted list of SpeechBubble objects.
     * Logs the count and types of detected bubbles immediately after completion, before translation.
     */
    internal suspend fun runDetectionStage(
        bitmap: Bitmap,
        sourceLang: String,
        pageIndex: Int,
        detectionKey: String,
        force: Boolean = false,
    ): List<SpeechBubble> {
        if (!force) {
            detectionCache[detectionKey]?.let { cached ->
                val dialogueCount = cached.count { it.textType == TextType.DIALOGUE }
                val narrationCount = cached.count { it.textType == TextType.NARRATION }
                logcat(LogPriority.INFO) {
                    "Detection (cached): ${cached.size} bubbles found ($dialogueCount DIALOGUE, $narrationCount NARRATION)"
                }
                return cached.map { it.copy() }
            }
        }

        val ocrBlocks = mangaOcrDetector.detectText(bitmap, sourceLang = sourceLang)
        if (ocrBlocks.isEmpty()) {
            logcat(LogPriority.INFO) { "Detection: 0 bubbles found" }
            return emptyList()
        }

        val clustered = mangaBubbleClusterer.cluster(ocrBlocks, isRtl = true)
        val validBubbles = clustered.filter { it.originalText.isNotBlank() }

        val dialogueCount = validBubbles.count { it.textType == TextType.DIALOGUE }
        val narrationCount = validBubbles.count { it.textType == TextType.NARRATION }
        logcat(LogPriority.INFO) {
            "Detection: ${validBubbles.size} bubbles found ($dialogueCount DIALOGUE, $narrationCount NARRATION)"
        }

        detectionCache[detectionKey] = validBubbles.map { it.copy() }
        return validBubbles
    }

    /**
     * Stage 2: TranslationStage
     * Consumes detected bubbles, calls RestTranslationService + LanguageToolService per bubble independently.
     * Failed bubbles are marked with translationFailed = true and fall back to original untranslated text.
     */
    internal suspend fun runTranslationStage(
        bubbles: List<SpeechBubble>,
        targetLang: String,
        provider: String,
        pageIndex: Int,
    ): TranslationStageResult {
        var succeeded = 0
        var failed = 0

        for (bubble in bubbles) {
            val text = bubble.originalText.trim()
            if (text.isEmpty()) {
                bubble.translatedText = bubble.originalText
                continue
            }

            try {
                val restResult = restTranslationService.translateBatch(
                    texts = listOf(text),
                    targetLangKey = targetLang,
                    provider = provider,
                )

                when (restResult) {
                    is RestTranslationService.TranslationResult.Success -> {
                        val rawTranslated = restResult.translations.firstOrNull()?.trim()
                        if (!rawTranslated.isNullOrEmpty()) {
                            // Run optional LanguageTool grammar check
                            val corrected = try {
                                val ltResult = languageToolService.correctGrammar(
                                    texts = listOf(rawTranslated),
                                    targetLangKey = targetLang,
                                )
                                ltResult.firstOrNull() ?: rawTranslated
                            } catch (e: Throwable) {
                                logcat(LogPriority.WARN, e) { "Grammar check failed for bubble #${bubble.id}" }
                                rawTranslated
                            }

                            bubble.translatedText = corrected
                            bubble.translationFailed = false
                            succeeded++
                        } else {
                            bubble.translationFailed = true
                            bubble.translatedText = bubble.originalText
                            failed++
                        }
                    }
                    is RestTranslationService.TranslationResult.Error -> {
                        logcat(LogPriority.WARN) {
                            "Translation failed for bubble #${bubble.id}: ${restResult.message}"
                        }
                        bubble.translationFailed = true
                        bubble.translatedText = bubble.originalText
                        failed++
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Unexpected error translating bubble #${bubble.id}" }
                bubble.translationFailed = true
                bubble.translatedText = bubble.originalText
                failed++
            }
        }

        logcat(LogPriority.INFO) { "Translation: $succeeded succeeded, $failed failed" }
        return TranslationStageResult(succeeded = succeeded, failed = failed)
    }

    /**
     * Stage 3: RenderStage
     * Consumes whatever bubbles exist (mix of translated + failed/original), running adaptive inpaint + render
     * per bubble regardless of whether all bubbles succeeded.
     */
    internal fun runRenderStage(
        bitmap: Bitmap,
        bubbles: List<SpeechBubble>,
        rawBytes: ByteArray,
        pageIndex: Int,
        chapterId: Long?,
        chapterUrl: String,
        targetLang: String,
    ): ByteArray {
        val translatedCount = bubbles.count { bubble ->
            !bubble.translationFailed &&
                !bubble.translatedText.isNullOrBlank() &&
                !bubble.translatedText.equals(bubble.originalText.trim(), ignoreCase = true)
        }
        val originalCount = bubbles.size - translatedCount

        val renderedBytes = if (translatedCount > 0) {
            try {
                mangaPageRenderer.renderToBytes(
                    bitmap,
                    bubbles,
                    compressFormat = Bitmap.CompressFormat.PNG,
                )
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) {
                    "Failed to render translated page $pageIndex, falling back to raw bytes"
                }
                rawBytes
            }
        } else {
            rawBytes
        }

        logcat(LogPriority.INFO) {
            "Render: ${bubbles.size} bubbles rendered ($translatedCount translated, $originalCount original)"
        }

        translationCache.putImageToCache(
            chapterId = chapterId,
            chapterUrl = chapterUrl,
            pageIndex = pageIndex,
            targetLang = targetLang,
            imageBytes = renderedBytes,
        )

        return renderedBytes
    }

    suspend fun translatePage(
        chapterId: Long?,
        chapterUrl: String,
        pageIndex: Int,
        targetLang: String,
        rawImageBytesProvider: () -> ByteArray,
        force: Boolean = false,
        sourceLang: String = "ja",
    ): TranslationResult {
        if (!force && translationCache.isPageInCache(chapterId, chapterUrl, pageIndex, targetLang)) {
            val cachedFile = translationCache.getImageFile(chapterId, chapterUrl, pageIndex, targetLang)
            return TranslationResult.Success(cachedFile.readBytes())
        }

        val cacheKey = translationCache.getCacheKey(chapterId, chapterUrl, pageIndex, targetLang)
        val detectionKey = getDetectionCacheKey(chapterId, chapterUrl, pageIndex)

        if (force) {
            activeRequests.remove(cacheKey)?.cancel()
            detectionCache.remove(detectionKey)
        }

        val deferred = activeRequests.computeIfAbsent(cacheKey) {
            scope.async {
                semaphore.withPermit {
                    try {
                        val rawBytes = rawImageBytesProvider()
                        if (rawBytes.isEmpty()) {
                            return@withPermit TranslationResult.Error("Source image bytes are empty")
                        }

                        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                            ?: return@withPermit TranslationResult.Error("Failed to decode page bitmap")

                        try {
                            // 1. Detection Stage
                            val bubbles = runDetectionStage(
                                bitmap = bitmap,
                                sourceLang = sourceLang,
                                pageIndex = pageIndex,
                                detectionKey = detectionKey,
                                force = force,
                            )

                            if (bubbles.isEmpty()) {
                                return@withPermit TranslationResult.Skipped("No text detected")
                            }

                            // 2. Translation Stage
                            val provider = preferences.translationProvider.get()
                            runTranslationStage(
                                bubbles = bubbles,
                                targetLang = targetLang,
                                provider = provider,
                                pageIndex = pageIndex,
                            )

                            // 3. Render Stage
                            val renderedBytes = runRenderStage(
                                bitmap = bitmap,
                                bubbles = bubbles,
                                rawBytes = rawBytes,
                                pageIndex = pageIndex,
                                chapterId = chapterId,
                                chapterUrl = chapterUrl,
                                targetLang = targetLang,
                            )

                            TranslationResult.Success(renderedBytes, bubbles)
                        } finally {
                            bitmap.recycle()
                        }
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) { "Unexpected error during page translation" }
                        TranslationResult.Error(e.message ?: "Unknown translation error")
                    } finally {
                        activeRequests.remove(cacheKey)
                    }
                }
            }
        }
        return deferred.await()
    }

    fun preloadTranslation(
        chapterId: Long?,
        chapterUrl: String,
        pageIndex: Int,
        targetLang: String,
        rawImageBytesProvider: () -> ByteArray,
        sourceLang: String = "ja",
    ) {
        if (translationCache.isPageInCache(chapterId, chapterUrl, pageIndex, targetLang)) return
        scope.launch {
            translatePage(
                chapterId = chapterId,
                chapterUrl = chapterUrl,
                pageIndex = pageIndex,
                targetLang = targetLang,
                rawImageBytesProvider = rawImageBytesProvider,
                force = false,
                sourceLang = sourceLang,
            )
        }
    }

    fun getOrTranslateStream(
        chapterId: Long?,
        chapterUrl: String,
        pageIndex: Int,
        targetLang: String,
        rawStreamProvider: () -> InputStream,
        sourceLang: String = "ja",
        onTranslationError: ((String) -> Unit)? = null,
    ): InputStream {
        if (translationCache.isPageInCache(chapterId, chapterUrl, pageIndex, targetLang)) {
            return translationCache.getImageStream(chapterId, chapterUrl, pageIndex, targetLang)
        }

        return try {
            val rawBytes = rawStreamProvider().use { it.readBytes() }
            val result = runBlocking(Dispatchers.IO) {
                translatePage(
                    chapterId = chapterId,
                    chapterUrl = chapterUrl,
                    pageIndex = pageIndex,
                    targetLang = targetLang,
                    rawImageBytesProvider = { rawBytes },
                    force = false,
                    sourceLang = sourceLang,
                )
            }
            when (result) {
                is TranslationResult.Success -> {
                    translationCache.getImageStream(chapterId, chapterUrl, pageIndex, targetLang)
                }
                is TranslationResult.Skipped -> {
                    ByteArrayInputStream(rawBytes)
                }
                is TranslationResult.Error -> {
                    onTranslationError?.invoke(result.message)
                    ByteArrayInputStream(rawBytes)
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error getting translated stream" }
            onTranslationError?.invoke(e.message ?: "Translation error")
            rawStreamProvider()
        }
    }

    data class TranslationStageResult(
        val succeeded: Int,
        val failed: Int,
    )

    sealed interface TranslationResult {
        data class Success(val imageBytes: ByteArray, val bubbles: List<SpeechBubble> = emptyList()) :
            TranslationResult
        data class Skipped(val reason: String) : TranslationResult
        data class Error(val message: String) : TranslationResult
    }
}
