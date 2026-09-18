package eu.kanade.tachiyomi.data.translation.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.translation.ocr.SpeechBubble
import eu.kanade.tachiyomi.data.translation.ocr.TextType
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

@Inject
@SingleIn(AppScope::class)
class MangaPageRenderer {

    private val mangaTypeface: Typeface by lazy {
        try {
            Typeface.create("sans-serif-condensed", Typeface.BOLD)
        } catch (_: Exception) {
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }

    /**
     * Renders translated text on top of speech bubbles, erasing the original raw text,
     * and returns the final rendered lossless PNG byte array.
     */
    fun renderToBytes(
        originalBitmap: Bitmap,
        bubbles: List<SpeechBubble>,
        compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    ): ByteArray {
        val renderedBitmap = render(originalBitmap, bubbles)
        val stream = ByteArrayOutputStream()
        renderedBitmap.compress(compressFormat, 100, stream)
        renderedBitmap.recycle()
        return stream.toByteArray()
    }

    /**
     * Creates a copy of [originalBitmap], erases original text in each bubble,
     * and renders the translated text with auto-fitting font size and styled outlines.
     */
    fun render(
        originalBitmap: Bitmap,
        bubbles: List<SpeechBubble>,
    ): Bitmap {
        val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        for (bubble in bubbles) {
            val translatedText = bubble.translatedText?.trim()
            if (translatedText.isNullOrEmpty()) continue
            if (translatedText.equals(bubble.originalText.trim(), ignoreCase = true)) continue

            // 1. Analyze bubble background color and uniformity
            val bgInfo = sampleBubbleBackground(resultBitmap, bubble)

            // 2. Inpaint / clean original text
            cleanBubble(canvas, bubble, bgInfo, resultBitmap.width, resultBitmap.height)

            // 3. Render translated text with auto-fitting font size and high contrast outline
            drawTranslatedText(canvas, bubble, translatedText, bgInfo)
        }

        return resultBitmap
    }

    /**
     * Samples a thin border strip immediately outside the text bounding box on all four sides
     * to determine the dominant background color and compute variance (standard deviation)
     * for deciding between flat-filling a solid box vs preserving underlying artwork.
     */
    private fun sampleBubbleBackground(bitmap: Bitmap, bubble: SpeechBubble): BubbleBgInfo {
        val rect = bubble.boundingBox
        val bmpWidth = bitmap.width
        val bmpHeight = bitmap.height

        val sampledColors = mutableListOf<Int>()
        val stripThickness = 4

        // Top strip: immediately above the bounding box
        if (rect.top > 0) {
            val topStart = maxOf(0, rect.top - stripThickness)
            val topEnd = rect.top - 1
            val stepX = maxOf(1, rect.width() / 40)
            for (y in topStart..topEnd) {
                var x = maxOf(0, rect.left)
                val maxX = minOf(bmpWidth - 1, rect.right)
                while (x <= maxX) {
                    sampledColors.add(bitmap.getPixel(x, y))
                    x += stepX
                }
            }
        }

        // Bottom strip: immediately below the bounding box
        if (rect.bottom < bmpHeight - 1) {
            val bottomStart = rect.bottom + 1
            val bottomEnd = minOf(bmpHeight - 1, rect.bottom + stripThickness)
            val stepX = maxOf(1, rect.width() / 40)
            for (y in bottomStart..bottomEnd) {
                var x = maxOf(0, rect.left)
                val maxX = minOf(bmpWidth - 1, rect.right)
                while (x <= maxX) {
                    sampledColors.add(bitmap.getPixel(x, y))
                    x += stepX
                }
            }
        }

        // Left strip: immediately to the left of the bounding box
        if (rect.left > 0) {
            val leftStart = maxOf(0, rect.left - stripThickness)
            val leftEnd = rect.left - 1
            val stepY = maxOf(1, rect.height() / 40)
            for (x in leftStart..leftEnd) {
                var y = maxOf(0, rect.top)
                val maxY = minOf(bmpHeight - 1, rect.bottom)
                while (y <= maxY) {
                    sampledColors.add(bitmap.getPixel(x, y))
                    y += stepY
                }
            }
        }

        // Right strip: immediately to the right of the bounding box
        if (rect.right < bmpWidth - 1) {
            val rightStart = rect.right + 1
            val rightEnd = minOf(bmpWidth - 1, rect.right + stripThickness)
            val stepY = maxOf(1, rect.height() / 40)
            for (x in rightStart..rightEnd) {
                var y = maxOf(0, rect.top)
                val maxY = minOf(bmpHeight - 1, rect.bottom)
                while (y <= maxY) {
                    sampledColors.add(bitmap.getPixel(x, y))
                    y += stepY
                }
            }
        }

        if (sampledColors.isEmpty()) {
            return BubbleBgInfo(bgColor = Color.WHITE, isLight = true, isSolid = true)
        }

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        val luminances = DoubleArray(sampledColors.size)

        for (i in sampledColors.indices) {
            val pixel = sampledColors[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            sumR += r
            sumG += g
            sumB += b
            luminances[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }

        val count = sampledColors.size
        val avgR = (sumR / count).toInt().coerceIn(0, 255)
        val avgG = (sumG / count).toInt().coerceIn(0, 255)
        val avgB = (sumB / count).toInt().coerceIn(0, 255)
        val avgColor = Color.rgb(avgR, avgG, avgB)

        val avgLum = luminances.average()
        val isLight = avgLum >= 128.0

        var sumSqDiff = 0.0
        for (lum in luminances) {
            val diff = lum - avgLum
            sumSqDiff += diff * diff
        }
        val variance = sumSqDiff / count
        val stdDev = sqrt(variance)

        // Low variance (uniform color) -> treat as solid box/fill background
        // High variance (textured/art-like) -> treat as floating text directly over artwork
        val isSolid = stdDev <= 45.0

        return BubbleBgInfo(bgColor = avgColor, isLight = isLight, isSolid = isSolid)
    }

    /**
     * Cleans original text out of the speech bubble or narration block.
     * Uses solid flat-fill if background has low color variance,
     * or line-by-line cleaning to preserve underlying artwork if high variance.
     */
    private fun cleanBubble(
        canvas: Canvas,
        bubble: SpeechBubble,
        bgInfo: BubbleBgInfo,
        bmpWidth: Int,
        bmpHeight: Int,
    ) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgInfo.bgColor
            style = Paint.Style.FILL
        }

        if (bgInfo.isSolid) {
            // Low variance: flat-fill the entire bounding region with the sampled average color
            val rect = bubble.boundingBox
            val padding = 3f
            val left = (rect.left - padding).coerceIn(0f, bmpWidth.toFloat() - 1f)
            val top = (rect.top - padding).coerceIn(0f, bmpHeight.toFloat() - 1f)
            val right = (rect.right + padding).coerceIn(0f, bmpWidth.toFloat() - 1f)
            val bottom = (rect.bottom + padding).coerceIn(0f, bmpHeight.toFloat() - 1f)

            val width = right - left
            val height = bottom - top
            val cornerRadius = if (bubble.textType == TextType.NARRATION) 3f else minOf(width, height) * 0.25f

            canvas.drawRoundRect(RectF(left, top, right, bottom), cornerRadius, cornerRadius, fillPaint)
        } else {
            // High variance: treat as floating text directly over artwork.
            // Clean individual text line bounding boxes to avoid wiping out the artwork.
            for (block in bubble.blocks) {
                for (line in block.lines) {
                    val lineRect = line.boundingBox
                    val padding = 2f
                    val left = (lineRect.left - padding).coerceIn(0f, bmpWidth.toFloat() - 1f)
                    val top = (lineRect.top - padding).coerceIn(0f, bmpHeight.toFloat() - 1f)
                    val right = (lineRect.right + padding).coerceIn(0f, bmpWidth.toFloat() - 1f)
                    val bottom = (lineRect.bottom + padding).coerceIn(0f, bmpHeight.toFloat() - 1f)

                    canvas.drawRoundRect(RectF(left, top, right, bottom), 3f, 3f, fillPaint)
                }
            }
        }
    }

    /**
     * Renders translated text into the bounding box with auto-fitting font size
     * and dual-layer stroke/fill for optimal readability. Handles narration prose
     * wrapping and alignment appropriately.
     */
    private fun drawTranslatedText(
        canvas: Canvas,
        bubble: SpeechBubble,
        text: String,
        bgInfo: BubbleBgInfo,
    ) {
        val rect = bubble.boundingBox
        val width = rect.width()
        val height = rect.height()

        val isNarration = bubble.textType == TextType.NARRATION
        val hPadding = if (isNarration) {
            (width * 0.04f).coerceIn(
                3f,
                12f,
            ).toInt()
        } else {
            (width * 0.08f).coerceIn(4f, 20f).toInt()
        }
        val vPadding = if (isNarration) {
            (height * 0.04f).coerceIn(
                3f,
                12f,
            ).toInt()
        } else {
            (height * 0.08f).coerceIn(4f, 20f).toInt()
        }

        val availableWidth = (width - hPadding * 2).coerceAtLeast(24)
        val availableHeight = (height - vPadding * 2).coerceAtLeast(20)

        // Configure text colors based on background luminance
        val textColor = if (bgInfo.isLight) Color.rgb(18, 18, 18) else Color.WHITE
        val outlineColor = if (bgInfo.isLight) Color.WHITE else Color.rgb(18, 18, 18)

        val alignment = if (isNarration && availableWidth > 220) {
            Layout.Alignment.ALIGN_NORMAL
        } else {
            Layout.Alignment.ALIGN_CENTER
        }

        // Find optimal font size using binary search
        val minFontSize = 9f
        val maxFontSize = if (isNarration) {
            (availableHeight * 0.75f).coerceIn(14f, 48f)
        } else {
            (availableHeight * 0.55f).coerceIn(16f, 64f)
        }
        val bestFontSize = findOptimalFontSize(
            text = text,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            minSize = minFontSize,
            maxSize = maxFontSize,
            alignment = alignment,
        )

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = mangaTypeface
            textSize = bestFontSize
        }

        val outlineWidth = (bestFontSize * 0.12f).coerceIn(1.5f, 4f)
        val strokePaint = TextPaint(textPaint).apply {
            color = outlineColor
            style = Paint.Style.STROKE
            strokeWidth = outlineWidth
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        val fillPaint = TextPaint(textPaint).apply {
            color = textColor
            style = Paint.Style.FILL
        }

        val strokeLayout = buildStaticLayout(text, strokePaint, availableWidth, alignment)
        val fillLayout = buildStaticLayout(text, fillPaint, availableWidth, alignment)

        // Center vertically within available bubble bounds
        val startX = rect.left + hPadding
        val startY = rect.top + vPadding + (availableHeight - fillLayout.height) / 2f

        canvas.save()
        canvas.translate(startX.toFloat(), startY)
        strokeLayout.draw(canvas)
        fillLayout.draw(canvas)
        canvas.restore()
    }

    private fun findOptimalFontSize(
        text: String,
        availableWidth: Int,
        availableHeight: Int,
        minSize: Float,
        maxSize: Float,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER,
    ): Float {
        var low = minSize
        var high = maxSize
        var best = minSize

        val testPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mangaTypeface
        }

        for (i in 0 until 8) {
            val mid = (low + high) / 2f
            testPaint.textSize = mid
            val layout = buildStaticLayout(text, testPaint, availableWidth, alignment)

            var fitsWidth = true
            for (lineIdx in 0 until layout.lineCount) {
                if (layout.getLineWidth(lineIdx) > availableWidth) {
                    fitsWidth = false
                    break
                }
            }

            if (layout.height <= availableHeight && fitsWidth) {
                best = mid
                low = mid + 0.5f
            } else {
                high = mid - 0.5f
            }
        }

        return best
    }

    private fun buildStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER,
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.95f)
            .build()
    }

    private data class BubbleBgInfo(
        val bgColor: Int,
        val isLight: Boolean,
        val isSolid: Boolean,
    )
}
