package tv.own.owntv.mobile.sports.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.BuildConfig

/**
 * Every brand asks for its own channel matches, and asks for nothing else. Run once per flavour by the
 * four-brand gate, so each brand's build checks its own value. (Branded-path rules ported from the TV
 * app's v4.6.1 `SportsRealChannelsTest`.)
 */
class SportsChannelBrandTest {

    @Test
    fun eachFlavourSendsItsOwnBrand_neverGoatForEveryone() {
        val expected = mapOf("goat" to "goat", "allaccess" to "allaccess", "fivestar" to "fivestar", "supreme" to "supreme")
        assertEquals(expected.getValue(BuildConfig.FLAVOR_brand), BuildConfig.SPORTS_CHANNEL_BRAND)
        assertEquals(BuildConfig.SPORTS_CHANNEL_BRAND, SportsChannelFeature.BRAND)
        assertTrue(SportsChannelFeature.ENABLED)
        assertTrue(SportsApiClient.homePath(SportsChannelFeature.BRAND).endsWith("?brand=" + BuildConfig.FLAVOR_brand))
    }

    @Test
    fun onlyAPlainBrandIdIsEverUsed() {
        assertEquals("goat", SportsChannelFeature.brandOrNull("goat"))
        assertEquals("supreme", SportsChannelFeature.brandOrNull(" SUPREME "))
        assertNull(SportsChannelFeature.brandOrNull(""))
        assertNull(SportsChannelFeature.brandOrNull(null))
        assertNull(SportsChannelFeature.brandOrNull("goat&user=x"))
        assertNull(SportsChannelFeature.brandOrNull("../goat"))
        assertNull(SportsChannelFeature.brandOrNull("a".repeat(33)))
    }

    @Test
    fun brandedHome_sinceFeed_andSportWindows() {
        assertEquals("/sports/home?brand=fivestar", SportsApiClient.homePath("fivestar"))
        assertEquals("/sports/home", SportsApiClient.homePath(null))
        assertEquals(
            "/sports/events?since=2026-10-03T02%3A00%3A00.000Z&brand=allaccess",
            SportsApiClient.sincePath("2026-10-03T02:00:00.000Z", "allaccess"),
        )
        val now = SportsApiParser.parseIsoUtcMs("2026-10-03T02:00:00Z")!!
        assertEquals(
            "/sports/events?sport=football&from=2026-10-02T08%3A00%3A00Z&to=2026-10-10T02%3A00%3A00Z&brand=goat",
            SportsApiClient.eventsForSportPath(
                "football", now - SportsSlateLogic.HOME_PAST_MS, now + SportsSlateLogic.EXTENDED_WINDOWS.getValue("football"), "goat",
            ),
        )
        assertFalse(SportsApiClient.eventsForSportPath("football", now, now + 1, null).contains("brand"))
        assertEquals("a malformed brand is never sent", "/sports/home", SportsApiClient.homePath("goat&password=x"))
    }

    @Test
    fun onlyTheBrandIdentifierIsSent_noCredentialsServersPlaylistsOrStreamUrls() {
        for (brand in listOf("goat", "allaccess", "fivestar", "supreme")) {
            val paths = listOf(
                SportsApiClient.homePath(brand),
                SportsApiClient.sincePath("c", brand),
                SportsApiClient.eventsForSportPath("soccer", 0, 1, brand),
            )
            for (p in paths) {
                assertFalse(p, Regex("user|pass|token|server|playlist|m3u|stream|http", RegexOption.IGNORE_CASE).containsMatchIn(p))
                assertEquals(p, listOf(brand), Regex("brand=([^&]+)").findAll(p).map { it.groupValues[1] }.toList())
            }
        }
    }
}
