package tv.own.owntv.mobile.ui.screens.multiview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The grid shapes a user can pick, one choice for every tile count, from the tile menu.
 *
 * AerioTV's `MultiviewLayoutMode` for the counts this grid can reach. Its Hero + Corner is a
 * six-tile shape and so has no place in a grid that stops at five; Spotlight is not a shape here
 * either, but the per-tile "Spotlight this tile" action, exactly as there.
 */
enum class MultiviewLayoutMode(val key: String) {
    /** The built-in table for the current count. */
    Auto("auto"),

    /** Equal tiles: rows = floor(sqrt n), last partial row centred. */
    EvenGrid("evenGrid"),

    /** One full-width column, whatever the orientation. */
    Stacked("stacked");

    companion object {
        fun from(key: String?): MultiviewLayoutMode = entries.firstOrNull { it.key == key } ?: Auto

        /** Only where a real alternative exists, so the choice never offers the same picture twice. */
        fun available(forTileCount: Int): List<MultiviewLayoutMode> = when (forTileCount) {
            2, 4 -> listOf(Auto, Stacked)
            3 -> listOf(Auto, EvenGrid, Stacked)
            5 -> listOf(Auto, EvenGrid)
            else -> emptyList()
        }
    }
}

/**
 * Where each tile goes: AerioTV's `MultiviewGridMath`, ported line for line for one to five tiles.
 *
 * Pure geometry, in whatever unit it is given — dp for placing tiles, px for finding the tile under
 * a finger. The layout tables describe a landscape (or square) area; a taller-than-wide area takes
 * the portrait path, where the hero shapes turn so the big tile sits on top and the even grids pick
 * whichever split paints the most 16:9 picture. Spacing is 0 from the screen, which pads each tile
 * instead; the shapes are the same either way.
 */
object MultiviewGridMath {

    private fun rect(x: Float, y: Float, w: Float, h: Float) = Rect(x, y, x + w, y + h)

    fun rects(mode: MultiviewLayoutMode, count: Int, width: Float, height: Float, spacing: Float = 0f): List<Rect> =
        when (mode) {
            MultiviewLayoutMode.Auto -> autoRects(count, width, height, spacing)
            MultiviewLayoutMode.EvenGrid -> evenGridRects(count, width, height, spacing)
            MultiviewLayoutMode.Stacked -> stackedRects(count, width, height, spacing)
        }

    /**
     * The rect for each tile in display order, with Spotlight applied: the spotlit tile takes the big
     * rect and the others fill the rest in order, so switching it on grows one tile rather than
     * reshuffling the grid. [spotlightIndex] out of range means no spotlight.
     */
    fun resolvedRects(
        mode: MultiviewLayoutMode,
        count: Int,
        spotlightIndex: Int?,
        width: Float,
        height: Float,
        spacing: Float = 0f,
    ): List<Rect> {
        val hero = spotlightIndex?.takeIf { it in 0 until count }
        if (hero != null && count > 1) {
            val spot = spotlightRects(count, width, height, spacing)
            if (spot.size == count) {
                var nextSmall = 1
                return List(count) { i -> if (i == hero) spot[0] else spot[nextSmall++] }
            }
        }
        return rects(mode, count, width, height, spacing)
    }

    fun stackedRects(count: Int, w: Float, h: Float, s: Float): List<Rect> {
        if (count <= 0 || w <= 0f || h <= 0f) return emptyList()
        val n = count.coerceAtMost(MultiviewModel.MAX_TILES)
        return gridRects(cols = 1, rows = n, count = n, w = w, h = h, s = s)
    }

    private fun autoRects(count: Int, w: Float, h: Float, s: Float): List<Rect> {
        if (count <= 0 || w <= 0f || h <= 0f) return emptyList()
        val n = count.coerceAtMost(MultiviewModel.MAX_TILES)
        if (isPortrait(w, h)) return portraitRects(n, w, h, s)
        return when (n) {
            1 -> layout1(w, h)
            2 -> layout2(w, h, s)
            3 -> layout3(w, h, s)
            4 -> uniformGrid(cols = 2, rows = 2, w = w, h = h, s = s)
            else -> layout5(w, h, s)
        }
    }

    private fun layout1(w: Float, h: Float) = listOf(rect(0f, 0f, w, h))

    /** Side by side, full height. */
    private fun layout2(w: Float, h: Float, s: Float): List<Rect> {
        val tw = (w - s) / 2
        return listOf(rect(0f, 0f, tw, h), rect(tw + s, 0f, tw, h))
    }

    /** Big tile left (2/3 width, full height); two small ones stacked on the right. */
    private fun layout3(w: Float, h: Float, s: Float): List<Rect> {
        val bigW = (w - s) * 2 / 3
        val smallW = w - bigW - s
        val smallH = (h - s) / 2
        return listOf(
            rect(0f, 0f, bigW, h),
            rect(bigW + s, 0f, smallW, smallH),
            rect(bigW + s, smallH + s, smallW, smallH),
        )
    }

    /**
     * A 3x3 whose top-left 2x2 is one big tile, then two down the right and two along the bottom;
     * the bottom-right cell stays empty. Order: big, (2,0), (2,1), (0,2), (1,2).
     */
    private fun layout5(w: Float, h: Float, s: Float): List<Rect> {
        val cellW = (w - 2 * s) / 3
        val cellH = (h - 2 * s) / 3
        val bigW = 2 * cellW + s
        val bigH = 2 * cellH + s
        val col2X = 2 * (cellW + s)
        val row2Y = 2 * (cellH + s)
        return listOf(
            rect(0f, 0f, bigW, bigH),
            rect(col2X, 0f, cellW, cellH),
            rect(col2X, cellH + s, cellW, cellH),
            rect(0f, row2Y, cellW, cellH),
            rect(cellW + s, row2Y, cellW, cellH),
        )
    }

