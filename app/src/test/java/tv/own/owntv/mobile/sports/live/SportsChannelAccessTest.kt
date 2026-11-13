package tv.own.owntv.mobile.sports.live

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.mobile.sports.live.SportsPlaylistSelection.Choice

/**
 * Channel visibility on top of identity: the active profile's Live TV rules decide what a game offers,
 * and [SportsChannelAccess.revalidate] decides again at the moment of acting. Synthetic data only.
 */
class SportsChannelAccessTest {

    // ------------------------------------------------------------------ synthetic world

    private val sportsCategory = 100L
    private val adultCategory = 200L
    private val hiddenCategory = 300L

    private val playlistA = SourceEntity(id = 7, name = "Synthetic A", type = SourceType.XTREAM, url = "user-entered", password = "synthetic-secret")
    private val playlistB = SourceEntity(id = 9, name = "Synthetic B", type = SourceType.XTREAM, url = "user-entered")

    private fun channel(id: Long, sourceId: Long, remoteId: String, name: String, epg: String?, category: Long? = sportsCategory) =
        ChannelEntity(
            id = id, sourceId = sourceId, name = name, streamUrl = "http://synthetic.invalid/live/user/pass/$remoteId.ts",
            epgChannelId = epg, remoteId = remoteId, categoryId = category,
        )

    private val espn = channel(1, 7, "900001", "US: ESPN", "fixture.espn")
    private val espnFhd = channel(2, 7, "900002", "US: ESPN FHD", "fixture.espn")
    private val espn4k = channel(3, 7, "900003", "US: ESPN 4K", "fixture.espn4k")
    private val adultFeed = channel(4, 7, "900004", "XXX: Late Night", "fixture.adult", category = adultCategory)
    private val inHiddenFolder = channel(5, 7, "900005", "US: Hidden Folder Sports", "fixture.hiddenfolder", category = hiddenCategory)
    private val bEspn = channel(11, 9, "900001", "US: ESPN", "fixture.espn") // same remote id, ANOTHER profile's playlist

    private fun ref(c: ChannelEntity, rank: Int = 0) =
        """{"channelId":"sch_${c.remoteId}","remoteId":"${c.remoteId}","epgChannelId":"${c.epgChannelId}","name":"${c.name}","category":"USA | Sports","logoUrl":null,"networkKey":"espn","confidence":${95 - rank},"reason":"network_exact"}"""

    private fun game(vararg channels: ChannelEntity): SportsEvent =
        SportsFixtures.parsed(SportsFixtures.event("evt_g", status = "LIVE", channels = JSONArray(channels.mapIndexed { i, c -> JSONObject(ref(c, i)) })))

    private class Profile(val id: Long, var playlist: Choice, var rules: SportsVisibilityRules, val adultBlocked: MutableSet<Long> = mutableSetOf())

    private inner class World : SportsAccessData {
        var snapshots = 0
        val profiles = mutableMapOf<Long, Profile>()
        var active: Long = -1
        val rows = mutableListOf(espn, espnFhd, espn4k, adultFeed, inHiddenFolder, bEspn)
        var switchDuringDecision: Long? = null
        val lookedUpSources = mutableListOf<Long>()

        override suspend fun snapshot(): SportsAccessSnapshot? {
            snapshots++
            val p = profiles[active] ?: return null
            val snap = SportsAccessSnapshot(p.id, p.playlist, p.rules)
            switchDuringDecision?.let { active = it; switchDuringDecision = null }
            return snap
        }

        override suspend fun activeProfileId() = active

        override suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity? {
            lookedUpSources += sourceId
            return rows.firstOrNull { it.sourceId == sourceId && it.remoteId == remoteId }
        }

        override suspend fun adultAllowed(profileId: Long, categoryId: Long?) = categoryId !in profiles.getValue(profileId).adultBlocked
    }

    private val open = SportsVisibilityRules(emptySet(), emptySet())
    private val adultProfile = Profile(1, Choice.Selected(playlistA), open)
    private val kidsProfile = Profile(
        2, Choice.Selected(playlistA),
        // As core's hiddenCategoryIds gives a kids profile: every adult category hidden.
        SportsVisibilityRules(emptySet(), setOf(adultCategory)),
        adultBlocked = mutableSetOf(adultCategory),
    )
    private val otherHousehold = Profile(3, Choice.Selected(playlistB), open)

