package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.text.format.Formatter
import com.jakewharton.disklrucache.DiskLruCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import okio.buffer
import okio.sink
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.io.InputStream

private const val PARAMETER_CACHE_SIZE = 150L * 1024 * 1024 // 150 MiB
private const val PARAMETER_APP_VERSION = 1
private const val PARAMETER_VALUE_COUNT = 1

@Inject
@SingleIn(AppScope::class)
class TranslationCache(
    private val context: Context,
) {

    private var diskCache = DiskLruCache.open(
        File(context.cacheDir, "translation_disk_cache"),
        PARAMETER_APP_VERSION,
        PARAMETER_VALUE_COUNT,
        PARAMETER_CACHE_SIZE,
    )

    private val cacheDir: File
        get() = diskCache.directory

    val readableSize: String
        get() = Formatter.formatFileSize(context, DiskUtil.getDirectorySize(cacheDir))

    fun getCacheKey(chapterId: Long?, chapterUrl: String, pageIndex: Int, targetLang: String): String {
        val id = chapterId ?: -1L
        return DiskUtil.hashKeyForDisk("tr_${id}_${chapterUrl}_${pageIndex}_${targetLang.uppercase()}")
    }

    fun isPageInCache(chapterId: Long?, chapterUrl: String, pageIndex: Int, targetLang: String): Boolean {
        return try {
            val key = getCacheKey(chapterId, chapterUrl, pageIndex, targetLang)
            val inJournal = diskCache.get(key).use { it != null }
            val fileExists = getImageFile(chapterId, chapterUrl, pageIndex, targetLang).exists()
            inJournal && fileExists
        } catch (_: IOException) {
            false
        }
    }

    fun getImageFile(chapterId: Long?, chapterUrl: String, pageIndex: Int, targetLang: String): File {
        val key = getCacheKey(chapterId, chapterUrl, pageIndex, targetLang)
        return File(diskCache.directory, "$key.0")
    }

    fun getImageStream(chapterId: Long?, chapterUrl: String, pageIndex: Int, targetLang: String): InputStream {
        val file = getImageFile(chapterId, chapterUrl, pageIndex, targetLang)
        return file.inputStream()
    }

    fun putImageToCache(
        chapterId: Long?,
        chapterUrl: String,
        pageIndex: Int,
        targetLang: String,
        imageBytes: ByteArray,
    ) {
        var editor: DiskLruCache.Editor? = null
        try {
            val key = getCacheKey(chapterId, chapterUrl, pageIndex, targetLang)
            editor = diskCache.edit(key) ?: return

            editor.newOutputStream(0).sink().buffer().use { sink ->
                sink.write(imageBytes)
                sink.flush()
            }

            diskCache.flush()
            editor.commit()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to put translated image to cache" }
        } finally {
            editor?.abortUnlessCommitted()
        }
    }

    fun clear(): Long {
        val size = DiskUtil.getDirectorySize(cacheDir)
        try {
            diskCache.delete()
            diskCache = DiskLruCache.open(
                File(context.cacheDir, "translation_disk_cache"),
                PARAMETER_APP_VERSION,
                PARAMETER_VALUE_COUNT,
                PARAMETER_CACHE_SIZE,
            )
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to clear translation cache" }
        }
        return size
    }
}
