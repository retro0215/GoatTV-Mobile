package tv.own.owntv.mobile.sports.live

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.mobile.sports.live.SportsFixtures.event
import tv.own.owntv.mobile.sports.live.SportsFixtures.team

/**
 * The parser halves of the TV app's fight-card and soccer tests (v4.6.1 `SportsFootballAndFightTest`,
 * `SportsSoccerTest`): fight cards and league metadata. Their slate, row and presentation assertions
 * belong to the screens and come later.
 */
class SportsFightAndLeagueParsingTest {

    private fun fighter(id: String, name: String, record: String?, flag: String?) = JSONObject().apply {
        put("id", id); put("name", name); put("shortName", JSONObject.NULL); put("record", record ?: JSONObject.NULL)
        put("country", "USA"); put("flagUrl", flag ?: JSONObject.NULL)
    }

    private fun bout(
        order: Int, start: String, a: JSONObject, b: JSONObject, status: String = "SCHEDULED", main: Boolean = false,
        winner: Int? = null, method: String? = null, round: Int? = null, clock: String? = null,
    ) = JSONObject().apply {
        put("order", order); put("weightClass", "Welterweight"); put("scheduledRounds", if (main) 5 else 3); put("startTime", start)
        put("status", status); put("round", round ?: JSONObject.NULL); put("clock", clock ?: JSONObject.NULL); put("mainEvent", main)
        put("fighters", JSONArray(listOf(a, b))); put("winner", winner ?: JSONObject.NULL); put("method", method ?: JSONObject.NULL)
    }