    private fun world(active: Long = 1) = World().apply {
        profiles[1] = adultProfile; profiles[2] = kidsProfile; profiles[3] = otherHousehold
        this.active = active
    }

    private fun names(offer: SportsChannelOffer) = offer.channels.map { it.name }

    // ------------------------------------------------------------------ tests

    @Test
    fun allowedChannels_inBackendRank_withSeparateFeeds() = runBlocking {
        val offer = SportsChannelAccess(world()).offer(game(espn, espnFhd, espn4k))
        assertEquals(listOf("US: ESPN", "US: ESPN FHD", "US: ESPN 4K"), names(offer))
        assertEquals(listOf(false, false, true), offer.channels.map { it.uhd })
        assertNull(offer.issue)
        assertEquals(0, offer.filteredOut)
    }

    @Test
    fun adultCategory_blockedOnAKidsProfile_byTheListRuleAndThePlayTimeCheck() = runBlocking {
        val kids = world(active = 2)
        val offer = SportsChannelAccess(kids).offer(game(adultFeed, espn))
        assertEquals(listOf("US: ESPN"), names(offer))
        assertEquals(1, offer.filteredOut)
        // Even if the list rule missed it, the play-time check alone still refuses it.
        kidsProfile.rules = open
        assertEquals(listOf("US: ESPN"), names(SportsChannelAccess(kids).offer(game(adultFeed, espn))))
    }

    @Test
    fun adultCategory_permittedWhereTheProfileAllowsIt() = runBlocking {
        assertEquals(listOf("XXX: Late Night", "US: ESPN"), names(SportsChannelAccess(world(active = 1)).offer(game(adultFeed, espn))))
    }

    @Test
    fun aHiddenChannel_isNotOffered_andNothingReplacesIt() = runBlocking {
        adultProfile.rules = SportsVisibilityRules(setOf(CustomizeKeys.channel(espnFhd)), emptySet())
        val offer = SportsChannelAccess(world()).offer(game(espn, espnFhd, espn4k))
        assertEquals(listOf("US: ESPN", "US: ESPN 4K"), names(offer))
        assertEquals(1, offer.filteredOut)
    }

    @Test
    fun aChannelInAHiddenCategory_isNotOffered() = runBlocking {
        adultProfile.rules = SportsVisibilityRules(emptySet(), setOf(hiddenCategory))
        assertEquals(listOf("US: ESPN"), names(SportsChannelAccess(world()).offer(game(inHiddenFolder, espn))))
    }

    @Test
    fun renames_readAsInLiveTv() = runBlocking {
        adultProfile.rules = SportsVisibilityRules(emptySet(), emptySet(), renames = mapOf(CustomizeKeys.channel(espn) to "My ESPN"))
        val access = SportsChannelAccess(world())
        val offer = access.offer(game(espn))
        assertEquals(listOf("My ESPN"), names(offer))
        assertEquals("My ESPN", access.revalidate(game(espn), offer.channels.single())!!.name)
    }

    @Test
    fun mixedResults_keepRank_allBlocked_isAnEmptyList() = runBlocking {
        val kids = world(active = 2)
        kidsProfile.rules = SportsVisibilityRules(setOf(CustomizeKeys.channel(espn)), setOf(adultCategory, hiddenCategory))
        val mixed = SportsChannelAccess(kids).offer(game(adultFeed, espn, inHiddenFolder, espnFhd, espn4k))
        assertEquals(listOf("US: ESPN FHD", "US: ESPN 4K"), names(mixed))
        assertEquals(3, mixed.filteredOut)
        val none = SportsChannelAccess(kids).offer(game(adultFeed, espn, inHiddenFolder))
        assertTrue(none.channels.isEmpty())
        assertNull("filtered out is not a playlist problem", none.issue)
        assertEquals(3, none.filteredOut)
    }

