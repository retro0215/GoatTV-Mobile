package tv.own.owntv.mobile.sports.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/** Where to Watch rows (TV app v4.6.1 `SportsWatchPresentationTest`, rows and UHD parts). */
class SportsWatchPresentationTest {

    private val event = SportsFixtures.parsed(
        SportsFixtures.event(
            "evt_1",
            status = "LIVE",
            broadcasts = listOf(SportsFixtures.broadcast("ESPN", key = "espn"), SportsFixtures.broadcast("FOX", key = "fox")),
        ),
    )

    private fun resolved(localId: Long, localName: String, refName: String, network: String?, category: String? = "USA | Sports") =
        ResolvedSportsChannel(
            SportsChannelRef("sch_$localId", "9$localId", "EPG.$localId", refName, category, null, network, 95, "network_exact"),
            ChannelEntity(id = localId, sourceId = 7, name = localName, streamUrl = "built-locally", remoteId = "9$localId"),
        )

    @Test
    fun rowsKeepBackendRank_primaryBefore4k_andExposeNoImplementationDetails() {
        val rows = SportsWatchPresentation.rows(
            event,
            listOf(resolved(1, "US: ESPN", "US: ESPN", "espn"), resolved(2, "US: ESPN 4K", "ESPN 4K UHD", "espn"), resolved(3, "US: FOX", "US: FOX", "fox", category = " ")),
            selectedChannelId = null,
        )
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.localChannelId })
        assertEquals(listOf("US: ESPN", "US: ESPN 4K", "US: FOX"), rows.map { it.name })
        assertEquals(listOf("ESPN", "ESPN", "FOX"), rows.map { it.network })
        assertEquals(listOf(false, true, false), rows.map { it.uhd })
        assertNull("a blank category is dropped", rows[2].category)
        val shown = rows.joinToString { it.toString() }
        for (internal in listOf("sch_", "EPG.", "network_exact", "95", "91")) assertFalse(internal, shown.contains("=$internal"))
    }

    @Test
    fun theSelectedFeedIsMarked_anUnknownNetworkIsNull() {
        val rows = SportsWatchPresentation.rows(event, listOf(resolved(1, "US: ESPN", "US: ESPN", "espn"), resolved(2, "Other", "Other", "nbc")), selectedChannelId = 2)
        assertEquals(listOf(false, true), rows.map { it.selected })
        assertNull(rows[1].network)
    }

    @Test
    fun uhdDetection() {
        assertTrue(SportsWatchPresentation.isUhd("ESPN 4K"))
        assertTrue(SportsWatchPresentation.isUhd("US: FOX UHD"))
        assertTrue(SportsWatchPresentation.isUhd("NBC 2160p"))
        assertFalse(SportsWatchPresentation.isUhd("ESPN FHD"))
        assertFalse(SportsWatchPresentation.isUhd("4KIDS TV"))
    }
}
