package tv.own.owntv.mobile.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewContent
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewModel
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewTileHold
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsMultiviewAdd

/**
 * Seen on the Samsung with 1.0.10: a still long press on a tile opened no menu (and moved the sound
 * instead), so a tile could not be removed. A hold now always ends in the menu or a move.
 */
class MultiviewTileHoldTest {

    private val slop = 16f

    @Test
    fun aStillHold_opensTheMenu() {
        val hold = MultiviewTileHold(slop)
        assertEquals("no movement at all — the case that failed on the device", MultiviewTileHold.Release.MENU, hold.release())
    }

    @Test
    fun aRestingFingersJitter_isStillTheMenu() {
        val hold = MultiviewTileHold(slop)
        repeat(5) { assertFalse(hold.move(2f)) }
        assertFalse(hold.moving)
        assertEquals(MultiviewTileHold.Release.MENU, hold.release())
    }

    @Test
    fun carriedPastTheSlop_isAMove_announcedOnce() {
        val hold = MultiviewTileHold(slop)
        assertFalse(hold.move(10f))
        assertTrue("crossing the slop starts the move", hold.move(10f))
        assertFalse("and only once", hold.move(40f))
        assertTrue(hold.moving)
        assertEquals(MultiviewTileHold.Release.DROP, hold.release())
    }

    // ------------------------------------------------------------------ removing, then adding again

    private fun live(id: Long, name: String = "Channel $id") =
        MultiviewContent.Live(ChannelEntity(id = id, sourceId = 1, name = name, streamUrl = "http://example.invalid/$id"))

    @Test
    fun removingASportsAddedTile_removesThatTileOnly_andMovesTheSound() {
        var grid = MultiviewModel()
        listOf(live(1, "US: ESPN FHD"), live(2, "US: ESPN 4K"), live(3, "NBA 02")).forEach { grid = grid.add(it).model }
        val espnSlot = grid.slotOf(live(1).key)!!
        assertEquals("the first tile has the sound", espnSlot, grid.audioSlot)
        val after = grid.remove(espnSlot)
        assertEquals(listOf(2L, 3L), after.tiles.map { (it.content as MultiviewContent.Live).channel.id })
        assertNull(after.slotOf(live(1).key))
        assertTrue("the sound moves to a tile still there", after.tiles.any { it.slot == after.audioSlot })
        assertEquals("the others keep their own slots (their players)", grid.slotOf(live(2).key), after.slotOf(live(2).key))
    }

    @Test
    fun aRemovedChannel_canBeAddedAgain_fromTheSportsPanel() {
        var grid = MultiviewModel().add(live(1)).model
        assertEquals(SportsMultiviewAdd.ALREADY_THERE, SportsMultiviewAdd.decide(grid.contains(live(1).key), grid.isFull) { false })
        grid = grid.remove(grid.slotOf(live(1).key)!!)
        assertEquals("the panel's button reads Add again", SportsMultiviewAdd.ADD, SportsMultiviewAdd.decide(grid.contains(live(1).key), grid.isFull) { false })
        assertEquals(1, grid.add(live(1)).model.size)
    }

    @Test
    fun aFullGrid_hasRoomAgainAfterARemoval() {
        var grid = MultiviewModel()
        (1L..MultiviewModel.MAX_TILES).forEach { grid = grid.add(live(it)).model }
        assertTrue(grid.isFull)
        grid = grid.remove(grid.tiles.first().slot)
        assertFalse(grid.isFull)
        assertEquals(SportsMultiviewAdd.ADD, SportsMultiviewAdd.decide(grid.contains(live(99).key), grid.isFull) { false })
    }
}
