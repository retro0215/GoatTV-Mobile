package tv.own.owntv.mobile.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.mobile.ui.screens.sports.SportsSection
import tv.own.owntv.mobile.ui.screens.sports.SportsTap
import tv.own.owntv.mobile.ui.screens.sports.assembleSportsSections
import tv.own.owntv.mobile.ui.screens.sports.matchSportsCategories
import tv.own.owntv.mobile.ui.screens.sports.matchSportsSection
import tv.own.owntv.mobile.ui.screens.sports.sportsTapAction

/**
 * The television's Sports matcher, ported unchanged. The first five cases are the television's own
 * tests (SportsViewModelTest at 6dedd3d0); the rest pin the precedence it relies on.
 */
class SportsModelTest {

    // --- The television's tests -------------------------------------------------------------------

    @Test fun ncaaf() = assertEquals(SportsSection.NCAAF, matchSportsSection("USA | NCAAF"))

    @Test fun nfl() = assertEquals(SportsSection.NFL, matchSportsSection("USA | NFL"))

    @Test fun nba() = assertEquals(SportsSection.NBA, matchSportsSection("USA | NBA"))

    @Test fun mlb() = assertEquals(SportsSection.MLB, matchSportsSection("USA | MLB"))

    @Test fun wnba() = assertEquals(SportsSection.WNBA, matchSportsSection("USA | WNBA"))

    // --- Every section ----------------------------------------------------------------------------

    @Test
    fun everySectionHasAName() {
        assertEquals(SportsSection.NHL, matchSportsSection("USA | NHL"))
        assertEquals(SportsSection.NCAAB, matchSportsSection("USA | NCAAB"))
        assertEquals(SportsSection.SOCCER, matchSportsSection("UK | Premier League"))
        assertEquals(SportsSection.BOXING_MMA, matchSportsSection("UFC Fight Pass"))
        assertEquals(SportsSection.PPV_SPECIAL, matchSportsSection("PPV Events"))
    }

    @Test
    fun packagesCountAsTheirLeague() {
        assertEquals(SportsSection.NFL, matchSportsSection("NFL Sunday Ticket"))
        assertEquals(SportsSection.NFL, matchSportsSection("Sunday Ticket"))
        assertEquals(SportsSection.NBA, matchSportsSection("League Pass"))
        assertEquals(SportsSection.MLB, matchSportsSection("Extra Innings"))
        assertEquals(SportsSection.NCAAF, matchSportsSection("College Football"))
        assertEquals(SportsSection.NCAAB, matchSportsSection("College Basketball"))
        assertEquals(SportsSection.PPV_SPECIAL, matchSportsSection("Special Events"))
        assertEquals(SportsSection.PPV_SPECIAL, matchSportsSection("Pay Per View"))
    }

    // --- Precedence -------------------------------------------------------------------------------

    @Test
    fun collegeFootballIsNeverNfl() {
        assertEquals(SportsSection.NCAAF, matchSportsSection("NCAA Football"))
        // Named as both: the college rule is checked first.
        assertEquals(SportsSection.NCAAF, matchSportsSection("NFL / NCAAF Game Day"))
        // NFL's own exclusion, for an NCAA name that is not a football one.
        assertNull(matchSportsSection("NCAA NFL Draft"))
    }

    @Test
    fun wnbaIsNeverNba() {
        assertEquals(SportsSection.WNBA, matchSportsSection("NBA | WNBA"))
        assertEquals(SportsSection.WNBA, matchSportsSection("W.N.B.A."))
    }

    @Test
    fun collegeBasketballIsNeverNba() {
        assertEquals(SportsSection.NCAAB, matchSportsSection("NCAA Basketball"))
        assertEquals(SportsSection.NCAAB, matchSportsSection("NBA & NCAAB"))
    }

    @Test
    fun footballIsSoccerOnlyWhenNothingSaysAmerican() {
        assertEquals(SportsSection.SOCCER, matchSportsSection("Football"))
        assertEquals(SportsSection.SOCCER, matchSportsSection("World Football"))
        assertNull(matchSportsSection("American Football"))
        assertEquals(SportsSection.NFL, matchSportsSection("NFL Football"))
        assertEquals(SportsSection.NCAAF, matchSportsSection("CFB Football"))
    }

