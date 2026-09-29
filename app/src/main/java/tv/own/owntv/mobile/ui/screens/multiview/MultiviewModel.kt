package tv.own.owntv.mobile.ui.screens.multiview

import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * What a tile shows. Live channels only for now; films, episodes and recordings slot in here later
 * as further cases, each with its own [key].
 */
sealed interface MultiviewContent {
    /** Identity for duplicate checks: the same thing can never be in two tiles. */
    val key: String
    val title: String

    data class Live(val channel: ChannelEntity) : MultiviewContent {
        override val key: String get() = "live:${channel.id}"
        override val title: String get() = channel.name
    }
}

/**
 * One tile: which player [slot] it owns, and what it shows.
 *
 * The slot is the tile's player for its whole life. Where the tile sits on screen is only its index
 * in [MultiviewModel.tiles], so reordering moves a picture without touching its stream.
 */
data class MultiviewTile(val slot: Int, val content: MultiviewContent)

/**
 * The grid's state and every rule for changing it — pure, so each rule is testable on its own.
 *
 * AerioTV's `MultiviewStore` semantics, with one structural change: tiles own a permanent player
 * slot (0 until [MAX_TILES]) instead of being positional, so drag and swap change display order only.
 * Audio, Spotlight and in-grid full screen are all held by slot, so they follow a tile wherever it
 * is moved.
 */