    /** The backend's `fight.bouts` shape, with the values of a real UFC Fight Night card (as in the TV tests). */
    private fun ufcCard(id: String = "evt_ufc_fn") = event(
        id, league = "ufc", status = "FINAL", start = "2026-09-26T21:00:00Z",
        home = null, away = null, title = "UFC Fight Night: Rosas Jr. vs. Barcelos",
    ).apply {
        put(
            "fight",
            JSONObject().put(
                "bouts",
                JSONArray(
                    listOf(
                        bout(1, "2026-09-26T21:00:00Z", fighter("ftr_00000000000000000001", "Vanessa Demopoulos", "11-9-0", null), fighter("ftr_00000000000000000002", "Yazmin Jauregui", "12-2-0", null),
                            status = "FINAL", winner = 1, method = "KO/TKO", round = 1, clock = "1:02"),
                        bout(2, "2026-09-26T21:00:00Z", fighter("ftr_00000000000000000003", "Robert Bryczek", "18-8-0", null), fighter("ftr_00000000000000000004", "Rodolfo Vieira", "12-5-0", null),
                            status = "FINAL", winner = 1, method = null, round = 3, clock = "5:00"),
                        bout(3, "2026-09-27T00:00:00Z", fighter("ftr_00000000000000000005", "Raul Rosas Jr.", "13-1-0", "https://a.espncdn.com/i/teamlogos/countries/500/mex.png"),
                            fighter("ftr_00000000000000000006", "Raoni Barcelos", "22-6-0", null), status = "FINAL", main = true, winner = 0, method = "KO/TKO", round = 5, clock = "1:38"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `fight card parses bouts, main event, winners and provider-stated methods only`() {
        val e = SportsFixtures.parsed(ufcCard())
        val fight = e.fight!!
        assertEquals(3, fight.bouts.size)
        assertEquals(listOf(1, 2, 3), fight.bouts.map { it.order })
        assertEquals("Raul Rosas Jr.", fight.mainEvent!!.fighters[fight.mainEvent!!.winner!!].name)
        assertEquals(listOf("KO/TKO", null, "KO/TKO"), fight.bouts.map { it.method })
        assertEquals(listOf(3, 3, 5), fight.bouts.map { it.scheduledRounds })
        assertEquals("1:38", fight.mainEvent!!.clock)
        assertEquals(SportsEventStatus.FINAL, fight.mainEvent!!.status)
        assertEquals("13-1-0", fight.mainEvent!!.fighters[0].record)
        assertNull("no team semantics", e.home)
        assertFalse(e.isTeamEvent)
        assertNull("not a fight card", SportsFixtures.parsed(event("evt_nfl")).fight)
    }

    @Test
    fun `main event falls back to the last bout, malformed bouts dropped, only https flags kept`() {
        val raw = ufcCard().apply {
            val bouts = getJSONObject("fight").getJSONArray("bouts")
            bouts.getJSONObject(2).put("mainEvent", false)
            bouts.getJSONObject(0).getJSONArray("fighters").getJSONObject(0).put("flagUrl", "http://insecure.example/flag.png")
            bouts.put(JSONObject().put("order", 9).put("fighters", JSONArray(listOf(fighter("ftr_x", "Solo", null, null))))) // one fighter → dropped
        }
        val fight = SportsFixtures.parsed(raw).fight!!
        assertEquals(3, fight.bouts.size)
        assertEquals(3, fight.mainEvent!!.order)
        assertNull("only https flags", fight.bouts[0].fighters[0].flagUrl)
    }

    @Test
    fun `a winner outside the two fighters, or no bouts at all, is not trusted`() {
        val raw = ufcCard().apply { getJSONObject("fight").getJSONArray("bouts").getJSONObject(0).put("winner", 7) }
        assertNull(SportsFixtures.parsed(raw).fight!!.bouts[0].winner)
        val empty = event("evt_ufc_empty", league = "ufc", home = null, away = null, title = "UFC 999").put("fight", JSONObject().put("bouts", JSONArray()))
        assertNull(SportsFixtures.parsed(empty).fight)
    }

    @Test
    fun `league metadata - competitionType, region and gender for soccer, null for US leagues`() {
        val leagues = JSONArray(
            listOf(
                """{"id":"nfl","name":"NFL","order":1,"sport":"football","shortName":"NFL","participantKind":"team","competitionType":null,"region":null,"gender":null}""",
                """{"id":"ucl","name":"UEFA Champions League","order":41,"sport":"soccer","shortName":"UCL","participantKind":"team","competitionType":"continental","region":"europe","gender":"men"}""",
                """{"id":"liga-mx","name":"Liga MX","order":43,"sport":"soccer","shortName":"Liga MX","participantKind":"team","competitionType":"league","region":"mexico","gender":"men"}""",
            ).map { JSONObject(it) },
        )
        val parsed = SportsApiParser.parseLeaguesPage(JSONObject().put("leagues", leagues).toString())!!
        assertEquals("sorted by order", listOf("nfl", "ucl", "liga-mx"), parsed.map { it.id })
        val byId = parsed.associateBy { it.id }
        assertEquals(Triple("continental", "europe", "men"), byId.getValue("ucl").let { Triple(it.competitionType, it.region, it.gender) })
        assertEquals(Triple(null, null, null), byId.getValue("nfl").let { Triple(it.competitionType, it.region, it.gender) })
        assertEquals("soccer", byId.getValue("liga-mx").sport)
        assertEquals("team", byId.getValue("nfl").participantKind)
    }

    @Test
    fun `leagues without a name or order still parse - without an id they are dropped`() {
        val leagues = JSONArray(listOf(JSONObject("""{"id":"xfl"}"""), JSONObject("""{"name":"No id"}""")))
        val parsed = SportsApiParser.parseLeaguesPage(JSONObject().put("leagues", leagues).toString())!!
        val xfl = parsed.single()
        assertEquals("XFL", xfl.name)
        assertEquals("XFL", xfl.shortName)
        assertEquals(Int.MAX_VALUE, xfl.order)
        assertNull(xfl.sport)
        assertNull("a non-object body is not a leagues page", SportsApiParser.parseLeaguesPage("[]"))
    }

    @Test
    fun `a team without a name falls back to its short name - neither drops the team only`() {
        val short = SportsFixtures.parsed(event("evt_t", home = JSONObject().put("shortName", "Eagles"), away = team("nfl.lar", "Los Angeles Rams", "Rams", "LAR")))
        assertEquals("Eagles", short.home!!.name)
        val nameless = SportsFixtures.parsed(event("evt_n", home = JSONObject().put("id", "nfl.x"), away = team("nfl.lar", "Los Angeles Rams", "Rams", "LAR")))
        assertNull(nameless.home)
        assertEquals("Los Angeles Rams", nameless.away!!.name)
    }
}
