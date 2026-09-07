package eu.kanade.tachiyomi.data.coil

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.github.penfeizhou.animation.avif.AVIFDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer

class AvifAnimatedDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val loader = object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer {
                return ByteBuffer.wrap(bytes)
            }
        }
        val drawable = AVIFDrawable(loader)
        drawable.intrinsicWidth
        drawable.intrinsicHeight
        return DecodeResult(
            image = drawable.asImage(),
            isSampled = false,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            return if (isApplicable(result.source.source())) {
                AvifAnimatedDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            return try {
                val peek = source.peek()
                val header = ByteArray(64)
                val read = peek.read(header)
                if (read >= 12 && ImageUtil.isAnimatedAvif(header.copyOf(read))) {
                    return true
                }
                val decoder = source.peek().inputStream().use {
                    try {
                        ca.mpreg.imagedecoder.ImageDecoder.new(it)
                    } catch (e: Exception) {
                        null
                    }
                } ?: return false
                decoder.format == "avif" && decoder.pages > 1
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to check if animated AVIF" }
                false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }
}