data class MultiviewModel(
    /** Display order: index 0 is the first tile drawn. */
    val tiles: List<MultiviewTile> = emptyList(),
    /** The one tile with sound; null only while the grid is empty. */
    val audioSlot: Int? = null,
    /** The tile given the big rect, or null. */
    val spotlightSlot: Int? = null,
    /** The tile filling the grid in full-screen-in-grid, or null. */
    val gridFullscreenSlot: Int? = null,
) {
    val size: Int get() = tiles.size
    val isEmpty: Boolean get() = tiles.isEmpty()
    val isFull: Boolean get() = tiles.size >= MAX_TILES

    fun contains(key: String): Boolean = tiles.any { it.content.key == key }
    fun slotOf(key: String): Int? = tiles.firstOrNull { it.content.key == key }?.slot
    fun tileIn(slot: Int): MultiviewTile? = tiles.firstOrNull { it.slot == slot }
    fun displayIndexOf(slot: Int): Int = tiles.indexOfFirst { it.slot == slot }

    /** The lowest slot no tile owns, or null when all [MAX_TILES] are taken. */
    fun freeSlot(): Int? = (0 until MAX_TILES).firstOrNull { s -> tiles.none { it.slot == s } }

    /**
     * Add [content] at the end. Already present is success and changes nothing — the same channel is
     * never in two tiles. Refused only at [MAX_TILES]. The first tile of a grid takes the sound.
     */
    fun add(content: MultiviewContent): Result {
        slotOf(content.key)?.let { return Result(this, it) }
        val slot = freeSlot() ?: return Result(this, null)
        val tiles = tiles + MultiviewTile(slot, content)
        return Result(copy(tiles = tiles, audioSlot = audioSlot ?: slot), slot)
    }

    /**
     * The channel being watched becomes the first tile with the sound, keeping every tile already
     * there behind it (AerioTV's `seedCurrent`). At [MAX_TILES] the last tile gives way — its slot
     * is in [Result.dropped] so its player can be freed.
     */
    fun seedFront(content: MultiviewContent): Result {
        val existing = tiles.firstOrNull { it.content.key == content.key }
        if (existing != null) {
            val moved = listOf(existing) + (tiles - existing)
            return Result(copy(tiles = moved, audioSlot = existing.slot), existing.slot)
        }
        var kept = tiles
        val dropped = mutableListOf<Int>()
        if (kept.size >= MAX_TILES) {
            dropped += kept.last().slot
            kept = kept.dropLast(1)
        }
        val slot = (0 until MAX_TILES).first { s -> kept.none { it.slot == s } }
        val next = MultiviewModel(
            tiles = listOf(MultiviewTile(slot, content)) + kept,
            audioSlot = slot,
            spotlightSlot = spotlightSlot?.takeIf { s -> kept.any { it.slot == s } },
            gridFullscreenSlot = gridFullscreenSlot?.takeIf { s -> kept.any { it.slot == s } },
        )
        return Result(next, slot, dropped)
    }

    /**
     * Take a tile out. When it had the sound, the sound moves to the last tile on screen (AerioTV:
     * the newest remaining one), so the grid is never silent while it still shows something.
     */
    fun remove(slot: Int): MultiviewModel {
        if (tiles.none { it.slot == slot }) return this
        val remaining = tiles.filterNot { it.slot == slot }
        return MultiviewModel(
            tiles = remaining,
            audioSlot = when {
                remaining.isEmpty() -> null
                audioSlot == slot -> remaining.last().slot
                else -> audioSlot
            },
            spotlightSlot = spotlightSlot.takeUnless { it == slot },
            gridFullscreenSlot = gridFullscreenSlot.takeUnless { it == slot },
        )
    }

    /**
     * Swap Stream: the tile keeps its slot and its place, and shows [content] instead. Picking what it
     * already shows is a no-op success; picking something another tile shows fails.
     */
    fun replace(slot: Int, content: MultiviewContent): Result {
        val index = displayIndexOf(slot)
        if (index < 0) return Result(this, null)
        if (tiles[index].content.key == content.key) return Result(this, slot)
        if (contains(content.key)) return Result(this, null)
        return Result(copy(tiles = tiles.toMutableList().also { it[index] = MultiviewTile(slot, content) }), slot)
    }

    /** Drag-to-reorder: insert, not swap — the tiles in between shift. Slots do not change. */
    fun move(fromIndex: Int, toIndex: Int): MultiviewModel {
        if (fromIndex !in tiles.indices || toIndex !in tiles.indices || fromIndex == toIndex) return this
        val list = tiles.toMutableList()
        list.add(toIndex, list.removeAt(fromIndex))
        return copy(tiles = list)
    }

    /** Move Tile mode: two tiles trade places. Slots do not change. */
    fun swap(a: Int, b: Int): MultiviewModel {
        if (a !in tiles.indices || b !in tiles.indices || a == b) return this
        val list = tiles.toMutableList()
        list[a] = tiles[b]
        list[b] = tiles[a]
        return copy(tiles = list)
    }

    fun withAudio(slot: Int): MultiviewModel =
        if (tiles.any { it.slot == slot }) copy(audioSlot = slot) else this

    /** Spotlight on [slot], or off with null. Full-screen-in-grid ends: the two do not combine. */
    fun withSpotlight(slot: Int?): MultiviewModel = when {
        slot == null -> copy(spotlightSlot = null)
        tiles.any { it.slot == slot } && size > 1 -> copy(spotlightSlot = slot, gridFullscreenSlot = null)
        else -> this
    }

    /** Full-screen-in-grid on [slot] (the sound goes with it), or off with null. */
    fun withGridFullscreen(slot: Int?): MultiviewModel = when {
        slot == null -> copy(gridFullscreenSlot = null)
        tiles.any { it.slot == slot } && size > 1 -> copy(gridFullscreenSlot = slot, audioSlot = slot)
        else -> this
    }

    /**
     * The same tiles in [reference]'s order, with its sound, Spotlight and full-screen tile where
     * those tiles are still here. Tiles [reference] does not know keep their place at the end.
     */
    fun orderedLike(reference: MultiviewModel): MultiviewModel {
        val rank = reference.tiles.withIndex().associate { (i, t) -> t.content.key to i }
        val ordered = tiles.sortedBy { rank[it.content.key] ?: Int.MAX_VALUE }
        fun slotFor(refSlot: Int?): Int? =
            refSlot?.let { reference.tileIn(it) }?.content?.key?.let { key -> ordered.firstOrNull { it.content.key == key }?.slot }
        return MultiviewModel(
            tiles = ordered,
            audioSlot = slotFor(reference.audioSlot) ?: audioSlot?.takeIf { s -> ordered.any { it.slot == s } } ?: ordered.firstOrNull()?.slot,
            spotlightSlot = slotFor(reference.spotlightSlot),
            gridFullscreenSlot = slotFor(reference.gridFullscreenSlot),
        )
    }

    /** Critical memory pressure: keep only the tile with the sound. Returns the model and freed slots. */
    fun keepOnlyAudible(): Result {
        if (size <= 1) return Result(this, audioSlot)
        val keep = tileIn(audioSlot ?: tiles.first().slot) ?: tiles.first()
        val dropped = tiles.filterNot { it.slot == keep.slot }.map { it.slot }
        return Result(MultiviewModel(tiles = listOf(keep), audioSlot = keep.slot), keep.slot, dropped)
    }

    /** A new model, the slot the operation concerned (null when refused), and any slots it freed. */
    data class Result(val model: MultiviewModel, val slot: Int?, val dropped: List<Int> = emptyList())

    companion object {
        /** The most tiles a grid can hold. */
        const val MAX_TILES = 5

        /** Adding past this many asks first (AerioTV's soft limit): here, that is before the fifth. */
        const val SOFT_LIMIT = 4
    }
}
