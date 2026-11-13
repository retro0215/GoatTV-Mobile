package tv.own.owntv.mobile.sports.live

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

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

/**
 * The production [SportsChannelLookup] on core 1.0.67: the active profile's playlists from
 * [activeProfileSources] (the same view Live TV uses), the default playlist setting, and
 * [ChannelDao.findByRemote] — `WHERE sourceId = ? AND remoteId = ?` against core's unique
 * `(sourceId, remoteId)` index. It never reads another profile's playlists and never searches by name.
 */
class MobileSportsChannelLookup(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
) : SportsChannelLookup {

    override suspend fun eligibleSource(): SourceEntity? {
        val profile = activeProfileSources(settings, sourceDao).first()
        if (profile.profileId < 0) return null
        val choice = SportsPlaylistSelection.choose(SportsPlaylistSelection.eligible(profile), settings.defaultSourceId.first())
        return (choice as? SportsPlaylistSelection.Choice.Selected)?.source
    }

    override suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity? =
        channelDao.findByRemote(sourceId, remoteId)
}
