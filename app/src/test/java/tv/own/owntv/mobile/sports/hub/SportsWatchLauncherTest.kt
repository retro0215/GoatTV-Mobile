package tv.own.owntv.mobile.sports.hub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
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
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsLatestEvent
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsWatchLauncher
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsWatchNext

/**
 * Watch from a game's Where to Watch list: decided again, then the app's one tuner gets the channel and
 * the game's channels as its zap list — or nothing plays. Synthetic data only.
 */
class SportsWatchLauncherTest {

    private val playlist = SourceEntity(id = 7, name = "Synthetic", type = SourceType.XTREAM, url = "user-entered")
    private fun row(id: Long, remoteId: String, name: String) = ChannelEntity(
        id = id, sourceId = 7, name = name, streamUrl = "http://synthetic.invalid/$remoteId.ts", epgChannelId = null, remoteId = remoteId, categoryId = 1,
    )

    private val espn = row(1, "900001", "US: ESPN")
    private val espn2 = row(2, "900002", "US: ESPN 2")
    private val elsewhere = row(3, "900003", "US: Somewhere Else") // in the playlist, not listed for the game

    private fun game(vararg channels: ChannelEntity, updated: String = "2026-11-13T12:00:00.000Z"): SportsEvent = SportsFixtures.parsed(
        SportsFixtures.event(
            "evt_g", status = "LIVE", updated = updated,
            channels = JSONArray(
                channels.mapIndexed { i, c ->
                    JSONObject("""{"channelId":"sch_${c.remoteId}","remoteId":"${c.remoteId}","epgChannelId":null,"name":"${c.name}","category":"Sports","logoUrl":null,"networkKey":null,"confidence":${90 - i},"reason":"name"}""")
                },
            ),
        ),
    )

    private inner class World : SportsAccessData {
        var active = 1L
        var hidden = emptySet<String>()
        val rows = mutableListOf(espn, espn2, elsewhere)
        override suspend fun snapshot() = SportsAccessSnapshot(active, SportsPlaylistSelection.Choice.Selected(playlist), SportsVisibilityRules(hidden, emptySet()))
        override suspend fun activeProfileId() = active
        override suspend fun findByRemote(sourceId: Long, remoteId: String) = rows.firstOrNull { it.remoteId == remoteId }
        override suspend fun adultAllowed(profileId: Long, categoryId: Long?) = true
    }

    private val world = World()
    private val access = SportsChannelAccess(world)
    private val tunes = mutableListOf<Pair<Long, LiveTuner.ZapList>>()
    private var checks = 0
    private val launcher = SportsWatchLauncher(
        check = { e, c, p -> checks++; access.checkWatch(e, c, p) },
        tune = { id, zap -> tunes += id to zap },
    )

    @Test
    fun anAllowedWatch_tunesOnce_withTheGamesChannelsAsTheZapList() = runBlocking {
        val g = game(espn, espn2)
        val chosen = access.offer(g).channels[1]
        val result = launcher.watch(g, chosen, offeredTo = 1, title = "Away at Home") { true }
        assertTrue(result is SportsWatchCheck.Allowed)
        assertEquals(1, tunes.size)
        val (id, zap) = tunes.single()
        assertEquals(espn2.id, id)
        assertEquals("Away at Home", zap.title)
        assertEquals("only the game's channels, in rank order", listOf(espn.id, espn2.id), zap.channels.map { it.id })
        assertEquals(SportsWatchNext.OPEN_PLAYER, SportsWatchNext.after(result))
    }

