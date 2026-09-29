package tv.own.owntv.mobile.sports

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewContent
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewModel
import tv.own.owntv.mobile.ui.screens.sports.SportsInputs
import tv.own.owntv.mobile.ui.screens.sports.SportsPreviewDebounce
import tv.own.owntv.mobile.ui.screens.sports.SportsReturn
import tv.own.owntv.mobile.ui.screens.sports.SportsSection
import tv.own.owntv.mobile.ui.screens.sports.SportsSectionLoader
import tv.own.owntv.mobile.ui.screens.sports.SportsUiState
import tv.own.owntv.mobile.ui.screens.sports.matchSportsCategories
import tv.own.owntv.mobile.ui.screens.sports.sportsReturnAction
import tv.own.owntv.mobile.ui.screens.sports.SportsPageEvent
import tv.own.owntv.mobile.ui.screens.sports.sportsPageEvent
import tv.own.owntv.mobile.ui.screens.sports.toSportsState

/**
 * How Sports reads the database and drives the preview: only the matched categories are read, a sync
 * re-reads them, customizations apply without a re-read, and a burst of taps tunes once.
 */
class SportsPipelineTest {

    private fun cat(id: Long, name: String) = CategoryEntity(id = id, sourceId = 1, mediaType = MediaType.LIVE, name = name)

    private fun ch(id: Long, categoryId: Long, name: String = "Channel $id") =
        ChannelEntity(id = id, sourceId = 1, categoryId = categoryId, name = name, streamUrl = "http://example.invalid/$id")

    /** A fake database: channels per category id, and which categories were read. */
    private class FakeDb(var channels: Map<Long, List<ChannelEntity>>) {
        val read = mutableListOf<Long>()
        val loader = SportsSectionLoader { _, category ->
            read += category.id
            channels[category.id].orEmpty()
        }
    }

    private fun inputs(
        categories: List<CategoryEntity>,
        generation: Int = 0,
        hidden: Set<String> = emptySet(),
        names: Map<String, String> = emptyMap(),
    ) = SportsInputs(
        profileId = 1,
        matched = matchSportsCategories(categories),
        hiddenChannels = hidden,
        channelNames = names,
        generation = generation,
    )

    private val nfl = cat(10, "USA | NFL")
    private val news = cat(20, "USA | News")
    private val nba = cat(30, "USA | NBA")

    @Test
    fun onlyMatchedCategoriesAreRead() = runBlocking<Unit> {
        val db = FakeDb(mapOf(10L to listOf(ch(1, 10)), 20L to listOf(ch(2, 20)), 30L to listOf(ch(3, 30))))
        val sections = db.loader.sections(inputs(listOf(nfl, news, nba)))
        assertEquals(listOf(10L, 30L), db.read)
        assertEquals(listOf(SportsSection.NFL, SportsSection.NBA), sections.map { it.section })
    }

    @Test
    fun aRenameOrAHiddenChannelDoesNotReReadTheDatabase() = runBlocking<Unit> {
        val db = FakeDb(mapOf(10L to listOf(ch(1, 10), ch(2, 10))))
        db.loader.sections(inputs(listOf(nfl)))
        val after = db.loader.sections(
            inputs(listOf(nfl), hidden = setOf("1:Channel 2"), names = mapOf("1:Channel 1" to "My NFL")),
        )
        assertEquals(1, db.loader.reads)
        assertEquals(listOf("My NFL"), after.single().channels.map { it.name })
    }

    @Test
    fun aFinishedSyncReReadsTheCategories_andNewMatchupNamesAppear() = runBlocking<Unit> {
        val db = FakeDb(mapOf(10L to listOf(ch(1, 10, "NFL 01: Team A vs Team B 1:00PM"))))
        db.loader.sections(inputs(listOf(nfl), generation = 0))
        db.channels = mapOf(10L to listOf(ch(1, 10, "NFL 01: Team C vs Team D 4:25PM")))
        val stale = db.loader.sections(inputs(listOf(nfl), generation = 0))
        assertEquals("NFL 01: Team A vs Team B 1:00PM", stale.single().channels.single().name)
        val fresh = db.loader.sections(inputs(listOf(nfl), generation = 1))
        assertEquals("NFL 01: Team C vs Team D 4:25PM", fresh.single().channels.single().name)
        assertEquals(2, db.loader.reads)
    }

    @Test
    fun aNewSportsCategoryIsTheOnlyOneRead() = runBlocking<Unit> {
        val db = FakeDb(mapOf(10L to listOf(ch(1, 10)), 30L to listOf(ch(3, 30))))
        db.loader.sections(inputs(listOf(nfl)))
        db.loader.sections(inputs(listOf(nfl, nba)))
        assertEquals(listOf(10L, 30L), db.read)
    }

