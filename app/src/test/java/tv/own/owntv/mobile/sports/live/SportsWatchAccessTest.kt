package tv.own.owntv.mobile.sports.live

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.mobile.sports.live.SportsPlaylistSelection.Choice

/**
 * Watch-time decisions ([SportsChannelAccess.checkWatch]): the chosen feed is decided again at the
 * moment of Watch — profile, playlist, rules and the game's own list — and the player gets the fresh
 * local row and the game's allowed channels as its zap list, or nothing at all. Synthetic data only.
 */
class SportsWatchAccessTest {

    private val sports = 100L
    private val adult = 200L
    private val playlistA = SourceEntity(id = 7, name = "Synthetic A", type = SourceType.XTREAM, url = "user-entered", password = "synthetic-secret")

    private fun row(id: Long, remoteId: String, name: String, category: Long = sports, sourceId: Long = 7) = ChannelEntity(
        id = id, sourceId = sourceId, name = name, streamUrl = "http://synthetic.invalid/live/user/pass/$remoteId.ts",
        epgChannelId = "fixture.$remoteId", remoteId = remoteId, categoryId = category,
    )

    private val espn = row(1, "900001", "US: ESPN")
    private val espnFhd = row(2, "900002", "US: ESPN FHD")
    private val espn4k = row(3, "900003", "US: ESPN 4K")
    private val adultFeed = row(4, "900004", "XXX: Late Night", category = adult)

    private fun ref(c: ChannelEntity, rank: Int) =
        """{"channelId":"sch_${c.remoteId}","remoteId":"${c.remoteId}","epgChannelId":"${c.epgChannelId}","name":"${c.name}","category":"USA | Sports","logoUrl":null,"networkKey":"espn","confidence":${95 - rank},"reason":"network_exact"}"""

    private fun game(vararg channels: ChannelEntity): SportsEvent =
        SportsFixtures.parsed(SportsFixtures.event("evt_g", status = "LIVE", channels = JSONArray(channels.mapIndexed { i, c -> JSONObject(ref(c, i)) })))

    private class Profile(val id: Long, var playlist: Choice, var rules: SportsVisibilityRules, val adultBlocked: MutableSet<Long> = mutableSetOf())

    private inner class World : SportsAccessData {
        val profiles = mutableMapOf<Long, Profile>()
        var active = 1L
        val rows = mutableListOf(espn, espnFhd, espn4k, adultFeed)
        override suspend fun snapshot(): SportsAccessSnapshot? = profiles[active]?.let { SportsAccessSnapshot(it.id, it.playlist, it.rules) }
        override suspend fun activeProfileId() = active
        override suspend fun findByRemote(sourceId: Long, remoteId: String) = rows.firstOrNull { it.sourceId == sourceId && it.remoteId == remoteId }
        override suspend fun adultAllowed(profileId: Long, categoryId: Long?) = categoryId !in profiles.getValue(profileId).adultBlocked
    }

    private val open = SportsVisibilityRules(emptySet(), emptySet())
    private val adultProfile = Profile(1, Choice.Selected(playlistA), open)
    private val kidsProfile = Profile(2, Choice.Selected(playlistA), SportsVisibilityRules(emptySet(), setOf(adult)), adultBlocked = mutableSetOf(adult))
    private val world = World().apply { profiles[1] = adultProfile; profiles[2] = kidsProfile }
    private val access = SportsChannelAccess(world)

    private suspend fun offered(event: SportsEvent) = access.offer(event).channels
    private fun allowed(check: SportsWatchCheck) = check as SportsWatchCheck.Allowed

    @Test
    fun oneEligibleChannel_playsThatChannel_withItAloneAsTheZapList() = runBlocking {
        val g = game(espn)
        val chosen = offered(g).single()
        val ok = allowed(access.checkWatch(g, chosen, expectedProfileId = 1))
        assertEquals(espn.id, ok.channel.id)
        assertEquals(listOf(espn.id), ok.zapChannels.map { it.id })
    }

    @Test
    fun severalChannels_theChosenOnePlays_andTheZapListKeepsBackendRank() = runBlocking {
        val g = game(espn4k, espn, espnFhd) // the backend's ranking, not alphabetical
        val shown = offered(g)
        assertEquals(listOf("US: ESPN 4K", "US: ESPN", "US: ESPN FHD"), shown.map { it.name })
        assertEquals(listOf(true, false, false), shown.map { it.uhd })
        val ok = allowed(access.checkWatch(g, shown[2], 1))
        assertEquals(espnFhd.id, ok.channel.id)
        assertEquals(listOf(espn4k.id, espn.id, espnFhd.id), ok.zapChannels.map { it.id })
    }

