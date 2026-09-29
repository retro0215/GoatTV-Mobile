package tv.own.owntv.mobile.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewContent
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewModel

/**
 * The grid's rules: five tiles at most, a permanent player slot per tile, one tile heard, and
 * reordering that moves pictures without ever replacing the player behind them.
 */
class MultiviewModelTest {

    private fun live(id: Long) = MultiviewContent.Live(
        ChannelEntity(id = id, sourceId = 1, name = "Channel $id", streamUrl = "http://example.invalid/$id"),
    )

    private fun gridOf(vararg ids: Long): MultiviewModel =
        ids.fold(MultiviewModel()) { m, id -> m.add(live(id)).model }

    private val MultiviewModel.slots get() = tiles.map { it.slot }
    private val MultiviewModel.ids get() = tiles.map { (it.content as MultiviewContent.Live).channel.id }

    @Test
    fun maxTilesIsFive() {
        assertEquals(5, MultiviewModel.MAX_TILES)
    }

    @Test
    fun addsOneThroughFive_andRefusesTheSixth() {
        var model = MultiviewModel()
        for (id in 1L..5L) {
            val result = model.add(live(id))
            assertEquals(id.toInt() - 1, result.slot)
            model = result.model
            assertEquals(id.toInt(), model.size)
        }
        assertTrue(model.isFull)
        val sixth = model.add(live(6))
        assertNull(sixth.slot)
        assertSame(model, sixth.model)
    }

    @Test
    fun theFirstTileTakesTheSound_laterOnesDoNot() {
        val model = gridOf(10, 11, 12)
        assertEquals(model.tiles.first().slot, model.audioSlot)
    }

    @Test
    fun aChannelIsNeverInTwoTiles() {
        val model = gridOf(10, 11)
        val again = model.add(live(10))
        assertSame(model, again.model)
        assertEquals(model.slotOf(live(10).key), again.slot)
    }

    @Test
    fun slotsArePermanent_andAFreedSlotIsReused() {
        val model = gridOf(1, 2, 3, 4, 5).remove(2)
        assertEquals(listOf(0, 1, 3, 4), model.slots)
        val refilled = model.add(live(9))
        assertEquals(2, refilled.slot)
        assertEquals(listOf(0, 1, 3, 4, 2), refilled.model.slots)
    }