    @Test
    fun categoryChangesReachThePage() = runBlocking<Unit> {
        val db = FakeDb(mapOf(10L to listOf(ch(1, 10)), 30L to listOf(ch(3, 30))))
        val source = MutableStateFlow<SportsInputs?>(null)
        val state = source.toSportsState(db.loader).stateIn(this)
        try {
            assertEquals(SportsUiState.Loading, state.value)

            source.value = inputs(listOf(news))
            await(state) { it == SportsUiState.Empty }

            source.value = inputs(listOf(news, nfl))
            val one = await(state) { it is SportsUiState.Ready } as SportsUiState.Ready
            assertEquals(listOf(SportsSection.NFL), one.sections.map { it.section })

            source.value = inputs(listOf(news, nfl, nba))
            val two = await(state) { it is SportsUiState.Ready && it.sections.size == 2 } as SportsUiState.Ready
            assertEquals(listOf(SportsSection.NFL, SportsSection.NBA), two.sections.map { it.section })

            source.value = inputs(listOf(news))
            await(state) { it == SportsUiState.Empty }
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun anUnreadableDatabaseIsSaidSo() = runBlocking<Unit> {
        val broken = SportsSectionLoader { _, _ -> error("disk I/O") }
        val source = MutableStateFlow<SportsInputs?>(inputs(listOf(nfl)))
        val state = source.toSportsState(broken).stateIn(this)
        try {
            await(state) { it == SportsUiState.Failed }
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    private suspend fun await(
        state: kotlinx.coroutines.flow.StateFlow<SportsUiState>,
        predicate: (SportsUiState) -> Boolean,
    ): SportsUiState = withTimeout(2_000) { state.first(predicate) }

    // --- Preview debounce -------------------------------------------------------------------------

    @Test
    fun tapsInQuickSuccessionTuneOnce() = runBlocking<Unit> {
        var starts = 0
        val debounce = SportsPreviewDebounce(this, delayMs = 60) { starts++ }
        repeat(5) {
            debounce.request()
            delay(10)
        }
        delay(150)
        assertEquals(1, starts)
    }

    @Test
    fun leavingDropsAPendingPreview() = runBlocking<Unit> {
        var starts = 0
        val debounce = SportsPreviewDebounce(this, delayMs = 60) { starts++ }
        debounce.request()
        debounce.cancel()
        delay(120)
        assertEquals(0, starts)
    }

    // --- Coming back ------------------------------------------------------------------------------

    @Test
    fun backFromFullScreenPreviewsTheSelectedChannelAgain() {
        // The player's Back stops the stream.
        assertEquals(SportsReturn.PREVIEW_AGAIN, sportsReturnAction(selectedId = 7, playingId = null, hasStream = false))
        assertEquals(SportsReturn.PREVIEW_AGAIN, sportsReturnAction(selectedId = 7, playingId = 7, hasStream = false))
    }

    @Test
    fun theSelectedChannelStillPlayingStaysThePreview() {
        assertEquals(SportsReturn.KEEP_PLAYING, sportsReturnAction(selectedId = 7, playingId = 7, hasStream = true))
    }

    // --- The page's lifecycle ---------------------------------------------------------------------

    @Test
    fun comingBackToThePageRestoresThePreview() {
        // From full screen, from Multiview, from another tab or from another app alike.
        assertEquals(SportsPageEvent.SHOW, sportsPageEvent(resumed = true, toFullscreen = false, changingConfiguration = false))
        assertEquals(SportsPageEvent.SHOW, sportsPageEvent(resumed = true, toFullscreen = true, changingConfiguration = false))
    }

    @Test
    fun leavingThePageStopsThePreview() {
        // Another tab, Open Multiview, or the Android Home button.
        assertEquals(SportsPageEvent.LEAVE, sportsPageEvent(resumed = false, toFullscreen = false, changingConfiguration = false))
    }

    @Test
    fun goingFullScreenHandsTheStreamOnRatherThanStoppingIt() {
        // One stream: the player carries the preview's connection and sound on, so nothing is left
        // underneath it and nothing is reopened.
        assertEquals(SportsPageEvent.NOTHING, sportsPageEvent(resumed = false, toFullscreen = true, changingConfiguration = false))
    }

    @Test
    fun aRotationIsNotLeaving() {
        assertEquals(SportsPageEvent.NOTHING, sportsPageEvent(resumed = false, toFullscreen = false, changingConfiguration = true))
    }

    @Test
    fun switchingChannelsStartsOnlyTheLastOne() = runBlocking<Unit> {
        // A then B then C: one tune, of C — so there is never a moment with two previews sounding.
        val started = mutableListOf<Long>()
        var selected = 0L
        val debounce = SportsPreviewDebounce(this, delayMs = 60) { started += selected }
        for (id in listOf(1L, 2L, 3L)) {
            selected = id
            debounce.request()
            delay(10)
        }
        delay(150)
        assertEquals(listOf(3L), started)
    }

    @Test
    fun someoneElsesStreamIsNotReplaced() {
        assertEquals(SportsReturn.LEAVE_ALONE, sportsReturnAction(selectedId = 7, playingId = 9, hasStream = true))
    }

    // --- Multiview, through the grid's own model --------------------------------------------------

    private fun live(id: Long) = MultiviewContent.Live(ch(id, 10))

    @Test
    fun aSportsChannelIsAddedAndRemovedByItsLiveKey() {
        val added = MultiviewModel().add(live(1)).model
        assertTrue(added.contains("live:1"))
        val slot = added.slotOf(live(1).key)!!
        assertTrue(added.remove(slot).isEmpty)
    }

    @Test
    fun aSportsChannelIsNeverInTheGridTwice() {
        val once = MultiviewModel().add(live(1)).model
        val twice = once.add(live(1))
        assertEquals(1, twice.model.size)
        assertEquals(once.slotOf(live(1).key), twice.slot)
    }

    @Test
    fun aFullGridRefusesTheSixth_andReplacesNothing() {
        val full = (1L..5L).fold(MultiviewModel()) { m, id -> m.add(live(id)).model }
        val refused = full.add(live(6))
        assertEquals(null, refused.slot)
        assertEquals(full, refused.model)
    }
}