    @Test
    fun aChannelHiddenSinceTheListWasShown_isRefused() = runBlocking {
        val g = game(espn, espnFhd)
        val chosen = offered(g)[1]
        adultProfile.rules = SportsVisibilityRules(setOf(CustomizeKeys.channel(espnFhd)), emptySet())
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.NO_LONGER_AVAILABLE), access.checkWatch(g, chosen, 1))
    }

    @Test
    fun aHiddenChannel_isAlsoLeftOutOfTheZapList() = runBlocking {
        val g = game(espn, espnFhd, espn4k)
        val chosen = offered(g)[0]
        adultProfile.rules = SportsVisibilityRules(setOf(CustomizeKeys.channel(espn4k)), emptySet())
        assertEquals(listOf(espn.id, espnFhd.id), allowed(access.checkWatch(g, chosen, 1)).zapChannels.map { it.id })
    }

    @Test
    fun anAdultChannel_isRefusedOnAKidsProfile_evenIfTheListRuleMissedIt() = runBlocking {
        val g = game(adultFeed, espn)
        val chosen = offered(g).first { it.name == "XXX: Late Night" } // offered to the adult profile
        world.active = 2
        kidsProfile.rules = open // the list rule misses it; the play-time check must not
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.PROFILE_CHANGED), access.checkWatch(g, chosen, 1))
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.NO_LONGER_AVAILABLE), access.checkWatch(g, chosen, 2))
    }

    @Test
    fun aProfileSwitchAfterTheListWasShown_refusesTheWatch_evenWhereTheNewProfileWouldAllowIt() = runBlocking {
        val g = game(espn)
        val chosen = offered(g).single()
        world.active = 2
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.PROFILE_CHANGED), access.checkWatch(g, chosen, expectedProfileId = 1))
    }

    @Test
    fun aDeletedPlaylist_refusesTheWatch() = runBlocking {
        val g = game(espn)
        val chosen = offered(g).single()
        adultProfile.playlist = Choice.None
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.NO_ELIGIBLE_PLAYLIST), access.checkWatch(g, chosen, 1))
    }

    @Test
    fun severalXtreamPlaylistsWithoutADefault_refusesTheWatch() = runBlocking {
        val g = game(espn)
        val chosen = offered(g).single()
        adultProfile.playlist = Choice.Ambiguous(eligibleCount = 2)
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.SEVERAL_PLAYLISTS), access.checkWatch(g, chosen, 1))
    }

    @Test
    fun aPlaylistRefresh_playsTheFreshLocalRow_notTheOneShown() = runBlocking {
        val g = game(espn)
        val chosen = offered(g).single()
        world.rows.remove(espn)
        world.rows += espn.copy(id = 501) // the same feed, re-imported under a new local id
        assertEquals(501L, allowed(access.checkWatch(g, chosen, 1)).channel.id)
    }

    @Test
    fun aChannelGoneFromThePlaylist_isRefused_andNothingIsSubstituted() = runBlocking {
        val g = game(espn, espnFhd)
        val chosen = offered(g)[1]
        world.rows.remove(espnFhd)
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.NO_LONGER_AVAILABLE), access.checkWatch(g, chosen, 1))
    }

    @Test
    fun aChannelTheGameNoLongerLists_isRefused() = runBlocking {
        val chosen = offered(game(espn, espnFhd))[1]
        val updated = game(espn) // the backend dropped the FHD feed for this game
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.NO_LONGER_AVAILABLE), access.checkWatch(updated, chosen, 1))
    }

    @Test
    fun revalidate_answersTheSameAsWatch() = runBlocking {
        val g = game(espn, espnFhd)
        val chosen = offered(g)[1]
        assertEquals(espnFhd.id, access.revalidate(g, chosen)?.id)
        world.rows.remove(espnFhd)
        assertNull(access.revalidate(g, chosen))
    }

    @Test
    fun nothingSensitive_isInWhatTheScreenShowsOrLogs() = runBlocking {
        val g = game(espn, espn4k)
        val shown = offered(g)
        val ok = allowed(access.checkWatch(g, shown[0], 1))
        val printed = shown.joinToString() + ok.toString()
        for (secret in listOf("synthetic.invalid", "pass", "900001", "900003", "fixture.", "sch_", "network_exact")) {
            assertFalse("'$secret' must not appear in: $printed", printed.contains(secret))
        }
        assertTrue(printed.contains("US: ESPN"))
    }
}
