package tv.own.owntv.mobile.sports.hub

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
import tv.own.owntv.mobile.sports.live.SportsAccessData
import tv.own.owntv.mobile.sports.live.SportsAccessSnapshot
import tv.own.owntv.mobile.sports.live.SportsChannelAccess
import tv.own.owntv.mobile.sports.live.SportsEvent
import tv.own.owntv.mobile.sports.live.SportsFixtures
import tv.own.owntv.mobile.sports.live.SportsPlaylistSelection
import tv.own.owntv.mobile.sports.live.SportsVisibilityRules
import tv.own.owntv.mobile.sports.live.SportsWatchCheck
import tv.own.owntv.mobile.sports.live.SportsWatchRejection
import tv.own.owntv.mobile.ui.screens.live.LivePlaybackState
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewContent
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewModel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsChannelLabel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsMultiviewAdd
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsPreviewOwner
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsWatchLauncher

/**
 * Preview and Add to Multiview from a game's Where to Watch list: the same fresh access decision as
 * Watch, one stream for Preview (owned by the panel, handed over by Watch, taken back when the panel
 * goes), and the grid's own rules for adding. Synthetic data only.
 */
class SportsPreviewMultiviewTest {

    private val playlist = SourceEntity(id = 7, name = "Synthetic", type = SourceType.XTREAM, url = "user-entered")
    private val sportsCategory = 1L
    private val adultCategory = 2L
    private fun row(id: Long, remoteId: String, name: String, category: Long = sportsCategory) = ChannelEntity(
        id = id, sourceId = 7, name = name, streamUrl = "http://synthetic.invalid/user/pass/$remoteId.ts", epgChannelId = "fixture.$remoteId",
        remoteId = remoteId, categoryId = category,
    )

    private val espnA = row(1, "900001", "US: ESPN")
    private val espnB = row(2, "900002", "US: ESPN") // same name, another category
    private val espn4k = row(3, "900003", "US: ESPN 4K")
    private val adultFeed = row(4, "900004", "XXX: Late Night", category = adultCategory)

    private fun game(vararg channels: Pair<ChannelEntity, String>): SportsEvent = SportsFixtures.parsed(
        SportsFixtures.event(
            "evt_g", status = "LIVE",
            channels = JSONArray(
                channels.mapIndexed { i, (c, category) ->
                    JSONObject("""{"channelId":"sch_${c.remoteId}","remoteId":"${c.remoteId}","epgChannelId":"${c.epgChannelId}","name":"${c.name}","category":"$category","logoUrl":null,"networkKey":"espn","confidence":${95 - i},"reason":"network_exact"}""")
                },
            ),
        ),
    )

    private val g = game(espnA to "USA | Sports", espnB to "USA | Sports Backup", espn4k to "EN | 4K UHD", adultFeed to "Adult")

    private inner class World : SportsAccessData {
        var active = 1L
        var hidden = emptySet<String>()
        val kids = setOf(2L)
        val rows = mutableListOf(espnA, espnB, espn4k, adultFeed)
        override suspend fun snapshot() = SportsAccessSnapshot(active, SportsPlaylistSelection.Choice.Selected(playlist), SportsVisibilityRules(hidden, emptySet()))
        override suspend fun activeProfileId() = active
        override suspend fun findByRemote(sourceId: Long, remoteId: String) = rows.firstOrNull { it.remoteId == remoteId }
        override suspend fun adultAllowed(profileId: Long, categoryId: Long?) = !(profileId in kids && categoryId == adultCategory)
    }

    private val world = World()
    private val access = SportsChannelAccess(world)

    // A stand-in for the app's one tuner, with its own reopen rule (LivePlaybackState.shouldOpen).
    private val playback = LivePlaybackState()
    private var playing: Long? = null
    private var opens = 0
    private var stops = 0
    private var lastZap: LiveTuner.ZapList? = null
    private fun tune(id: Long, zap: LiveTuner.ZapList) {
        lastZap = zap
        if (playback.shouldOpen(id, hasStream = playing != null)) {
            playback.openedId = id
            playing = id
            opens++
        }
    }

