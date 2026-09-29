package tv.own.owntv.mobile.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh

/**
 * The playlist auto-refresh the phone never ran: which playlists are due, when checking happens at
 * all, and that each playlist is decided on its own.
 */
class PlaylistAutoRefresherTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    private fun due(refresh: PlaylistRefresh, ageMs: Long?, coldStart: Boolean) =
        isPlaylistRefreshDue(refresh, ageMs?.let { now - it }, now, coldStart)

    @Test
    fun off_isNeverDue() {
        assertFalse(due(PlaylistRefresh.OFF, 365 * day, coldStart = true))
        assertFalse("not even a playlist that never synced", due(PlaylistRefresh.OFF, null, coldStart = true))
    }

    @Test
    fun onStartup_isDueOnlyOnTheColdStart() {
        val startup = PlaylistRefresh(PlaylistAutoRefresh.STARTUP)
        assertTrue(due(startup, 5 * 60_000L, coldStart = true))
        assertFalse("a resume is not a start", due(startup, 30 * day, coldStart = false))
    }

    @Test
    fun sixHours_isDueAtSixHoursAndNotBefore() {
        val six = PlaylistRefresh(PlaylistAutoRefresh.HOURS_6)
        assertFalse(due(six, 6 * hour - 60_000L, coldStart = false))
        assertTrue(due(six, 6 * hour, coldStart = false))
        assertTrue("checked on the cold start too", due(six, 7 * hour, coldStart = true))
        assertFalse("a fresh sync is not redone on a cold start", due(six, hour, coldStart = true))
    }

    @Test
    fun twelveHours_isDueAtTwelveHours() {
        val twelve = PlaylistRefresh(PlaylistAutoRefresh.HOURS_12)
        assertFalse(due(twelve, 11 * hour, coldStart = false))
        assertTrue(due(twelve, 12 * hour, coldStart = false))
    }

    @Test
    fun everyNDays_usesTheDayCount() {
        val threeDays = PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 3)
        assertFalse(due(threeDays, 2 * day, coldStart = false))
        assertTrue(due(threeDays, 3 * day, coldStart = false))
        assertTrue("an old 24h selection still means a day", due(PlaylistRefresh.parse("HOURS_24"), day, coldStart = false))
        assertFalse(due(PlaylistRefresh.parse("HOURS_24"), day - 60_000L, coldStart = false))
    }

    @Test
    fun aPlaylistThatNeverSynced_isDueOnAnyInterval() {
        assertTrue(due(PlaylistRefresh(PlaylistAutoRefresh.HOURS_6), null, coldStart = false))
        assertTrue(due(PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 30), null, coldStart = false))
    }

    @Test
    fun gate_firstForegroundIsTheColdStart_thenResumesAreThrottled() {
        var clock = 10_000L
        val gate = AutoRefreshGate { clock }
        assertEquals(AutoRefreshGate.Kind.COLD_START, gate.claim())
        assertNull("the activity start right after the cold start", gate.claim())
        clock += AutoRefreshGate.RESUME_THROTTLE_MS - 1
        assertNull(gate.claim())
        clock += 1
        assertEquals(AutoRefreshGate.Kind.RESUME, gate.claim())
        clock += 5_000L
        assertNull("rotation, a quick app switch", gate.claim())
        clock += AutoRefreshGate.RESUME_THROTTLE_MS
        assertEquals(AutoRefreshGate.Kind.RESUME, gate.claim())
    }

    @Test
    fun onlyTheDuePlaylistIsSynced_eachDecidedOnItsOwn() {
        val stale = source(1, lastSyncAt = now - 7 * hour)
        val fresh = source(2, lastSyncAt = now - hour)
        val modes = mapOf(1L to PlaylistRefresh(PlaylistAutoRefresh.HOURS_6), 2L to PlaylistRefresh(PlaylistAutoRefresh.HOURS_6))
        val enqueued = mutableListOf<Long>()
        val refresher = refresher(listOf(stale, fresh), modes, enqueued, clock = { 0L })

        refresher.onForeground()

        assertEquals(listOf(1L), enqueued)
    }

    @Test
    fun anAutomaticSyncNeverRestartsOrDoublesOneAlreadyQueued() {
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, PlaylistAutoRefresher.POLICY)
        assertEquals("auto_refresh", PlaylistAutoRefresher.REASON)
    }

    @Test
    fun resumesInsideTheThrottleDoNotEvenCheck() {
        var checks = 0
        var clock = 0L
        val refresher = PlaylistAutoRefresher(
            dueSources = { _, _ -> checks++; emptyList() },
            enqueue = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            wallClock = { now },
            elapsedClock = { clock },
        )
        refresher.onForeground()
        repeat(5) { clock += 1_000L; refresher.onForeground() }
        assertEquals(1, checks)
        clock += AutoRefreshGate.RESUME_THROTTLE_MS
        refresher.onForeground()
        assertEquals(2, checks)
    }

    @Test
    fun startupPlaylist_syncsOnTheColdStartButNotOnLaterResumes() {
        val startup = source(1, lastSyncAt = now - 10 * day)
        val modes = mapOf(1L to PlaylistRefresh(PlaylistAutoRefresh.STARTUP))
        val enqueued = mutableListOf<Long>()
        var clock = 0L
        val refresher = refresher(listOf(startup), modes, enqueued, clock = { clock })

        refresher.onForeground()
        clock += 10 * AutoRefreshGate.RESUME_THROTTLE_MS
        refresher.onForeground()

        assertEquals(listOf(1L), enqueued)
    }

    @Test
    fun aFailedCheckDoesNotBreakTheNextOne() {
        var clock = 0L
        var fail = true
        val enqueued = mutableListOf<Long>()
        val refresher = PlaylistAutoRefresher(
            dueSources = { _, _ -> if (fail) error("database closed") else listOf(source(3, null)) },
            enqueue = { enqueued += it.id },
            scope = CoroutineScope(Dispatchers.Unconfined),
            wallClock = { now },
            elapsedClock = { clock },
        )
        refresher.onForeground()
        fail = false
        clock += AutoRefreshGate.RESUME_THROTTLE_MS
        refresher.onForeground()
        assertEquals(listOf(3L), enqueued)
    }

    private fun refresher(
        sources: List<SourceEntity>,
        modes: Map<Long, PlaylistRefresh>,
        enqueued: MutableList<Long>,
        clock: () -> Long,
    ) = PlaylistAutoRefresher(
        dueSources = { coldStart, nowMs ->
            sources.filter { isPlaylistRefreshDue(modes[it.id] ?: PlaylistRefresh.OFF, it.lastSyncAt, nowMs, coldStart) }
        },
        enqueue = { enqueued += it.id },
        scope = CoroutineScope(Dispatchers.Unconfined),
        wallClock = { now },
        elapsedClock = clock,
    )

    private fun source(id: Long, lastSyncAt: Long?) =
        SourceEntity(id = id, name = "Playlist $id", type = SourceType.XTREAM, url = "http://example.invalid", lastSyncAt = lastSyncAt)
}
