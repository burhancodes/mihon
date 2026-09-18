package eu.kanade.tachiyomi.data.translation.ocr

import android.graphics.Rect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs
import kotlin.math.max

enum class TextType {
    DIALOGUE,
    NARRATION,
}

@Inject
@SingleIn(AppScope::class)
class MangaBubbleClusterer {

    internal var rectFactory: (Int, Int, Int, Int) -> Rect = { l, t, r, b -> Rect(l, t, r, b) }

    private val Rect.w: Int get() = right - left
    private val Rect.h: Int get() = bottom - top
    private val Rect.cX: Int get() = left + (right - left) / 2
    private val Rect.cY: Int get() = top + (bottom - top) / 2

    fun cluster(
        blocks: List<OcrBlock>,
        isRtl: Boolean = true,
    ): List<SpeechBubble> {
        val validBlocks = blocks.filterNot { isNoiseText(it.text) }
        if (validBlocks.isEmpty()) return emptyList()

        if (validBlocks.size == 1) {
            val b = validBlocks[0]
            val isNarr = isNarrationCandidate(b)
            return listOf(
                SpeechBubble(
                    id = 1,
                    boundingBox = rectFactory(
                        b.boundingBox.left,
                        b.boundingBox.top,
                        b.boundingBox.right,
                        b.boundingBox.bottom,
                    ),
                    originalText = b.text,
                    blocks = listOf(b),
                    isVertical = b.boundingBox.h > b.boundingBox.w,
                    textType = if (isNarr) TextType.NARRATION else TextType.DIALOGUE,
                ),
            )
        }

        val avgHeight = validBlocks.map { it.boundingBox.h.toDouble() }.average().coerceIn(16.0, 120.0)
        val thresholdX = (avgHeight * 1.5).coerceIn(20.0, 75.0)
        val thresholdY = (avgHeight * 1.2).coerceIn(15.0, 60.0)

        // Classify each block by geometry
        val isNarration = BooleanArray(validBlocks.size) { isNarrationCandidate(validBlocks[it]) }

        // Disjoint Set Union (DSU)
        val parent = IntArray(validBlocks.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) {
                root = parent[root]
            }
            var curr = i
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) {
                parent[rootI] = rootJ
            }
        }

        for (i in validBlocks.indices) {
            for (j in i + 1 until validBlocks.size) {
                val b1 = validBlocks[i].boundingBox
                val b2 = validBlocks[j].boundingBox

                // Strategy 1: Narration/caption candidate grouping
                // Multiple stacked lines with small consistent vertical gaps and a shared left margin
                val leftDiff = abs(b1.left - b2.left)
                val rightDiff = abs(b1.right - b2.right)
                val centerDiff = abs(b1.cX - b2.cX)
                val isAligned = if (isRtl &&
                    (validBlocks[i].lines.isNotEmpty() && validBlocks[i].boundingBox.h > validBlocks[i].boundingBox.w)
                ) {
                    rightDiff <= 50
                } else {
                    leftDiff <= 50 || centerDiff <= 40
                }

                val vGap = if (b1.bottom <= b2.top) {
                    b2.top - b1.bottom
                } else if (b2.bottom <= b1.top) {
                    b1.top - b2.bottom
                } else {
                    0
                }

                val avgLineH = maxOf(
                    16.0,
                    (
                        b1.h.toDouble() / maxOf(1, validBlocks[i].lines.size) +
                            b2.h.toDouble() / maxOf(1, validBlocks[j].lines.size)
                        ) / 2.0,
                )
                val isVerticallyProximate = vGap <= (avgLineH * 2.2).coerceAtLeast(40.0)

                val horizontalOverlap = minOf(b1.right, b2.right) - maxOf(b1.left, b2.left)
                val isWide = b1.w >= 150 || b2.w >= 150

                val isNarrationGroup = isAligned && isVerticallyProximate && (horizontalOverlap > 0 || isWide) &&
                    (isNarration[i] || isNarration[j] || (b1.w >= 140 && b2.w >= 140))

                if (isNarrationGroup) {
                    union(i, j)
                    continue
                }

                // Strategy 2: Dialogue candidate grouping (compact bounding box, balanced aspect ratio)
                val dx = max(0, max(b1.left - b2.right, b2.left - b1.right))
                val dy = max(0, max(b1.top - b2.bottom, b2.top - b1.bottom))

                if (dx <= thresholdX && dy <= thresholdY) {
                    union(i, j)
                }
            }
        }

        val clustersMap = mutableMapOf<Int, MutableList<OcrBlock>>()
        for (i in validBlocks.indices) {
            val root = find(i)
            clustersMap.getOrPut(root) { mutableListOf() }.add(validBlocks[i])
        }

        val rawBubbles = clustersMap.values.map { clusterBlocks ->
            var minL = Int.MAX_VALUE
            var minT = Int.MAX_VALUE
            var maxR = Int.MIN_VALUE
            var maxB = Int.MIN_VALUE

            for (b in clusterBlocks) {
                minL = minOf(minL, b.boundingBox.left)
                minT = minOf(minT, b.boundingBox.top)
                maxR = maxOf(maxR, b.boundingBox.right)
                maxB = maxOf(maxB, b.boundingBox.bottom)
            }

            val bubbleRect = rectFactory(minL, minT, maxR, maxB)
            val isVertical = bubbleRect.h >= bubbleRect.w

            // Classify cluster as NARRATION if any constituent block was narration or if wide aspect ratio with multiple lines
            val totalLines = clusterBlocks.sumOf { it.lines.size }
            val isClusterNarration = clusterBlocks.any { isNarrationCandidate(it) } ||
                (bubbleRect.w.toDouble() / maxOf(1, bubbleRect.h) >= 2.0 && totalLines >= 2) ||
                (bubbleRect.w >= 250 && totalLines >= 2)

            val sortedBlocks = if (isRtl && isVertical) {
                // Japanese manga vertical text
                clusterBlocks.sortedWith { o1, o2 ->
                    val r1 = o1.boundingBox
                    val r2 = o2.boundingBox
                    val colDiff = abs(r1.cX - r2.cX)
                    if (colDiff > thresholdX * 0.5) {
                        r2.cX.compareTo(r1.cX)
                    } else {
                        r1.top.compareTo(r2.top)
                    }
                }
            } else {
                // Horizontal text: top-to-bottom primarily
                clusterBlocks.sortedWith { o1, o2 ->
                    val r1 = o1.boundingBox
                    val r2 = o2.boundingBox
                    val rowDiff = abs(r1.cY - r2.cY)
                    if (rowDiff > thresholdY * 0.5) {
                        r1.top.compareTo(r2.top)
                    } else {
                        r1.left.compareTo(r2.left)
                    }
                }
            }

            val combinedText = sortedBlocks.joinToString("\n") { it.text.trim() }

            SpeechBubble(
                id = 0,
                boundingBox = bubbleRect,
                originalText = combinedText,
                blocks = sortedBlocks,
                isVertical = isVertical,
                textType = if (isClusterNarration) TextType.NARRATION else TextType.DIALOGUE,
            )
        }

        val cleanBubbles = rawBubbles.filterNot { isNoiseText(it.originalText) }

        // Sort bubbles in overall reading order of the manga page
        val sortedBubbles = if (isRtl) {
            cleanBubbles.sortedWith { b1, b2 ->
                val r1 = b1.boundingBox
                val r2 = b2.boundingBox
                val yDiff = abs(r1.cY - r2.cY)
                if (yDiff > 120) {
                    r1.top.compareTo(r2.top)
                } else {
                    r2.right.compareTo(r1.right)
                }
            }
        } else {
            cleanBubbles.sortedWith { b1, b2 ->
                val r1 = b1.boundingBox
                val r2 = b2.boundingBox
                val yDiff = abs(r1.cY - r2.cY)
                if (yDiff > 120) {
                    r1.top.compareTo(r2.top)
                } else {
                    r1.left.compareTo(r2.left)
                }
            }
        }

        return sortedBubbles.mapIndexed { index, bubble ->
            bubble.copy(id = index + 1)
        }
    }

    fun isNarrationCandidate(b: OcrBlock): Boolean {
        val width = b.boundingBox.w
        val height = b.boundingBox.h
        val aspectRatio = width.toDouble() / maxOf(1, height)

        // Case 1: Multi-line block with consistent left margin
        if (b.lines.size >= 2) {
            val lefts = b.lines.map { it.boundingBox.left }
            val leftDiff = lefts.maxOrNull()!! - lefts.minOrNull()!!
            if (leftDiff <= 50 && width >= 140) {
                return true
            }
        }

        // Case 2: Wide block spanning a large fraction of panel width
        if (aspectRatio >= 1.8 && width >= 150) {
            return true
        }

        return false
    }

    internal fun isNoiseText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        // Legitimate manga expressions/reactions like "!", "!!", "?", "??", "?!", "!?", "...", "…", etc.
        // must pass through and not be filtered as noise.
        val isReaction = trimmed.all { c ->
            c == '!' || c == '?' || c == '.' || c == '…' || c == '！' || c == '？' || c == '。' ||
                c == '~' || c == '♡' || c == '♥'
        }
        if (isReaction) return false

        val hasMeaningfulChar = trimmed.any { c ->
            c.isLetter() || c.isDigit() ||
                c in '\uAC00'..'\uD7A3' ||
                c in '\u1100'..'\u11FF' ||
                c in '\u3040'..'\u30FF' ||
                c in '\u4E00'..'\u9FFF'
        }

        // Filter blocks that are BOTH short (length <= 2) AND contain exclusively punctuation/bracket characters
        // with no letters or CJK characters at all.
        if (!hasMeaningfulChar && trimmed.length <= 2) {
            return true
        }

        // Filter isolated single Latin letters (commonly false positives on line art: "R", "I", "l", etc.)
        if (trimmed.length == 1 && (trimmed[0] in 'A'..'Z' || trimmed[0] in 'a'..'z')) {
            return true
        }

        return false
    }
}

data class SpeechBubble(
    val id: Int,
    val boundingBox: Rect,
    val originalText: String,
    val blocks: List<OcrBlock>,
    val isVertical: Boolean,
    var translatedText: String? = null,
    val textType: TextType = TextType.DIALOGUE,
    var translationFailed: Boolean = false,
)