    // --- Normalisation ----------------------------------------------------------------------------

    @Test
    fun caseAndPunctuationDoNotMatter() {
        assertEquals(SportsSection.NHL, matchSportsSection("usa|nhl-hd"))
        assertEquals(SportsSection.SOCCER, matchSportsSection("premier-league"))
        assertEquals(SportsSection.BOXING_MMA, matchSportsSection("  boxing  "))
    }

    @Test
    fun notSportIsNothing() {
        assertNull(matchSportsSection("USA | News"))
        assertNull(matchSportsSection("Movies 24/7"))
        assertNull(matchSportsSection(""))
    }

    // --- Categories to sections -------------------------------------------------------------------

    private fun cat(id: Long, name: String) = CategoryEntity(id = id, sourceId = 1, mediaType = MediaType.LIVE, name = name)

    private fun ch(id: Long, name: String = "Channel $id") =
        ChannelEntity(id = id, sourceId = 1, name = name, streamUrl = "http://example.invalid/$id")

    @Test
    fun onlySportCategoriesAreKept_inProviderOrder() {
        val matched = matchSportsCategories(listOf(cat(1, "News"), cat(2, "NBA"), cat(3, "Kids"), cat(4, "NFL")))
        assertEquals(listOf(2L, 4L), matched.map { it.category.id })
        assertEquals(listOf(SportsSection.NBA, SportsSection.NFL), matched.map { it.section })
    }

    @Test
    fun sectionsFollowTheTelevisionsOrder_notTheProviders() {
        val sections = assembleSportsSections(
            listOf(
                SportsSection.PPV_SPECIAL to listOf(ch(1)),
                SportsSection.SOCCER to listOf(ch(2)),
                SportsSection.NFL to listOf(ch(3)),
                SportsSection.NHL to listOf(ch(4)),
            ),
        )
        assertEquals(
            listOf(SportsSection.NFL, SportsSection.NHL, SportsSection.SOCCER, SportsSection.PPV_SPECIAL),
            sections.map { it.section },
        )
    }

    @Test
    fun emptySectionsAreNotShown() {
        val sections = assembleSportsSections(listOf(SportsSection.NFL to emptyList(), SportsSection.NBA to listOf(ch(1))))
        assertEquals(listOf(SportsSection.NBA), sections.map { it.section })
        assertEquals(emptyList<Any>(), assembleSportsSections(emptyList()))
    }

    @Test
    fun aChannelInTwoCategoriesOfOneSectionIsShownOnce() {
        val sections = assembleSportsSections(
            listOf(
                SportsSection.NFL to listOf(ch(1), ch(2)),
                SportsSection.NFL to listOf(ch(2), ch(3)),
            ),
        )
        assertEquals(listOf(1L, 2L, 3L), sections.single().channels.map { it.id })
    }

    @Test
    fun twoCategoriesOfOneSectionAreJoinedInOrder() {
        val sections = assembleSportsSections(
            listOf(
                SportsSection.NCAAF to listOf(ch(10), ch(11)),
                SportsSection.NFL to listOf(ch(1)),
                SportsSection.NCAAF to listOf(ch(12)),
            ),
        )
        assertEquals(listOf(10L, 11L, 12L), sections.first { it.section == SportsSection.NCAAF }.channels.map { it.id })
    }

    @Test
    fun providerMatchupNamesAreShownAsTheyAre() {
        val name = "NCAAF 03: Team A vs Team B 7:30PM"
        val sections = assembleSportsSections(listOf(SportsSection.NCAAF to listOf(ch(1, name))))
        assertEquals(name, sections.single().channels.single().name)
    }

    // --- Taps -------------------------------------------------------------------------------------

    @Test
    fun firstTapPreviews_secondTapOnTheSameCardGoesFullScreen() {
        assertEquals(SportsTap.PREVIEW, sportsTapAction(tappedId = 5, selectedId = null))
        assertEquals(SportsTap.PREVIEW, sportsTapAction(tappedId = 5, selectedId = 4))
        assertEquals(SportsTap.FULLSCREEN, sportsTapAction(tappedId = 5, selectedId = 5))
    }
}