    @Test
    fun aRefusedWatch_tunesNothing() = runBlocking {
        val g = game(espn)
        val chosen = access.offer(g).channels.single()
        world.active = 2 // another profile since the list was shown
        val result = launcher.watch(g, chosen, offeredTo = 1, title = "t") { true }
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.PROFILE_CHANGED), result)
        assertTrue(tunes.isEmpty())
        assertEquals(SportsWatchNext.SHOW_REFUSAL, SportsWatchNext.after(result))
    }

    @Test
    fun aChannelHiddenWhileTheListWasOpen_tunesNothing() = runBlocking {
        val g = game(espn)
        val chosen = access.offer(g).channels.single()
        world.hidden = setOf(tv.own.owntv.core.customize.CustomizeKeys.channel(espn))
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.NO_LONGER_AVAILABLE), launcher.watch(g, chosen, 1, "t") { true })
        assertTrue(tunes.isEmpty())
    }

    @Test
    fun leavingTheScreenBeforeTheCheckEnds_startsNothing_inTheBackground() = runBlocking {
        val g = game(espn)
        val chosen = access.offer(g).channels.single()
        assertNull(launcher.watch(g, chosen, 1, "t") { false })
        assertTrue(tunes.isEmpty())
        assertEquals(SportsWatchNext.NOTHING, SportsWatchNext.after(null))
    }

    @Test
    fun aSecondTapWhileTheFirstIsChecked_isIgnored() = runBlocking {
        val g = game(espn)
        val chosen = access.offer(g).channels.single()
        val gate = CompletableDeferred<Unit>()
        val slow = SportsWatchLauncher(
            check = { e, c, p -> checks++; gate.await(); access.checkWatch(e, c, p) },
            tune = { id, zap -> tunes += id to zap },
        )
        val first = async { slow.watch(g, chosen, 1, "t") { true } }
        yield()
        assertNull("second tap", slow.watch(g, chosen, 1, "t") { true })
        gate.complete(Unit)
        assertTrue(first.await() is SportsWatchCheck.Allowed)
        assertEquals(1, checks)
        assertEquals(1, tunes.size)
    }

    @Test
    fun watchingTheChannelAlreadyPlaying_opensNoSecondStream() = runBlocking {
        // The tuner's own rule (LivePlaybackState.shouldOpen), as tune() applies it.
        val playback = LivePlaybackState()
        var opens = 0
        val tuner = SportsWatchLauncher(
            check = { e, c, p -> access.checkWatch(e, c, p) },
            tune = { id, _ -> if (playback.shouldOpen(id, hasStream = playback.openedId != null)) { playback.openedId = id; opens++ } },
        )
        val g = game(espn, espn2)
        val chosen = access.offer(g).channels[0]
        tuner.watch(g, chosen, 1, "t") { true }
        tuner.watch(g, chosen, 1, "t") { true } // Back to the panel, Watch the same channel again
        assertEquals("one decoder, one connection", 1, opens)
        tuner.watch(g, access.offer(g).channels[1], 1, "t") { true }
        assertEquals("another channel does open", 2, opens)
    }

    @Test
    fun watchJudgesTheNewestCopyOfTheGame() {
        val shown = game(espn, espn2, updated = "2026-11-13T12:00:00.000Z")
        val newer = game(espn, updated = "2026-11-13T12:05:00.000Z")
        val older = game(espn, espn2, elsewhere, updated = "2026-11-13T11:00:00.000Z")
        assertSame(newer, SportsLatestEvent.pick(shown, older, newer, null))
        assertSame(shown, SportsLatestEvent.pick(shown, older))
        val otherGame = SportsFixtures.parsed(SportsFixtures.event("evt_other", updated = "2026-11-13T13:00:00.000Z"))
        assertSame("another game never stands in", shown, SportsLatestEvent.pick(shown, otherGame))
    }

    @Test
    fun aFeedTheNewestCopyDropped_isRefused() = runBlocking {
        val shown = game(espn, espn2)
        val chosen = access.offer(shown).channels[1]
        val newest = SportsLatestEvent.pick(shown, game(espn, updated = "2026-11-13T12:05:00.000Z"))
        assertEquals(SportsWatchCheck.Rejected(SportsWatchRejection.NO_LONGER_AVAILABLE), launcher.watch(newest, chosen, 1, "t") { true })
        assertTrue(tunes.isEmpty())
    }
}