    @Test
    fun profileSwitching_usesTheNewProfilesPlaylistAndRules_neverTheOld() = runBlocking {
        val w = world(active = 1)
        val access = SportsChannelAccess(w)
        assertEquals(listOf("XXX: Late Night", "US: ESPN"), names(access.offer(game(adultFeed, espn))))
        w.active = 2
        assertEquals(listOf("US: ESPN"), names(access.offer(game(adultFeed, espn))))
        w.active = 3 // another household member with their own playlist
        val other = access.offer(game(espn))
        assertEquals(listOf(11L), other.channels.map { it.localChannelId })
        assertEquals(listOf(9L), w.lookedUpSources.takeLast(1))
    }

    @Test
    fun aSwitchWhileDeciding_discardsTheDecision() = runBlocking {
        val w = world(active = 1).apply { switchDuringDecision = 2 }
        val offer = SportsChannelAccess(w).offer(game(adultFeed, espn))
        assertTrue(offer.channels.isEmpty())
        assertEquals(SportsAccessIssue.PROFILE_CHANGED, offer.issue)
    }

    @Test
    fun noFallbackToAnotherProfilesPlaylist() = runBlocking {
        val w = world(active = 1)
        w.rows.remove(espn) // gone from this profile's playlist; the other profile still has the same remote id
        val offer = SportsChannelAccess(w).offer(game(espn))
        assertTrue(offer.channels.isEmpty())
        assertTrue("only the active profile's playlist is read", w.lookedUpSources.all { it == 7L })
    }

    @Test
    fun noActiveProfile_noEligiblePlaylist_orSeveralWithoutADefault_offerNothing() = runBlocking {
        assertEquals(SportsAccessIssue.NO_PROFILE, SportsChannelAccess(world(active = -1)).offer(game(espn)).issue)
        adultProfile.playlist = Choice.None
        val w = world()
        assertEquals(SportsAccessIssue.NO_ELIGIBLE_PLAYLIST, SportsChannelAccess(w).offer(game(espn)).issue)
        adultProfile.playlist = Choice.Ambiguous(2)
        assertEquals(SportsAccessIssue.SEVERAL_PLAYLISTS, SportsChannelAccess(w).offer(game(espn)).issue)
        assertTrue("nothing is looked up without one eligible playlist", w.lookedUpSources.isEmpty())
    }

    @Test
    fun playlistDeleted_orRefreshed_staleRecordsAreNotOffered() = runBlocking {
        val w = world()
        val access = SportsChannelAccess(w)
        // A refresh renumbered the local rows but kept the provider ids: the new rows are offered.
        w.rows.replaceAll { if (it.sourceId == 7L) it.copy(id = it.id + 1000) else it }
        assertEquals(listOf(1001L, 1002L), access.offer(game(espn, espnFhd)).channels.map { it.localChannelId })
        // The provider dropped the FHD feed: it is simply gone, nothing stands in for it.
        w.rows.removeAll { it.remoteId == "900002" }
        assertEquals(listOf("US: ESPN"), names(access.offer(game(espn, espnFhd))))
        // The playlist itself was removed from the profile.
        adultProfile.playlist = Choice.None
        assertEquals(SportsAccessIssue.NO_ELIGIBLE_PLAYLIST, access.offer(game(espn)).issue)
    }

    // ------------------------------------------------------------------ watch-time revalidation

    @Test
    fun revalidate_returnsTheCurrentLocalRow_forTheChosenFeed() = runBlocking {
        val access = SportsChannelAccess(world())
        val chosen = access.offer(game(espn, espnFhd)).channels[1]
        val playable = access.revalidate(game(espn, espnFhd), chosen)
        assertEquals(espnFhd.id, playable!!.id)
    }

    @Test
    fun revalidate_refusesAChannelHiddenAfterTheScreenOpened() = runBlocking {
        val access = SportsChannelAccess(world())
        val chosen = access.offer(game(espn, espnFhd)).channels.first()
        adultProfile.rules = SportsVisibilityRules(setOf(CustomizeKeys.channel(espn)), emptySet())
        assertNull("hidden since: refused, and not swapped for the FHD feed", access.revalidate(game(espn, espnFhd), chosen))
    }

