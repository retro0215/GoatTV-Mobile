package tv.own.owntv.mobile.sports.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The models against the GoatTV Sports API's real response shape, from `src/test/resources/sports/`.
 *
 * Those fixtures carry exactly the field names the live API returned for a branded `/sports/home`
 * (checked 2026-11-13) with synthetic values — made-up ids, no real stream ids. If the backend renames
 * or drops a field, [fixturesUseOnlyTheLiveFieldNames] is the place to update first, together with the
 * fixture; nothing here invents a field the API does not send.
 */
class SportsBackendContractTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("sports/$name")) { "missing test resource sports/$name" }.readText()

    private val home: SportsHomePayload by lazy { requireNotNull(SportsApiParser.parseHome(resource("home_branded.json"))) }

    private fun event(id: String) = home.events.single { it.id == id }

    @Test
    fun fixturesUseOnlyTheLiveFieldNames() {
        val root = JSONObject(resource("home_branded.json"))
        assertEquals(setOf("generatedAt", "cursor", "leagues", "popular", "events"), root.keys().asSequence().toSet())
        val liveEventKeys = setOf(
            "id", "leagueId", "title", "away", "home", "startTime", "status", "statusDetail", "venueName",
            "priority", "broadcasts", "updatedAt", "channels", "fight",
        )
        val liveChannelKeys = setOf(
            "channelId", "remoteId", "epgChannelId", "name", "category", "logoUrl", "networkKey", "confidence", "reason",
        )
        val events = root.getJSONArray("events")
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val extra = e.keys().asSequence().toSet() - liveEventKeys
            // One event deliberately carries a field from "a future backend" to prove it is ignored.
            assertTrue("unexpected event fields $extra", extra.isEmpty() || extra == setOf("aFieldFromAFutureBackend"))
            val channels = e.getJSONArray("channels")
            for (c in 0 until channels.length()) assertEquals(liveChannelKeys, channels.getJSONObject(c).keys().asSequence().toSet())
        }
    }

    @Test
    fun homePayload_envelope() {
        assertEquals("2026-11-13T18:00:00.000Z", home.cursor)
        assertEquals(SportsApiParser.parseIsoUtcMs("2026-11-13T18:00:05.120Z"), home.generatedAtMs)
        assertEquals("leagues sorted by order", listOf("nfl", "nba", "nhl", "ufc", "epl"), home.leagues.map { it.id })
        assertEquals("popular keeps backend order", listOf("evt_fixture_nhl_live", "evt_fixture_nfl_sched"), home.popular)
        assertEquals(4, home.events.size)
    }

    @Test
    fun gameIdentity_status_league_and_schedule() {
        val nfl = event("evt_fixture_nfl_sched")
        assertEquals("nfl", nfl.leagueId)
        assertEquals("football", home.leagues.single { it.id == nfl.leagueId }.sport)
        assertEquals(SportsEventStatus.SCHEDULED, nfl.status)
        assertEquals(SportsApiParser.parseIsoUtcMs("2026-11-16T18:00:00Z"), nfl.startTimeMs)
        assertEquals("Sun, November 16th at 1:00 PM EST", nfl.statusDetail)
        assertEquals("Fixture Stadium", nfl.venueName)
        assertEquals(80, nfl.priority)
        assertEquals(SportsApiParser.parseIsoUtcMs("2026-11-13T17:59:41.002Z"), nfl.updatedAtMs)
        // The id is what the game-detail route takes (/sports/events/{id}/game-center).
        assertTrue(nfl.id.startsWith("evt_"))
    }

    @Test
    fun teams_and_scores() {
        val live = event("evt_fixture_nhl_live")
        assertTrue(live.isLive && live.isTeamEvent && live.showsScores)
        assertEquals("3", live.home!!.score)
        assertEquals("2", live.away!!.score)
        assertEquals("nhl.home", live.home.id)
        assertEquals("HHC", live.home.abbreviation)
        assertNull("null logo stays null", live.home.logoUrl)

        val sched = event("evt_fixture_nfl_sched")
        assertNull(sched.home!!.score)
        assertFalse("no placeholder scores before kickoff", sched.showsScores)
        assertEquals("https://images.example/teams/nfl.home.png", sched.home.logoUrl)

        val final = event("evt_fixture_nba_final")
        assertEquals(SportsEventStatus.FINAL, final.status)
        assertEquals("Final/OT", final.statusDetail)
        assertTrue(final.showsScores)
    }

    @Test
    fun broadcasts() {
        val nfl = event("evt_fixture_nfl_sched")
        assertEquals(listOf("ESPN", "ESPN+"), nfl.broadcasts.map { it.name })
        assertEquals(SportsBroadcast("ESPN+", "STREAMING", "NATIONAL", "espn-plus"), nfl.broadcasts[1])
        assertEquals("ESPN", nfl.primaryBroadcast!!.name)
        assertEquals("HOME", event("evt_fixture_nhl_live").broadcasts.single().market)
        assertNull("a null networkKey stays null", event("evt_fixture_nba_final").broadcasts.single().networkKey)
        assertTrue(event("evt_fixture_ufc_card").broadcasts.isEmpty())
    }

    @Test
    fun multipleMatchedChannels_keepBackendRankAndEveryField() {
        val channels = event("evt_fixture_nfl_sched").channels
        assertEquals(listOf("sch_fixture_1", "sch_fixture_2"), channels.map { it.channelId })
        val primary = channels[0]
        assertEquals("900001", primary.remoteId)
        assertEquals("fixture.espn.us", primary.epgChannelId)
        assertEquals("US: ESPN FHD", primary.name)
        assertEquals("USA | Sports", primary.category)
        assertNull(primary.logoUrl)
        assertEquals("espn", primary.networkKey)
        assertEquals(95, primary.confidence)
        assertEquals("network_exact", primary.reason)
        assertNull("an alternate without an EPG id is still kept", channels[1].epgChannelId)
        assertEquals("network_alias", channels[1].reason)
    }

    @Test
    fun brandChannelReferences_eventChannelsAndUnmatchedGames() {
        val card = event("evt_fixture_ufc_card")
        assertEquals("event_channel", card.channels.single().reason)
        assertNull(card.channels.single().networkKey)
        assertEquals("rsn", event("evt_fixture_nhl_live").channels.single().reason)
        assertTrue("matched nothing: an empty list, not an error", event("evt_fixture_nba_final").channels.isEmpty())
    }

    @Test
    fun fightCard_fromTheLiveShape() {
        val card = event("evt_fixture_ufc_card")
        assertEquals("UFC Fixture Night", card.title)
        assertNull(card.home); assertNull(card.away)
        val fight = card.fight!!
        assertEquals(2, fight.bouts.size)
        assertEquals(2, fight.mainEvent!!.order)
        assertEquals(listOf("Fighter Three", "Fighter Four"), fight.mainEvent!!.fighters.map { it.name })
        assertNull("no winner before the bout", fight.mainEvent!!.winner)
        assertNull("other events carry fight: null", event("evt_fixture_nfl_sched").fight)
    }

    @Test
    fun unknownFields_areIgnored() {
        assertNotNull(event("evt_fixture_nba_final"))
    }

    @Test
    fun leaguesPage_fromTheLiveShape() {
        val leagues = requireNotNull(SportsApiParser.parseLeaguesPage(resource("leagues.json")))
        assertEquals(listOf("nfl", "ufc", "epl"), leagues.map { it.id })
        assertEquals("athlete", leagues.single { it.id == "ufc" }.participantKind)
        assertEquals("england", leagues.single { it.id == "epl" }.region)
    }
}
