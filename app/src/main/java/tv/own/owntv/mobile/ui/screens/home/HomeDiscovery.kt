package tv.own.owntv.mobile.ui.screens.home

import androidx.paging.PagingSource
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

/**
 * Home's four discovery rails: Recently Added and Top 10, for films and for shows.
 *
 * The candidates used to be `searchList("", …, 1000)` — which is `ORDER BY name ASC LIMIT 1000`, the
 * first thousand titles of the alphabet. On a 28,000-film catalogue that is roughly everything up to
 * "A", so a new release called anything later could never reach either rail, however many times Home
 * was rebuilt. They now come from core's own date-added and rating orderings, a bounded page of each:
 *
 * - **Recently Added** is the newest [RECENT_COUNT] by `addedAt`, straight from the database's order.
 * - **Top 10** ranks the union of the newest [POOL] (the freshness bonus only ever goes to recent
 *   titles), the top-rated [POOL] (where the rating score comes from) and the recently watched
 *   [WATCHED_POOL] (the engagement bonus), with the scoring unchanged.
 *
 * At most [POOL] × 2 + [WATCHED_POOL] rows per kind are held while ranking, never the catalogue.
 */
internal object HomeDiscovery {
    const val POOL = 1000
    const val WATCHED_POOL = 50
    const val RECENT_COUNT = 20
    const val TOP_COUNT = 10

    data class Rails<T>(val recent: List<T>, val top: List<T>)

    suspend fun movies(dao: MovieDao, profileId: Long, sourceIds: List<Long>, now: Long): Rails<MovieEntity> = rails(
        newest = { dao.pagingAllDateAdded(sourceIds).firstPage(it) },
        topRated = { dao.pagingAllRating(sourceIds).firstPage(it) },
        watched = { dao.recentlyWatchedSnapshot(profileId, sourceIds, it) },
        now = now, id = { it.id }, rating = { it.rating }, addedAt = { it.addedAt },
    )

    suspend fun series(dao: SeriesDao, profileId: Long, sourceIds: List<Long>, now: Long): Rails<SeriesEntity> = rails(
        newest = { dao.pagingAllDateAdded(sourceIds).firstPage(it) },
        topRated = { dao.pagingAllRating(sourceIds).firstPage(it) },
        watched = { dao.recentlyWatchedSnapshot(profileId, sourceIds, it) },
        now = now, id = { it.id }, rating = { it.rating }, addedAt = { it.addedAt },
    )

    /**
     * Both rails from three bounded reads: [newest] in `addedAt` order, [topRated] in rating order and
     * [watched], each asked for no more than its pool size.
     */
    suspend fun <T> rails(
        newest: suspend (limit: Int) -> List<T>,
        topRated: suspend (limit: Int) -> List<T>,
        watched: suspend (limit: Int) -> List<T>,
        now: Long,
        id: (T) -> Long,
        rating: (T) -> Double?,
        addedAt: (T) -> Long?,
    ): Rails<T> {
        val newestPage = newest(POOL)
        val watchedPage = watched(WATCHED_POOL)
        return Rails(
            recent = newestPage.take(RECENT_COUNT),
            top = rankTop(
                candidatePool(newestPage, topRated(POOL), watchedPage, id),
                watchedPage.map(id).toSet(), now, id, rating, addedAt,
            ),
        )
    }

    /** Every candidate once, whichever list it came from. */
    fun <T> candidatePool(newest: List<T>, topRated: List<T>, watched: List<T>, id: (T) -> Long): List<T> =
        (newest + topRated + watched).distinctBy(id)

    /**
     * The Top 10 score, as it has always been: rating (×1.2, or 6.0 when the provider gives none), plus
     * up to 4 for being added within the last 30 days, plus 3 for having been watched. Ties go to the
     * newer title, then the higher id.
     */
    fun <T> rankTop(
        candidates: List<T>,
        watchedIds: Set<Long>,
        now: Long,
        id: (T) -> Long,
        rating: (T) -> Double?,
        addedAt: (T) -> Long?,
    ): List<T> = candidates.map { item ->
        val r = rating(item) ?: 0.0
        val ratingScore = if (r > 0.0) r * 1.2 else 6.0

        val added = addedAt(item) ?: 0L
        val ageDays = if (added > 0L) (now - added).coerceAtLeast(0L) / 86_400_000.0 else 30.0
        val freshnessScore = maxOf(0.0, 4.0 * (1.0 - (ageDays / 30.0)))

        val engagementScore = if (id(item) in watchedIds) 3.0 else 0.0

        Triple(item, ratingScore + freshnessScore + engagementScore, added)
    }
        .sortedWith(
            compareByDescending<Triple<T, Double, Long>> { it.second }
                .thenByDescending { it.third }
                .thenByDescending { id(it.first) },
        )
        .take(TOP_COUNT)
        .map { it.first }
}

/**
 * The first [limit] rows of a query core only exposes for paging, without a pager: one refresh load
 * is a single `LIMIT`/`OFFSET 0` read in the query's own order.
 *
 * A load that fails throws rather than answering "no rows": an empty answer would be published as
 * empty rails, and a failed read must leave the rails that were already there.
 */
internal suspend fun <T : Any> PagingSource<Int, T>.firstPage(limit: Int): List<T> =
    when (val result = load(PagingSource.LoadParams.Refresh(key = null, loadSize = limit, placeholdersEnabled = false))) {
        is PagingSource.LoadResult.Page -> result.data
        is PagingSource.LoadResult.Error -> throw result.throwable
        is PagingSource.LoadResult.Invalid -> throw IllegalStateException("catalogue changed during the read")
    }
