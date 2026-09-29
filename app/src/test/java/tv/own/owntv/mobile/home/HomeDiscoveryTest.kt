package tv.own.owntv.mobile.home

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.home.HomeDiscovery
import tv.own.owntv.mobile.ui.screens.home.firstPage
import kotlin.random.Random

/**
 * Home's Recently Added and Top 10 rails, against a catalogue the size of the one on the phone.
 *
 * The rails used to be built from the alphabetically first 1,000 titles, so a new release named past
 * "A" could never appear. These tests hold the rails to the date-added and rating orderings instead.
 */
class HomeDiscoveryTest {

    private data class Title(val id: Long, val name: String, val rating: Double?, val addedAt: Long?)

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    /** 28,000 titles, alphabetical by id, added over the last three years, rated 1–9 or unrated. */
    private fun catalogue(size: Int = 28_000, seed: Int = 7): MutableList<Title> {
        val random = Random(seed)
        return MutableList(size) { i ->
            Title(
                id = i + 1L,
                name = "Title %05d".format(i),
                rating = if (random.nextInt(5) == 0) null else 1.0 + random.nextInt(80) / 10.0,
                addedAt = now - (60L + random.nextInt(1_000)) * day,
            )
        }
    }

    /** The three bounded reads the DAO provides, done in memory the way SQLite orders them. */
    private class Reads(private val titles: List<Title>, private val watchedIds: List<Long> = emptyList()) {
        val limitsAsked = mutableListOf<Int>()
        suspend fun newest(limit: Int) = titles
            .sortedWith(compareByDescending<Title> { it.addedAt ?: Long.MIN_VALUE }.thenByDescending { it.id })
            .take(limit).also { limitsAsked += limit }
        suspend fun topRated(limit: Int) = titles
            .sortedWith(compareByDescending<Title> { it.rating ?: Double.NEGATIVE_INFINITY }.thenBy { it.name })
            .take(limit).also { limitsAsked += limit }
        suspend fun watched(limit: Int) = watchedIds.mapNotNull { id -> titles.firstOrNull { it.id == id } }
            .take(limit).also { limitsAsked += limit }
    }

    private fun rails(reads: Reads) = runBlocking {
        HomeDiscovery.rails(
            newest = reads::newest, topRated = reads::topRated, watched = reads::watched,
            now = now, id = { it.id }, rating = { it.rating }, addedAt = { it.addedAt },
        )
    }

    @Test
    fun anAlphabeticallyLateNewRelease_isRecentlyAdded() {
        val titles = catalogue()
        titles += Title(99_001, "Zootopia 3", rating = 7.9, addedAt = now - day)

        val rails = rails(Reads(titles))

        assertEquals("Zootopia 3", rails.recent.first().name)
        // What the old query saw: the first 1,000 by name. The new release was never among them.
        assertFalse(titles.sortedBy { it.name }.take(HomeDiscovery.POOL).any { it.name == "Zootopia 3" })
    }

    @Test
    fun anAlphabeticallyLateNewRelease_reachesTop10WhenItsScoreEarnsIt() {
        val titles = catalogue()
        titles += Title(99_002, "Zulu Dawn Returns", rating = 9.9, addedAt = now - day)

        val rails = rails(Reads(titles))

        assertEquals("Zulu Dawn Returns", rails.top.first().name)
    }

    @Test
    fun recentlyAdded_isTheNewest20_newestFirst() {
        val titles = catalogue()
        val rails = rails(Reads(titles))
        // The query's order: addedAt DESC, then the newer row first on a tie.
        val expected = titles.sortedWith(compareByDescending<Title> { it.addedAt }.thenByDescending { it.id })
            .take(HomeDiscovery.RECENT_COUNT).map { it.id }
        assertEquals(HomeDiscovery.RECENT_COUNT, rails.recent.size)
        assertEquals(expected, rails.recent.map { it.id })
    }

    @Test
    fun anExistingTitleWhoseDateChanged_movesIntoRecentlyAdded() {
        val titles = catalogue()
        val old = titles.first { it.name == "Title 27000" }
        assertFalse(rails(Reads(titles)).recent.any { it.id == old.id })

        titles[titles.indexOf(old)] = old.copy(addedAt = now - 60_000L)

        assertEquals(old.id, rails(Reads(titles)).recent.first().id)
    }

    @Test
    fun anExistingTitleWhoseRatingRose_entersTop10() {
        val titles = catalogue()
        val quiet = titles.first { it.name == "Title 26500" }
        assertFalse(rails(Reads(titles)).top.any { it.id == quiet.id })

        titles[titles.indexOf(quiet)] = quiet.copy(rating = 10.0)

        assertTrue(rails(Reads(titles)).top.any { it.id == quiet.id })
    }

