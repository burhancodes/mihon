package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    var originalStream: (() -> InputStream)? = stream
    var translationStreamProvider: ((() -> InputStream) -> InputStream)? = null
    var isTranslated: Boolean = false

    var stream: (() -> InputStream)? = stream
        get() {
            val original = originalStream ?: field
            val provider = translationStreamProvider
            return if (provider != null && original != null) {
                { provider(original) }
            } else {
                field ?: original
            }
        }
        set(value) {
            field = value
            originalStream = value
        }
}
