package tv.own.owntv.mobile.sports.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.mobile.sports.live.SportsPlaylistSelection.Choice

/**
 * Which playlist Sports matches may come from: the active profile's own, Live-enabled, Xtream; its
 * default first, else its only one, else none. Synthetic playlists only.
 */
class SportsPlaylistSelectionTest {

    private fun source(id: Long, type: SourceType = SourceType.XTREAM) =
        SourceEntity(id = id, name = "Synthetic $id", type = type, url = "user-entered")

    private fun pick(profileSources: List<SourceEntity>, live: Set<Long> = profileSources.map { it.id }.toSet(), default: Long = -1L) =
        SportsPlaylistSelection.choose(SportsPlaylistSelection.eligible(profileSources, live), default)

    @Test
    fun theDefaultPlaylist_whenItIsEligible() {
        val a = source(1); val b = source(2)
        assertEquals(Choice.Selected(b), pick(listOf(a, b), default = 2))
    }

    @Test
    fun theOnlyEligiblePlaylist_whenThereIsNoDefault() {
        val only = source(4)
        assertEquals(Choice.Selected(only), pick(listOf(only)))
        assertEquals("M3U and Stalker beside it do not count", Choice.Selected(only), pick(listOf(source(1, SourceType.M3U), only, source(5, SourceType.STALKER))))
    }

    @Test
    fun severalEligiblePlaylists_andNoDefault_neverGuess() {
        assertEquals(Choice.Ambiguous(2), pick(listOf(source(1), source(2))))
        assertEquals(Choice.Ambiguous(3), pick(listOf(source(1), source(2), source(3)), default = 99))
    }

    @Test
    fun m3uStalkerAndLocalBackup_areNeverEligible() {
        val notXtream = listOf(source(1, SourceType.M3U), source(2, SourceType.STALKER), source(3, SourceType.LOCAL_BACKUP))
        assertTrue(SportsPlaylistSelection.eligible(notXtream, setOf(1, 2, 3)).isEmpty())
        assertEquals(Choice.None, pick(notXtream))
        assertEquals("a non-Xtream default is not followed", Choice.None, pick(notXtream, default = 1))
    }

    @Test
    fun aNonXtreamDefault_fallsBackToTheOnlyXtreamPlaylist() {
        val xtream = source(2)
        assertEquals(Choice.Selected(xtream), pick(listOf(source(1, SourceType.M3U), xtream), default = 1))
    }

    @Test
    fun aPlaylistWithLiveTvSwitchedOff_isNotEligible() {
        val vodOnly = source(1); val live = source(2)
        assertEquals(Choice.Selected(live), pick(listOf(vodOnly, live), live = setOf(2)))
        assertEquals("a Live-off default is passed over", Choice.Selected(live), pick(listOf(vodOnly, live), live = setOf(2), default = 1))
        assertEquals(Choice.None, pick(listOf(vodOnly), live = emptySet()))
    }

    @Test
    fun activeProfileIsolation_anotherProfilesPlaylistIsNeverChosen() {
        // The selection only ever sees the ACTIVE profile's playlists (core's activeProfileSources); a
        // default that points at another profile's playlist id is simply not among them.
        val mine = source(4)
        val anotherProfilesId = 77L
        assertEquals(Choice.Selected(mine), pick(listOf(mine), default = anotherProfilesId))
        assertEquals(Choice.Ambiguous(2), pick(listOf(mine, source(5)), default = anotherProfilesId))
        assertEquals(Choice.None, pick(emptyList(), default = anotherProfilesId))
    }

    @Test
    fun aDeletedDefault_isPassedOver() {
        val a = source(1)
        assertEquals(Choice.Selected(a), pick(listOf(a), default = 3))
    }

    @Test
    fun theEligibleListKeepsProfileOrder() {
        val list = SportsPlaylistSelection.eligible(listOf(source(3), source(1, SourceType.M3U), source(2)), setOf(1, 2, 3))
        assertEquals(listOf(3L, 2L), list.map { it.id })
    }
}