    @Test
    fun dragReorderChangesOrderOnly_neverTheSlot() {
        val before = gridOf(1, 2, 3, 4, 5)
        val slotOfChannel = before.tiles.associate { it.content.key to it.slot }
        val after = before.move(fromIndex = 0, toIndex = 3)
        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), after.ids)
        // Every channel still owns the player slot it started on.
        after.tiles.forEach { assertEquals(slotOfChannel[it.content.key], it.slot) }
        assertEquals(before.slots.toSet(), after.slots.toSet())
    }

    @Test
    fun repeatedDragsNeverReplaceAPlayer() {
        var model = gridOf(1, 2, 3, 4, 5)
        val slotOfChannel = model.tiles.associate { it.content.key to it.slot }
        listOf(0 to 4, 4 to 0, 2 to 1, 3 to 0, 1 to 4, 0 to 2).forEach { (from, to) -> model = model.move(from, to) }
        model.tiles.forEach { assertEquals(slotOfChannel[it.content.key], it.slot) }
    }

    @Test
    fun theSoundFollowsItsTileThroughAMove() {
        val model = gridOf(1, 2, 3).withAudio(1)
        val moved = model.move(1, 2)
        assertEquals(1, moved.audioSlot)
        assertEquals(2, moved.displayIndexOf(1))
    }

    @Test
    fun swapTradesPlaces_slotsUnchanged() {
        val swapped = gridOf(1, 2, 3).swap(0, 2)
        assertEquals(listOf(3L, 2L, 1L), swapped.ids)
        assertEquals(listOf(2, 1, 0), swapped.slots)
    }

    @Test
    fun exactlyOneTileHasTheSound_andItHandsOff() {
        var model = gridOf(1, 2, 3, 4, 5)
        model = model.withAudio(1)
        assertEquals(1, model.audioSlot)
        model = model.withAudio(4)
        assertEquals(4, model.audioSlot)
        // A slot with no tile cannot take it.
        assertEquals(4, model.remove(3).withAudio(3).audioSlot)
    }

    @Test
    fun removingTheAudibleTileMovesTheSoundToTheLastTileShown() {
        val model = gridOf(1, 2, 3, 4).withAudio(0).remove(0)
        assertEquals(model.tiles.last().slot, model.audioSlot)
    }

    @Test
    fun removingAnotherTileKeepsTheSoundWhereItIs() {
        assertEquals(2, gridOf(1, 2, 3).withAudio(2).remove(0).audioSlot)
    }

    @Test
    fun removingTheLastTileLeavesAnEmptySilentGrid() {
        val model = gridOf(1).remove(0)
        assertTrue(model.isEmpty)
        assertNull(model.audioSlot)
    }

    @Test
    fun replaceKeepsTheSlotAndThePlace() {
        val model = gridOf(1, 2, 3)
        val result = model.replace(1, live(9))
        assertEquals(1, result.slot)
        assertEquals(listOf(1L, 9L, 3L), result.model.ids)
        assertEquals(listOf(0, 1, 2), result.model.slots)
    }

    @Test
    fun replaceWithAChannelAnotherTileShowsIsRefused() {
        val model = gridOf(1, 2, 3)
        assertNull(model.replace(1, live(3)).slot)
    }

    @Test
    fun replaceWithWhatItAlreadyShowsIsANoOpSuccess() {
        val model = gridOf(1, 2)
        val result = model.replace(1, live(2))
        assertEquals(1, result.slot)
        assertSame(model, result.model)
    }

    @Test
    fun seedingPutsTheWatchedChannelFirstWithTheSound() {
        val result = gridOf(1, 2).seedFront(live(7))
        assertEquals(listOf(7L, 1L, 2L), result.model.ids)
        assertEquals(result.slot, result.model.audioSlot)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun seedingAChannelAlreadyThereMovesItFirst_withoutAnewSlot() {
        val model = gridOf(1, 2, 3)
        val result = model.seedFront(live(3))
        assertEquals(listOf(3L, 1L, 2L), result.model.ids)
        assertEquals(model.slotOf(live(3).key), result.slot)
    }

    @Test
    fun seedingAFullGridDropsTheLastTile() {
        val result = gridOf(1, 2, 3, 4, 5).seedFront(live(9))
        assertEquals(5, result.model.size)
        assertEquals(listOf(9L, 1L, 2L, 3L, 4L), result.model.ids)
        assertEquals(listOf(4), result.dropped)
        assertEquals(4, result.slot)
    }

    @Test
    fun spotlightOnAndOff_withoutTouchingOrderOrSound() {
        val model = gridOf(1, 2, 3).withAudio(0)
        val lit = model.withSpotlight(2)
        assertEquals(2, lit.spotlightSlot)
        assertEquals(model.tiles, lit.tiles)
        assertEquals(0, lit.audioSlot)
        val off = lit.withSpotlight(null)
        assertNull(off.spotlightSlot)
        assertEquals(model.tiles, off.tiles)
    }

    @Test
    fun spotlightNeedsASecondTile() {
        assertNull(gridOf(1).withSpotlight(0).spotlightSlot)
    }

    @Test
    fun spotlightAndGridFullscreenDoNotCombine() {
        val full = gridOf(1, 2, 3).withGridFullscreen(1)
        assertEquals(1, full.gridFullscreenSlot)
        assertEquals(1, full.audioSlot)
        val lit = full.withSpotlight(2)
        assertNull(lit.gridFullscreenSlot)
    }

    @Test
    fun removingTheSpotlitTileEndsSpotlight() {
        assertNull(gridOf(1, 2, 3).withSpotlight(1).remove(1).spotlightSlot)
    }

    @Test
    fun memoryPressureKeepsOnlyTheAudibleTile() {
        val result = gridOf(1, 2, 3, 4, 5).withAudio(3).keepOnlyAudible()
        assertEquals(listOf(3), result.model.slots)
        assertEquals(3, result.model.audioSlot)
        assertEquals(listOf(0, 1, 2, 4), result.dropped.sorted())
    }

    @Test
    fun orderedLikePutsAGridBackAsItWas() {
        val snapshot = gridOf(1, 2, 3).withAudio(2).withSpotlight(1)
        val shuffled = snapshot.move(0, 2).withAudio(0).withSpotlight(null)
        val restored = shuffled.orderedLike(snapshot)
        assertEquals(snapshot.ids, restored.ids)
        assertEquals(2, restored.audioSlot)
        assertEquals(1, restored.spotlightSlot)
    }

    @Test
    fun clearedGridIsEmptyWithNothingHeard() {
        val cleared = MultiviewModel()
        assertTrue(cleared.isEmpty)
        assertNull(cleared.audioSlot)
        assertNull(cleared.spotlightSlot)
        assertFalse(cleared.isFull)
    }
}