    @Test
    fun aRecentlyWatchedTitle_keepsItsEngagementBonus() {
        val titles = catalogue()
        // Old, and rated no higher than the best of the catalogue: only watching it lifts it to the top.
        val gem = Title(99_003, "Quiet Gem", rating = 8.9, addedAt = now - 900 * day)
        titles += gem
        assertEquals(gem.id, rails(Reads(titles, watchedIds = listOf(gem.id))).top.first().id)
    }

    @Test
    fun candidatePool_isTheDeduplicatedUnion() {
        val a = Title(1, "A", 9.0, now)
        val b = Title(2, "B", 8.0, now - day)
        val c = Title(3, "C", 5.0, now - 90 * day)
        val pool = HomeDiscovery.candidatePool(listOf(a, b), listOf(a, c), listOf(c, b)) { it.id }
        assertEquals(listOf(1L, 2L, 3L), pool.map { it.id })
    }

    @Test
    fun readsAreBounded_andTheCatalogueIsNeverLoaded() {
        val reads = Reads(catalogue(size = 40_000))
        rails(reads)
        assertEquals(
            listOf(HomeDiscovery.POOL, HomeDiscovery.WATCHED_POOL, HomeDiscovery.POOL),
            reads.limitsAsked,
        )
        assertTrue(reads.limitsAsked.sum() <= 2 * HomeDiscovery.POOL + HomeDiscovery.WATCHED_POOL)
    }

    @Test
    fun top10Scoring_isUnchanged() {
        // The ranking as it shipped before this change, verbatim, against many random pools.
        fun previous(candidates: List<Title>, watchedIds: Set<Long>): List<Long> = candidates.map { m ->
            val rating = m.rating ?: 0.0
            val ratingScore = if (rating > 0.0) rating * 1.2 else 6.0
            val addedAt = m.addedAt ?: 0L
            val ageDays = if (addedAt > 0L) (now - addedAt).coerceAtLeast(0L) / 86_400_000.0 else 30.0
            val freshnessScore = maxOf(0.0, 4.0 * (1.0 - (ageDays / 30.0)))
            val engagementScore = if (m.id in watchedIds) 3.0 else 0.0
            Triple(m, ratingScore + freshnessScore + engagementScore, addedAt)
        }
            .sortedWith(compareByDescending<Triple<Title, Double, Long>> { it.second }.thenByDescending { it.third }.thenByDescending { it.first.id })
            .take(10).map { it.first.id }

        repeat(50) { seed ->
            val random = Random(seed)
            val pool = List(500) { i ->
                Title(i + 1L, "T$i",
                    rating = if (random.nextInt(4) == 0) null else random.nextInt(100) / 10.0,
                    addedAt = if (random.nextInt(10) == 0) null else now - random.nextLong(0, 60 * day))
            }
            val watched = pool.shuffled(random).take(20).map { it.id }.toSet()
            val ranked = HomeDiscovery.rankTop(pool, watched, now, { it.id }, { it.rating }, { it.addedAt }).map { it.id }
            assertEquals(previous(pool, watched), ranked)
        }
    }

    @Test
    fun firstPage_isOneBoundedLoadInTheQueryOrder() = runBlocking {
        val source = object : PagingSource<Int, Title>() {
            var asked = -1
            override fun getRefreshKey(state: PagingState<Int, Title>): Int? = null
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Title> {
                asked = params.loadSize
                return LoadResult.Page(catalogue(size = 5_000).take(params.loadSize), prevKey = null, nextKey = null)
            }
        }
        val page = source.firstPage(HomeDiscovery.POOL)
        assertEquals(HomeDiscovery.POOL, source.asked)
        assertEquals(HomeDiscovery.POOL, page.size)
    }

    @Test
    fun firstPage_aFailedReadThrows_soTheRailsOnScreenAreKept() = runBlocking {
        val failing = object : PagingSource<Int, Title>() {
            override fun getRefreshKey(state: PagingState<Int, Title>): Int? = null
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Title> =
                LoadResult.Error(IllegalStateException("closed"))
        }
        val outcome = runCatching { failing.firstPage(10) }
        assertTrue("an empty page would be published as empty rails", outcome.isFailure)
    }

    @Test
    fun aFailedRead_failsTheWholeRebuild_notOneRail() {
        var reads = 0
        val outcome = runCatching {
            runBlocking {
                HomeDiscovery.rails<Title>(
                    newest = { reads++; listOf(Title(1, "A", 8.0, now)) },
                    topRated = { error("database closed mid-sync") },
                    watched = { reads++; emptyList() },
                    now = now, id = { it.id }, rating = { it.rating }, addedAt = { it.addedAt },
                )
            }
        }
        assertTrue(outcome.isFailure)
    }
}
