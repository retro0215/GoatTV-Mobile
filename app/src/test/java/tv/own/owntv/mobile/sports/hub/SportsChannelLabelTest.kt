package tv.own.owntv.mobile.sports.hub

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.mobile.sports.live.SportsAccessData
import tv.own.owntv.mobile.sports.live.SportsAccessSnapshot
import tv.own.owntv.mobile.sports.live.SportsChannelAccess
import tv.own.owntv.mobile.sports.live.SportsFixtures
import tv.own.owntv.mobile.sports.live.SportsPlaylistSelection
import tv.own.owntv.mobile.sports.live.SportsVisibilityRules
import tv.own.owntv.mobile.sports.live.VisibleSportsChannel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsChannelLabel

/**
 * Seen on the Samsung with 1.0.10: two "US: ESPN" feeds, both "USA | Sports ⚽", read exactly alike.
 * Such twins now show the playlist's own channel number; nothing is ever made up.
 */
class SportsChannelLabelTest {

    private fun ch(id: Long, name: String, category: String?, number: Int?, uhd: Boolean = false) =
        VisibleSportsChannel(id, name, null, category, uhd, number = number, backendChannelId = "sch_$id")

    @Test
    fun twinsWithDifferentNumbers_showTheirNumbers() {
        val labels = SportsChannelLabel.forRows(
            listOf(ch(1, "US: ESPN", "USA | Sports", 10657), ch(2, "US: ESPN", "USA | Sports", 10606), ch(3, "US: ESPN FHD", "USA | Sports", 10602)),
        )
        assertEquals(listOf(10657, 10606, null), labels.map { it.number })
        assertEquals("a channel without a twin shows no number", null, labels[2].number)
    }

    @Test
    fun sameNameButDifferentCategory_needsNoNumber() {
        val labels = SportsChannelLabel.forRows(listOf(ch(1, "US: ESPN", "USA | Sports", 1), ch(2, "US: ESPN", "USA | Sports Backup", 2)))
        assertEquals(listOf(null, null), labels.map { it.number })
    }

    @Test
    fun noNumberInThePlaylist_noNumberShown_neverInvented() {
        val labels = SportsChannelLabel.forRows(listOf(ch(1, "US: ESPN", "USA | Sports", null), ch(2, "US: ESPN", "USA | Sports", 10606)))
        assertNull(labels[0].number)
        assertEquals(10606, labels[1].number)
    }

    @Test
    fun theSameNumberOnBothTwins_wouldNotHelp_soItIsLeftOut() {
        val labels = SportsChannelLabel.forRows(listOf(ch(1, "US: ESPN", "USA | Sports", 500), ch(2, "US: ESPN", "USA | Sports", 500)))
        assertEquals(listOf(null, null), labels.map { it.number })
    }

    @Test
    fun twinsAreFoundRegardlessOfLetterCase_andRankAnd4kAreKept() {
        val labels = SportsChannelLabel.forRows(
            listOf(ch(1, "US: ESPN 4K", "EN | 4K UHD", 32883, uhd = true), ch(2, "us: espn", "usa | sports", 7), ch(3, "US: ESPN", "USA | Sports", 8)),
        )
        assertEquals(listOf("US: ESPN 4K", "us: espn", "US: ESPN"), labels.map { it.name })
        assertEquals(listOf(null, 7, 8), labels.map { it.number })
        assertEquals(listOf(true, false, false), labels.map { it.uhd })
    }

    // ------------------------------------------------------------------ the number is the local row's

    private val playlist = SourceEntity(id = 7, name = "Synthetic", type = SourceType.XTREAM, url = "user-entered")
    private fun row(id: Long, remoteId: String, number: Int?) = ChannelEntity(
        id = id, sourceId = 7, name = "US: ESPN", streamUrl = "http://synthetic.invalid/user/pass/$remoteId.ts",
        epgChannelId = "fixture.$remoteId", remoteId = remoteId, categoryId = 1, number = number,
    )

    @Test
    fun theNumberComesFromThePlaylistRow_notTheBackend_andNothingSensitiveIsShown() = runBlocking {
        val rows = listOf(row(1, "900001", 10657), row(2, "900002", 10606))
        val access = SportsChannelAccess(object : SportsAccessData {
            override suspend fun snapshot() = SportsAccessSnapshot(1, SportsPlaylistSelection.Choice.Selected(playlist), SportsVisibilityRules(emptySet(), emptySet()))
            override suspend fun activeProfileId() = 1L
            override suspend fun findByRemote(sourceId: Long, remoteId: String) = rows.firstOrNull { it.remoteId == remoteId }
            override suspend fun adultAllowed(profileId: Long, categoryId: Long?) = true
        })
        val game = SportsFixtures.parsed(
            SportsFixtures.event(
                "evt_g", status = "LIVE",
                channels = JSONArray(
                    rows.map {
                        JSONObject("""{"channelId":"sch_${it.remoteId}","remoteId":"${it.remoteId}","epgChannelId":"${it.epgChannelId}","name":"US: ESPN","category":"USA | Sports","logoUrl":null,"networkKey":"espn","confidence":95,"reason":"network_exact"}""")
                    },
                ),
            ),
        )
        val labels = SportsChannelLabel.forRows(access.offer(game).channels)
        assertEquals(listOf(10657, 10606), labels.map { it.number })
        val printed = labels.joinToString()
        for (secret in listOf("900001", "900002", "fixture.", "synthetic.invalid", "pass", "sch_")) assertFalse(printed.contains(secret))
    }
}
