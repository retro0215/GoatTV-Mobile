package tv.own.owntv.mobile.sports.live

import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.ActiveProfileSources

/**
 * Which one of the active profile's playlists Sports channel matches may come from (pure, unit-tested).
 *
 * Eligible: a playlist of the ACTIVE profile only, enabled for Live TV, and Xtream (the backend matched
 * provider stream ids, which only an Xtream playlist shares). Then:
 *  1. the profile's default playlist, when it is eligible;
 *  2. otherwise its only eligible playlist;
 *  3. otherwise nothing — several eligible playlists and no default is never resolved by guessing.
 *
 * A default that points elsewhere (a removed playlist, another profile's, an M3U or Stalker one, one
 * with Live TV switched off) is not eligible and is passed over, never followed.
 *
 * Read from core by [MobileSportsAccessData], in the same snapshot as the profile's visibility rules.
 */
object SportsPlaylistSelection {

    sealed interface Choice {
        data class Selected(val source: SourceEntity) : Choice

        /** The profile has no eligible playlist. */
        data object None : Choice

        /** Several eligible playlists and no eligible default: matches are not offered. */
        data class Ambiguous(val eligibleCount: Int) : Choice
    }

    fun eligible(profile: ActiveProfileSources): List<SourceEntity> =
        eligible(profile.sources, profile.liveSourceIds.toSet())

    fun eligible(profileSources: List<SourceEntity>, liveSourceIds: Set<Long>): List<SourceEntity> =
        profileSources.filter { it.type == SourceType.XTREAM && it.id in liveSourceIds }

    fun choose(eligible: List<SourceEntity>, defaultSourceId: Long): Choice {
        if (defaultSourceId > 0) eligible.firstOrNull { it.id == defaultSourceId }?.let { return Choice.Selected(it) }
        return when (eligible.size) {
            0 -> Choice.None
            1 -> Choice.Selected(eligible.single())
            else -> Choice.Ambiguous(eligible.size)
        }
    }
}
