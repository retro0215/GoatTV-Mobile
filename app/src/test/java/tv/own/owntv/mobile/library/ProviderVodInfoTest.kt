package tv.own.owntv.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.library.firstPresent
import tv.own.owntv.mobile.ui.screens.library.parseProviderVodInfo

/**
 * What the playlist says about one title, and how the detail page chooses between it, the catalogue
 * row and TMDB.
 *
 * On the device every film's catalogue plot was empty and TMDB refused every lookup, so the provider's
 * own `get_vod_info` answer is the only source of a film's description and trailer. The bodies here
 * have the shape the user's panel returned.
 */
class ProviderVodInfoTest {

    @Test
    fun vodInfo_readsPlotTrailerAndBackdropArray() {
        val info = parseProviderVodInfo(
            """{"info":{"plot":"A thief plans one last job.","youtube_trailer":"KeEHsuZ4Ja4",
               "backdrop_path":["https://img.example/b1.jpg","https://img.example/b2.jpg"],"tmdb_id":"1171145"},
               "movie_data":{"stream_id":28127,"name":"4K Crime 101 (2026)"}}""",
        )!!
        assertEquals("A thief plans one last job.", info.plot)
        assertEquals("KeEHsuZ4Ja4", info.trailer)
        assertEquals("https://img.example/b1.jpg", info.backdropUrl)
    }

    @Test
    fun vodInfo_blankTrailerIsNoTrailer() {
        val info = parseProviderVodInfo("""{"info":{"plot":"A king.","youtube_trailer":"","backdrop_path":[]}}""")!!
        assertEquals("A king.", info.plot)
        assertNull(info.trailer)
        assertNull(info.backdropUrl)
    }

    @Test
    fun vodInfo_nullAndPlaceholderValuesAreNothing() {
        val info = parseProviderVodInfo(
            """{"info":{"plot":null,"description":"null","youtube_trailer":"  ","backdrop_path":"https://img.example/b.jpg"}}""",
        )!!
        assertNull(info.plot)
        assertNull(info.trailer)
        assertEquals("backdrop as a plain string", "https://img.example/b.jpg", info.backdropUrl)
    }

    @Test
    fun vodInfo_plotFromAnotherKeyWhenPlotIsMissing() {
        assertEquals("From description.", parseProviderVodInfo("""{"info":{"description":"From description."}}""")!!.plot)
    }

    @Test
    fun vodInfo_unusableBodiesAreNull() {
        assertNull("panel with no info block", parseProviderVodInfo("""{"info":[],"episodes":{}}"""))
        assertNull(parseProviderVodInfo("""{"user_info":{"auth":0}}"""))
        assertNull(parseProviderVodInfo("<html>502</html>"))
        assertNull(parseProviderVodInfo(""))
    }

    @Test
    fun description_prefersCatalogueThenProviderThenTmdb() {
        assertEquals("row", firstPresent("row", "provider", "tmdb"))
        assertEquals("film with no catalogue plot", "provider", firstPresent(null, "provider", "tmdb"))
        assertEquals("provider", firstPresent("  ", "provider", "tmdb"))
        assertEquals("tmdb", firstPresent(null, null, "tmdb"))
        assertNull("nothing anywhere: no section", firstPresent(null, "", " "))
    }

    @Test
    fun trailer_prefersThePlaylistOverTmdb() {
        assertEquals("KeEHsuZ4Ja4", firstPresent("KeEHsuZ4Ja4", "tmdbTrailer"))
        assertEquals("tmdbTrailer", firstPresent(null, "tmdbTrailer"))
        assertNull(firstPresent(null, null))
    }
}