    @Test
    fun revalidate_afterAProfileSwitch_orAParentalChange_refuses() = runBlocking {
        val w = world(active = 1)
        val access = SportsChannelAccess(w)
        val chosen = access.offer(game(adultFeed)).channels.single()
        w.active = 2
        assertNull(access.revalidate(game(adultFeed), chosen))
        w.active = 1
        adultProfile.adultBlocked += adultCategory // the profile's parental setting changed
        assertNull(access.revalidate(game(adultFeed), chosen))
    }

    @Test
    fun revalidate_followsARefreshedRow_butNotAFeedTheGameNoLongerLists() = runBlocking {
        val w = world()
        val access = SportsChannelAccess(w)
        val chosen = access.offer(game(espn, espnFhd)).channels.first()
        w.rows.replaceAll { if (it.id == espn.id) it.copy(id = 5001) else it }
        assertEquals(5001L, access.revalidate(game(espn, espnFhd), chosen)!!.id)
        assertNull("the game's matches changed: not offered any more", access.revalidate(game(espnFhd), chosen))
    }

    // ------------------------------------------------------------------ exposure

    @Test
    fun theOffer_carriesNoRemoteIdsEpgIdsCredentialsOrStreamUrls() = runBlocking {
        val access = SportsChannelAccess(world())
        val offer = access.offer(game(espn, espnFhd, espn4k))
        val shown = offer.toString() + offer.channels.joinToString { "${it.localChannelId} ${it.name} ${it.networkKey} ${it.category} ${it.uhd}" }
        for (secret in listOf("900001", "fixture.espn", "synthetic-secret", "synthetic.invalid", "/live/", "network_exact", "95")) {
            assertFalse(secret, shown.contains(secret))
        }
        // The playable row (with its stream URL) only comes out of revalidate, at the moment of acting.
        assertNotNull(access.revalidate(game(espn), offer.channels.first())?.streamUrl)
    }

    // ------------------------------------------------------------------ a screen of cards (offerAll)

    private fun gameWith(id: String, vararg channels: ChannelEntity): SportsEvent =
        SportsFixtures.parsed(SportsFixtures.event(id, status = "LIVE", channels = JSONArray(channels.mapIndexed { i, c -> JSONObject(ref(c, i)) })))

    @Test
    fun offerAll_countsEveryGameFromOneSnapshot_afterFiltering() = runBlocking {
        val kids = world(active = 2)
        val offers = SportsChannelAccess(kids).offerAll(
            listOf(gameWith("evt_a", espn, espnFhd), gameWith("evt_b", adultFeed), gameWith("evt_none"), gameWith("evt_c", espn4k)),
        )
        assertEquals("one profile read for the whole screen", 1, kids.snapshots)
        assertEquals(2L, offers.profileId)
        assertEquals(2, offers.countFor("evt_a"))
        assertEquals("blocked for this profile: zero", 0, offers.countFor("evt_b"))
        assertEquals(1, offers.byEventId.getValue("evt_b").filteredOut)
        assertEquals("a game listing no channels is not looked up", false, offers.byEventId.containsKey("evt_none"))
        assertEquals(1, offers.countFor("evt_c"))
    }

    @Test
    fun offerAll_followsTheActiveProfile_andDiscardsOnASwitch() = runBlocking {
        val w = world(active = 1)
        val access = SportsChannelAccess(w)
        assertEquals(1, access.offerAll(listOf(gameWith("evt_b", adultFeed))).countFor("evt_b"))
        w.active = 2
        assertEquals(0, access.offerAll(listOf(gameWith("evt_b", adultFeed))).countFor("evt_b"))
        w.switchDuringDecision = 1
        val discarded = access.offerAll(listOf(gameWith("evt_b", adultFeed)))
        assertEquals(SportsAccessIssue.PROFILE_CHANGED, discarded.issue)
        assertTrue(discarded.byEventId.isEmpty())
        adultProfile.playlist = Choice.Ambiguous(2)
        assertEquals(SportsAccessIssue.SEVERAL_PLAYLISTS, access.offerAll(listOf(gameWith("evt_a", espn))).issue)
    }
}
