package tv.own.owntv.mobile.sports.live

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

/**
 * [SportsAccessData] on core 1.0.67, read exactly where Live TV reads it:
 *  - the active profile and its playlists: [activeProfileSources] (what `LiveViewModel`/`LiveTuner` use);
 *  - the default playlist: [SettingsRepository.defaultSourceId];
 *  - kids or not: [ProfileDao.getById];
 *  - hidden channels, hidden categories and renames: [CustomizationStore.observe] for Live TV;
 *  - categories hidden for the profile: [AdultCategoryClassifier.hiddenCategoryIds] over the profile's
 *    Live categories — `LiveViewModel`'s own rule;
 *  - the play-time adult check: [AdultCategoryClassifier.allows] — `LiveTuner.open`'s own check.
 * Every call reads afresh; nothing is cached, so changes apply on the next decision.
 */
class MobileSportsAccessData(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
) : SportsAccessData {

    override suspend fun snapshot(): SportsAccessSnapshot? {
        val profile = activeProfileSources(settings, sourceDao).first()
        if (profile.profileId < 0) return null
        val playlist = SportsPlaylistSelection.choose(SportsPlaylistSelection.eligible(profile), settings.defaultSourceId.first())
        val kids = profileDao.getById(profile.profileId)?.isKids == true
        val custom = customize.observe(profile.profileId, MediaType.LIVE).first()
        val categories = categoryDao.observe(profile.liveSourceIds.ifEmpty { listOf(-1L) }, MediaType.LIVE).first()
        return SportsAccessSnapshot(
            profileId = profile.profileId,
            playlist = playlist,
            rules = SportsVisibilityRules(
                hiddenChannelKeys = custom.hiddenItems.keys,
                hiddenCategoryIds = AdultCategoryClassifier.hiddenCategoryIds(categories, custom.hiddenCategories, kids),
                renames = custom.itemNames,
            ),
        )
    }

    override suspend fun activeProfileId(): Long = settings.activeProfileId.first()

    override suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity? =
        channelDao.findByRemote(sourceId, remoteId)

    override suspend fun adultAllowed(profileId: Long, categoryId: Long?): Boolean =
        AdultCategoryClassifier.allows(profileId, categoryId, profileDao, categoryDao)
}
