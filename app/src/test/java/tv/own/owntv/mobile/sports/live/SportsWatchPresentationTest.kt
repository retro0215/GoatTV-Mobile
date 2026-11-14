package tv.own.owntv.mobile.sports.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where to Watch rows (TV app v4.6.1 `SportsWatchPresentationTest`, rows and UHD parts), from visible channels. */
class SportsWatchPresentationTest {

    private val event = SportsFixtures.parsed(
        SportsFixtures.event(
            "evt_1",
            status = "LIVE",
            broadcasts = listOf(SportsFixtures.broadcast("ESPN", key = "espn"), SportsFixtures.broadcast("FOX", key = "fox")),
        ),
    )

    private fun visible(localId: Long, name: String, network: String?, uhd: Boolean = false, category: String? = "USA | Sports") =
        VisibleSportsChannel(localId, name, network, category, uhd, backendChannelId = "sch_$localId")

    @Test
    fun rowsKeepRank_primaryBefore4k_andExposeNoImplementationDetails() {
        val rows = SportsWatchPresentation.rows(
            event,
            listOf(visible(1, "US: ESPN", "espn"), visible(2, "US: ESPN 4K", "espn", uhd = true), visible(3, "US: FOX", "fox", category = null)),
            selectedChannelId = null,
        )
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.localChannelId })
        assertEquals(listOf("US: ESPN", "US: ESPN 4K", "US: FOX"), rows.map { it.name })
        assertEquals(listOf("ESPN", "ESPN", "FOX"), rows.map { it.network })
        assertEquals(listOf(false, true, false), rows.map { it.uhd })
        assertNull(rows[2].category)
        assertFalse("the backend id never reaches a row", rows.joinToString().contains("sch_"))
    }

    @Test
    fun theSelectedFeedIsMarked_anUnknownNetworkIsNull() {
        val rows = SportsWatchPresentation.rows(event, listOf(visible(1, "US: ESPN", "espn"), visible(2, "Other", "nbc")), selectedChannelId = 2)
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
