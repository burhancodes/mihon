package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterCache: ChapterCache,
    private val translationManager: TranslationManager,
    private val readerPreferences: ReaderPreferences,
    private val manga: Manga,
    private val source: Source,
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { page ->
                        page.chapter = chapter
                        setupTranslation(page, chapter)
                    }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read) {
                    chapter.requestedPage = chapter.chapter.last_page_read
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    private fun setupTranslation(page: ReaderPage, chapter: ReaderChapter) {
        val chapterId = chapter.chapter.id
        val chapterUrl = chapter.chapter.url

        val srcLang = when {
            source.lang.startsWith("ko", ignoreCase = true) -> "ko"
            source.lang.startsWith("zh", ignoreCase = true) -> "zh"
            source.lang.startsWith("ja", ignoreCase = true) -> "ja"
            else -> source.lang
        }

        page.translationStreamProvider = { rawStream ->
            val targetLang = readerPreferences.translationTargetLanguage.get()
            val isLiveTranslationEnabled = readerPreferences.liveTranslation.get()

            val cached = if (page.isTranslated ||
                translationManager.isPageTranslated(chapterId, chapterUrl, page.index, targetLang)
            ) {
                translationManager.getCachedStream(chapterId, chapterUrl, page.index, targetLang)
            } else {
                null
            }

            val isRaw = isRawManga(manga, source, targetLang)
            if (cached != null) {
                page.isTranslated = true
                cached
            } else if (isLiveTranslationEnabled && isRaw) {
                logcat(LogPriority.INFO) {
                    "Live translation triggered for '${manga.title}' page ${page.index} (sourceLang=$srcLang, targetLang=$targetLang)"
                }
                val stream = translationManager.getOrTranslateStream(
                    chapterId = chapterId,
                    chapterUrl = chapterUrl,
                    pageIndex = page.index,
                    targetLang = targetLang,
                    rawStreamProvider = rawStream,
                    sourceLang = srcLang,
                )
                page.isTranslated = translationManager.isPageTranslated(
                    chapterId = chapterId,
                    chapterUrl = chapterUrl,
                    pageIndex = page.index,
                    targetLang = targetLang,
                )
                stream
            } else {
                rawStream()
            }
        }
    }

    internal fun isRawManga(manga: Manga, source: Source, targetLang: String): Boolean {
        val srcLang = source.lang.lowercase()
        val target = targetLang.lowercase()
        val isTargetEnglish = target == "eng" || target == "en"
        val isSourceEnglish = srcLang == "en" || srcLang == "eng"

        val isExplicitlyRaw = isExplicitlyRaw(
            title = manga.title,
            genre = manga.genre,
            description = manga.description,
            url = manga.url,
        )

        if (isTargetEnglish && isSourceEnglish && !isExplicitlyRaw) {
            return false
        }
        if (srcLang == target && !isExplicitlyRaw) {
            return false
        }
        return true
    }

    internal fun isExplicitlyRaw(
        title: String,
        genre: List<String>?,
        description: String?,
        url: String? = null,
    ): Boolean {
        return rawWordRegex.containsMatchIn(title) ||
            (url != null && rawWordRegex.containsMatchIn(url)) ||
            genre?.any { rawWordRegex.containsMatchIn(it) } == true ||
            (description != null && rawWordRegex.containsMatchIn(description))
    }

    companion object {
        private val rawWordRegex = Regex("""\braw\b""", RegexOption.IGNORE_CASE)
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloadedOnDisk(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
        return when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
            )
            source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                    is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
                }
            }
            source is HttpSource -> HttpPageLoader(
                chapter,
                source,
                chapterCache,
                translationManager,
                readerPreferences,
            )
            source is StubSource -> error(context.stringResource(MR.strings.source_not_installed, source.toString()))
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
    }
}