    private val owner = SportsPreviewOwner(playingChannelId = { playing }, stop = { stops++; playing = null; playback.openedId = null })
    private val launcher = SportsWatchLauncher(check = { e, c, p -> access.checkWatch(e, c, p) }, tune = ::tune)

    private suspend fun preview(index: Int, offeredTo: Long = 1) =
        launcher.run(g, access.offer(g).channels[index], offeredTo, { true }) { a -> tune(a.channel.id, LiveTuner.ZapList("t", a.zapChannels)); owner.started(a.channel.id) }

    private suspend fun watch(index: Int) =
        launcher.run(g, access.offer(g).channels[index], 1, { true }) { a -> tune(a.channel.id, LiveTuner.ZapList("t", a.zapChannels)); owner.handedToPlayer() }

    // ------------------------------------------------------------------ preview

    @Test
    fun aPreview_isDecidedAgain_thenPlaysInThePanel_withTheGamesChannelsAsTheZapList() = runBlocking {
        assertTrue(preview(0) is SportsWatchCheck.Allowed)
        assertEquals(espnA.id, owner.owned.value)
        assertEquals(1, opens)
        assertEquals(listOf(espnA.id, espnB.id, espn4k.id, adultFeed.id), lastZap!!.channels.map { it.id })
    }

    @Test
    fun switchingPreviewChannels_replacesTheStream_neverAddsOne() = runBlocking {
        preview(0)
        preview(2)
        assertEquals(espn4k.id, owner.owned.value)
        assertEquals(espn4k.id, playing)
        assertEquals("two opens, one stream at a time", 2, opens)
        preview(2)
        assertEquals("the same channel again: no reopen", 2, opens)
    }

    @Test
    fun closingThePanel_stopsItsPreview_once() = runBlocking {
        preview(0)
        owner.release()
        owner.release()
        assertEquals(1, stops)
        assertNull(playing)
        assertNull(owner.owned.value)
    }

    @Test
    fun watchFromAPreview_carriesTheSameStreamOn_andThePanelLetsGo() = runBlocking {
        preview(1)
        assertTrue(watch(1) is SportsWatchCheck.Allowed)
        assertEquals("no second open, no second decoder", 1, opens)
        assertNull(owner.owned.value)
        owner.release() // the panel goes away as the player opens
        assertEquals("the player's stream is not stopped", 0, stops)
        assertEquals(espnB.id, playing)
    }

    @Test
    fun aStreamSomeoneElseStarted_isNeverStoppedByThePanel() = runBlocking {
        preview(0)
        playing = 99 // the user started something else meanwhile (Live TV, the mini player)
        owner.release()
        assertEquals(0, stops)
        assertEquals(99L, playing)
    }