    private fun uniformGrid(cols: Int, rows: Int, w: Float, h: Float, s: Float): List<Rect> {
        val tw = (w - (cols - 1) * s) / cols
        val th = (h - (rows - 1) * s) / rows
        return buildList {
            for (row in 0 until rows) for (col in 0 until cols) add(rect(col * (tw + s), row * (th + s), tw, th))
        }
    }

    fun evenGridRects(count: Int, w: Float, h: Float, s: Float): List<Rect> {
        if (count <= 0 || w <= 0f || h <= 0f) return emptyList()
        val n = count.coerceAtMost(MultiviewModel.MAX_TILES)
        val (cols, rows) = if (isPortrait(w, h)) {
            bestFitGrid(n, w, h, s)
        } else {
            val r = maxOf(1, sqrt(n.toDouble()).toInt())
            ceil(n.toDouble() / r).toInt() to r
        }
        return gridRects(cols, rows, n, w, h, s)
    }

    /** `cols x rows` in reading order, the last partial row centred. */
    private fun gridRects(cols: Int, rows: Int, count: Int, w: Float, h: Float, s: Float): List<Rect> {
        val tw = (w - (cols - 1) * s) / cols
        val th = (h - (rows - 1) * s) / rows
        val rects = ArrayList<Rect>(count)
        var remaining = count
        for (row in 0 until rows) {
            val inRow = minOf(cols, remaining)
            if (inRow <= 0) break
            val rowW = inRow * tw + (inRow - 1) * s
            val xStart = (w - rowW) / 2
            for (i in 0 until inRow) rects.add(rect(xStart + i * (tw + s), row * (th + s), tw, th))
            remaining -= inRow
        }
        return rects
    }

    /** One big tile (2/3) and the rest stacked beside it — or, in portrait, beneath it. */
    fun spotlightRects(count: Int, w: Float, h: Float, s: Float): List<Rect> {
        if (count <= 0 || w <= 0f || h <= 0f) return emptyList()
        val n = count.coerceAtMost(MultiviewModel.MAX_TILES)
        if (n == 1) return layout1(w, h)
        if (isPortrait(w, h)) return transposed(w, h) { tw, th -> spotlightRects(n, tw, th, s) }
        val bigW = (w - s) * 2 / 3
        val smallW = w - bigW - s
        val smallCount = n - 1
        val smallH = (h - s * (smallCount - 1)) / smallCount
        return buildList {
            add(rect(0f, 0f, bigW, h))
            for (i in 0 until smallCount) add(rect(bigW + s, (smallH + s) * i, smallW, smallH))
        }
    }

    /** Taller than wide. A square counts as landscape, which is also what stops [transposed] recursing. */
    private fun isPortrait(w: Float, h: Float) = h > w

    /**
     * A landscape table built in a swapped area and flipped back, then re-sorted into reading order —
     * transposing walks a grid column-first, and without the sort tile 2 of five would land bottom-left.
     */
    private fun transposed(w: Float, h: Float, build: (Float, Float) -> List<Rect>): List<Rect> =
        build(h, w)
            .map { rect(it.top, it.left, it.height, it.width) }
            .sortedWith { a, b ->
                if (kotlin.math.abs(a.top - b.top) > 1f) a.top.compareTo(b.top) else a.left.compareTo(b.left)
            }

    /** Portrait: the hero shapes (3, 5) turn; everything else is the split that paints the most video. */
    private fun portraitRects(n: Int, w: Float, h: Float, s: Float): List<Rect> = when (n) {
        1 -> layout1(w, h)
        3 -> transposed(w, h) { tw, th -> layout3(tw, th, s) }
        5 -> transposed(w, h) { tw, th -> layout5(tw, th, s) }
        else -> {
            val (cols, rows) = bestFitGrid(n, w, h, s)
            gridRects(cols, rows, n, w, h, s)
        }
    }

    /**
     * The `cols x rows` split whose cells show the most 16:9 picture, letterboxing counted, as
     * (cols, rows). A shape whose last row would be empty is never considered.
     */
    internal fun bestFitGrid(n: Int, w: Float, h: Float, s: Float): Pair<Int, Int> {
        val targetAspect = 16f / 9f
        var best = 1 to maxOf(1, n)
        var bestArea = -1f
        for (rows in 1..maxOf(1, n)) {
            val cols = ceil(n.toDouble() / rows).toInt()
            if (cols * (rows - 1) >= n) continue
            val tw = (w - (cols - 1) * s) / cols
            val th = (h - (rows - 1) * s) / rows
            if (tw <= 0f || th <= 0f) continue
            val videoW = minOf(tw, th * targetAspect)
            val area = videoW * (videoW / targetAspect)
            if (area > bestArea) {
                bestArea = area
                best = cols to rows
            }
        }
        return best
    }

    /** The tile under [pos]; in an empty cell (the 5-tile hole) the tile whose centre is nearest. */
    fun indexAt(pos: Offset, rects: List<Rect>): Int {
        rects.forEachIndexed { i, r -> if (r.contains(pos)) return i }
        var best = 0
        var bestD = Float.MAX_VALUE
        rects.forEachIndexed { i, r ->
            val dx = r.center.x - pos.x
            val dy = r.center.y - pos.y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