    @Test
    fun aRefusedPreview_playsNothing_andOwnsNothing() = runBlocking {
        val chosen = access.offer(g).channels[0]
        world.hidden = setOf(CustomizeKeys.channel(espnA))
        val result = launcher.run(g, chosen, 1, { true }) { a -> tune(a.channel.id, LiveTuner.ZapList("t", a.zapChannels)); owner.started(a.channel.id) }
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.NO_LONGER_AVAILABLE), result)
        assertEquals(0, opens)
        assertNull(owner.owned.value)
    }

    @Test
    fun aPreviewAfterAProfileSwitch_isRefused() = runBlocking {
        val chosen = access.offer(g).channels[0]
        world.active = 3
        val result = launcher.run(g, chosen, 1, { true }) { owner.started(it.channel.id) }
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.PROFILE_CHANGED), result)
        assertNull(owner.owned.value)
    }

    @Test
    fun aPreviewInTheBackground_startsNothing() = runBlocking {
        val result = launcher.run(g, access.offer(g).channels[0], 1, { false }) { owner.started(it.channel.id) }
        assertNull(result)
        assertNull(owner.owned.value)
        assertEquals(0, opens)
    }

    // ------------------------------------------------------------------ add to multiview

    @Test
    fun addToMultiview_isDecidedAgain_andAddsTheLocalRow_notTheBackendsChannel() = runBlocking {
        val chosen = access.offer(g).channels[2]
        val allowed = launcher.run(g, chosen, 1, { true }) { } as SportsWatchCheck.Allowed
        val grid = MultiviewModel().add(MultiviewContent.Live(allowed.channel)).model
        assertEquals(listOf(espn4k.id), grid.tiles.map { (it.content as MultiviewContent.Live).channel.id })
        assertEquals("no stream is opened by adding; the grid opens its tiles itself", 0, opens)
    }

    @Test
    fun addToMultiview_onAKidsProfile_refusesAnAdultChannel() = runBlocking {
        val adultOffered = access.offer(g).channels.first { it.name == "XXX: Late Night" }
        world.active = 2 // kids: switched while the list was open
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.PROFILE_CHANGED), launcher.run(g, adultOffered, 1, { true }) { })
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.NO_LONGER_AVAILABLE), launcher.run(g, adultOffered, 2, { true }) { })
        assertFalse("never offered to the kids profile", access.offer(g).channels.any { it.name == "XXX: Late Night" })
    }

    @Test
    fun theGridsRules_decideWhatAddDoes() {
        var warned = 0
        val warn = { warned++; true }
        assertEquals(SportsMultiviewAdd.ALREADY_THERE, SportsMultiviewAdd.decide(alreadyThere = true, isFull = false, shouldWarn = warn))
        assertEquals(SportsMultiviewAdd.FULL, SportsMultiviewAdd.decide(alreadyThere = false, isFull = true, shouldWarn = warn))
        assertEquals("the warning is not even asked about (it records being shown)", 0, warned)
        assertEquals(SportsMultiviewAdd.WARN_FIRST, SportsMultiviewAdd.decide(false, false, warn))
        assertEquals(SportsMultiviewAdd.ADD, SportsMultiviewAdd.decide(false, false) { false })
    }

    @Test
    fun aFullGrid_andAChannelAlreadyThere_addNothing() {
        var grid = MultiviewModel()
        (10L until 10L + MultiviewModel.MAX_TILES).forEach { grid = grid.add(MultiviewContent.Live(row(it, "$it", "Ch $it"))).model }
        assertTrue(grid.isFull)
        val one = MultiviewContent.Live(espnA)
        assertEquals(SportsMultiviewAdd.FULL, SportsMultiviewAdd.decide(grid.contains(one.key), grid.isFull) { false })
        assertNull("the grid itself refuses a sixth tile", grid.add(one).slot)

        val small = MultiviewModel().add(one).model
        assertEquals(SportsMultiviewAdd.ALREADY_THERE, SportsMultiviewAdd.decide(small.contains(one.key), small.isFull) { false })
        assertEquals("never twice", 1, small.add(one).model.tiles.size)
    }

    // ------------------------------------------------------------------ the rows

    @Test
    fun sameNamedChannels_areToldApartByTheirCategory() = runBlocking {
        val labels = access.offer(g).channels.map(SportsChannelLabel::of)
        assertEquals(listOf("US: ESPN", "US: ESPN", "US: ESPN 4K", "XXX: Late Night"), labels.map { it.name })
        assertEquals(listOf("USA | Sports", "USA | Sports Backup", "EN | 4K UHD", "Adult"), labels.map { it.category })
        assertTrue("the two ESPN rows differ", labels[0] != labels[1])
        assertEquals(listOf(false, false, true, false), labels.map { it.uhd })
    }

    @Test
    fun aBlankCategory_orOneThatRepeatsTheName_isLeftOut() = runBlocking {
        val g2 = game(espnA to "  ", espn4k to "US: ESPN 4K")
        assertEquals(listOf(null, null), access.offer(g2).channels.map { SportsChannelLabel.of(it).category })
    }

    @Test
    fun aRowShowsNothingSensitive() = runBlocking {
        val printed = access.offer(g).channels.map(SportsChannelLabel::of).joinToString()
        for (secret in listOf("synthetic.invalid", "pass", "900001", "fixture.", "sch_", "network_exact")) {
            assertFalse("'$secret' in $printed", printed.contains(secret))
        }
    }
}
