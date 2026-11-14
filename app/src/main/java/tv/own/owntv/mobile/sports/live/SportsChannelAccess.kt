package tv.own.owntv.mobile.sports.live

import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity

/**
 * Everything one Sports channel decision needs, read at one moment for ONE profile, so the playlist,
 * the parental rules and the hidden lists can never come from two different profiles.
 */
data class SportsAccessSnapshot(
    val profileId: Long,
    /** [SportsPlaylistSelection] for this profile: the one eligible playlist, or why there is none. */
    val playlist: SportsPlaylistSelection.Choice,
    val rules: SportsVisibilityRules,
)

/**
 * The Live TV visibility rules of one profile, as Live TV applies them (see `LiveViewModel` and
 * `LiveTuner` — this reuses their sources, it is not a separate parental-control system):
 *  - [hiddenChannelKeys]: `CustomizeKeys.channel(ch)` entries the profile hid (`SectionCustomizations.hiddenItems`);
 *  - [hiddenCategoryIds]: core's `AdultCategoryClassifier.hiddenCategoryIds` — the profile's hidden
 *    categories, plus every adult category when it is a kids profile;
 *  - [renames]: the profile's channel renames, so a channel reads as it does in Live TV.
 * On top of these, every channel still passes core's `AdultCategoryClassifier.allows` — the check the
 * tuner makes before it plays anything — through [SportsAccessData.adultAllowed].
 */
data class SportsVisibilityRules(
    val hiddenChannelKeys: Set<String>,
    val hiddenCategoryIds: Set<Long>,
    val renames: Map<String, String> = emptyMap(),
)

/** Where [SportsChannelAccess] reads the customer's current state from (a seam for tests). */
interface SportsAccessData {
    /** The active profile's current snapshot, or null with no active profile. */
    suspend fun snapshot(): SportsAccessSnapshot?

    /** The profile active right now — compared after a decision so a switch mid-way discards it. */
    suspend fun activeProfileId(): Long

    suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity?

    /** Core's `AdultCategoryClassifier.allows` for [profileId]: Live TV's play-time adult check. */
    suspend fun adultAllowed(profileId: Long, categoryId: Long?): Boolean
}

/**
 * One channel a customer may be offered for a game: what a screen needs, and nothing else. No remote
 * id, EPG id, match confidence or reason, no stream URL and no credentials — the playable row is only
 * ever handed out by [SportsChannelAccess.revalidate], at the moment of acting on it.
 */
class VisibleSportsChannel internal constructor(
    /** Local channel id, to mark the chosen row; never displayed. */
    val localChannelId: Long,
    /** As Live TV shows it (renames applied). */
    val name: String,
    val networkKey: String?,
    val category: String?,
    val uhd: Boolean,
    /**
     * The playlist's own channel number — the one the player shows as "#n" and lists under each
     * channel — or null when the playlist gives none. Shown only to tell apart two channels that would
     * otherwise read the same; never a remote or EPG id.
     */
    val number: Int? = null,
    /** The backend's stable id for this feed: how [SportsChannelAccess.revalidate] finds it again. */
    internal val backendChannelId: String,
) {
    override fun equals(other: Any?): Boolean =
        other is VisibleSportsChannel && other.localChannelId == localChannelId && other.backendChannelId == backendChannelId &&
            other.name == name && other.networkKey == networkKey && other.category == category && other.uhd == uhd &&
            other.number == number

    override fun hashCode(): Int = 31 * localChannelId.hashCode() + backendChannelId.hashCode()

    override fun toString(): String = "VisibleSportsChannel($name)"
}

/** Why a game has no channel to offer, when it has none. */
enum class SportsAccessIssue {
    NO_PROFILE,
    NO_ELIGIBLE_PLAYLIST,
    /** Several eligible Xtream playlists and no default: the customer has to choose one first. */
    SEVERAL_PLAYLISTS,
    /** The active profile changed while the decision was being made: nothing from it is used. */
    PROFILE_CHANGED,
}

/** [SportsChannelAccess.checkWatch]: play [channel], with [zapChannels] as the list around it — or nothing. */
sealed interface SportsWatchCheck {
    class Allowed(
        /** The chosen feed's local row as it is now. */
        val channel: ChannelEntity,
        /** Every channel this profile may use for the game, in backend rank order (includes [channel]). */
        val zapChannels: List<ChannelEntity>,
        val profileId: Long?,
    ) : SportsWatchCheck {
        // A channel row carries its stream address; nothing that logs this may print it.
        override fun toString(): String = "Allowed(${channel.name}, ${zapChannels.size} channels)"
    }

    data class Rejected(val reason: SportsWatchRejection) : SportsWatchCheck
}

enum class SportsWatchRejection {
    /** Hidden, blocked, gone from the playlist, or no longer listed for the game. */
    NO_LONGER_AVAILABLE,
    /** Another profile is active than the one the channels were offered to. */
    PROFILE_CHANGED,
    NO_PROFILE,
    NO_ELIGIBLE_PLAYLIST,
    SEVERAL_PLAYLISTS,
}

/** [SportsChannelAccess.offerAll]: every game's offer from one snapshot of [profileId]. */
data class SportsChannelOffers(
    val profileId: Long?,
    /** Games that list channels; a game absent here has none to offer. */
    val byEventId: Map<String, SportsChannelOffer>,
    val issue: SportsAccessIssue? = null,
) {
    /** Channels this profile may use for [eventId] (0 when the game lists none or all were filtered). */
    fun countFor(eventId: String): Int = byEventId[eventId]?.channels?.size ?: 0
}

data class SportsChannelOffer(
    /** Allowed channels in backend rank order; empty when none survive. */
    val channels: List<VisibleSportsChannel>,
    val profileId: Long?,
    /** Set when no playlist could be used at all (not when every channel was merely filtered out). */
    val issue: SportsAccessIssue? = null,
    /** How many identity-verified channels the profile's rules removed (hidden / adult). */
    val filteredOut: Int = 0,
) {
    companion object {
        fun none(issue: SportsAccessIssue, profileId: Long? = null) = SportsChannelOffer(emptyList(), profileId, issue)
    }
}

/**
 * Sports channel matches as the customer may see and use them: identity first ([SportsChannelResolver],
 * Phase 2A), then the active profile's Live TV visibility ([SportsChannelVisibility]).
 *
 * Nothing is cached: every call reads the profile, its playlist, its rules and the channel rows afresh,
 * so a profile switch, a playlist removed or refreshed, a changed parental setting or a newly hidden
 * channel is honoured on the very next call. [revalidate] is the call to make immediately before Watch,
 * Preview or Add to Multiview — the screen may have been open for a while.
 */
class SportsChannelAccess(private val data: SportsAccessData) {

    suspend fun offer(event: SportsEvent): SportsChannelOffer = decide(event.channels).offer

    /**
     * The playable local channel for [chosen] right now, or null when it is no longer allowed: hidden,
     * blocked, gone from the playlist, another profile active, or no longer matched for this game. The
     * whole resolution runs again — after a playlist refresh the same feed may come back as a new local
     * row, and that fresh row is what is returned. Never a substitute channel.
     */
    suspend fun revalidate(event: SportsEvent, chosen: VisibleSportsChannel): ChannelEntity? =
        (checkWatch(event, chosen, expectedProfileId = null) as? SportsWatchCheck.Allowed)?.channel

    /**
     * [revalidate] for Watch, from ONE decision: the chosen feed's current local row, and every channel
     * this profile may use for the game right now — fresh rows, backend rank order — as the list the
     * player zaps through. Rejected (and nothing to play) when the feed is no longer allowed or no
     * longer listed for [event], when no playlist can be used, or when the active profile is not
     * [expectedProfileId], the profile the customer saw the offer for: a channel offered to one
     * profile is never played for another, even where that one would also allow it.
     */
    suspend fun checkWatch(event: SportsEvent, chosen: VisibleSportsChannel, expectedProfileId: Long?): SportsWatchCheck {
        val decision = decide(event.channels)
        val offer = decision.offer
        when (offer.issue) {
            null -> Unit
            SportsAccessIssue.NO_PROFILE -> return SportsWatchCheck.Rejected(SportsWatchRejection.NO_PROFILE)
            SportsAccessIssue.NO_ELIGIBLE_PLAYLIST -> return SportsWatchCheck.Rejected(SportsWatchRejection.NO_ELIGIBLE_PLAYLIST)
            SportsAccessIssue.SEVERAL_PLAYLISTS -> return SportsWatchCheck.Rejected(SportsWatchRejection.SEVERAL_PLAYLISTS)
            SportsAccessIssue.PROFILE_CHANGED -> return SportsWatchCheck.Rejected(SportsWatchRejection.PROFILE_CHANGED)
        }
        if (expectedProfileId != null && offer.profileId != expectedProfileId) {
            return SportsWatchCheck.Rejected(SportsWatchRejection.PROFILE_CHANGED)
        }
        val chosenRow = decision.playable.firstOrNull { it.first.backendChannelId == chosen.backendChannelId }?.second
            ?: return SportsWatchCheck.Rejected(SportsWatchRejection.NO_LONGER_AVAILABLE)
        return SportsWatchCheck.Allowed(chosenRow, decision.playable.map { it.second }, offer.profileId)
    }

    /**
     * [offer] for many games at once — a screen of cards — from ONE snapshot of the active profile, so
     * every count on screen answers for the same profile and the same rules. Keyed by event id.
     */
    suspend fun offerAll(events: List<SportsEvent>): SportsChannelOffers {
        val snapshot = data.snapshot() ?: return SportsChannelOffers(null, emptyMap(), SportsAccessIssue.NO_PROFILE)
        val source = when (val p = snapshot.playlist) {
            is SportsPlaylistSelection.Choice.Selected -> p.source
            is SportsPlaylistSelection.Choice.Ambiguous -> return SportsChannelOffers(snapshot.profileId, emptyMap(), SportsAccessIssue.SEVERAL_PLAYLISTS)
            SportsPlaylistSelection.Choice.None -> return SportsChannelOffers(snapshot.profileId, emptyMap(), SportsAccessIssue.NO_ELIGIBLE_PLAYLIST)
        }
        val adult = adultCheck(snapshot.profileId)
        val resolver = SportsChannelResolver(SnapshotLookup(source, data))
        val byEvent = LinkedHashMap<String, SportsChannelOffer>(events.size)
        for (event in events) {
            if (event.channels.isEmpty()) continue
            val resolved = resolver.resolve(event.channels).accepted
            val allowed = SportsChannelVisibility.filter(resolved, snapshot.rules, adult)
            byEvent[event.id] = SportsChannelOffer(allowed.map { it.first }, snapshot.profileId, filteredOut = resolved.size - allowed.size)
        }
        if (data.activeProfileId() != snapshot.profileId) {
            return SportsChannelOffers(snapshot.profileId, emptyMap(), SportsAccessIssue.PROFILE_CHANGED)
        }
        return SportsChannelOffers(snapshot.profileId, byEvent)
    }

    private class Decision(val offer: SportsChannelOffer, val playable: List<Pair<VisibleSportsChannel, ChannelEntity>>)

    private suspend fun decide(refs: List<SportsChannelRef>): Decision {
        val snapshot = data.snapshot() ?: return Decision(SportsChannelOffer.none(SportsAccessIssue.NO_PROFILE), emptyList())
        val source = when (val p = snapshot.playlist) {
            is SportsPlaylistSelection.Choice.Selected -> p.source
            is SportsPlaylistSelection.Choice.Ambiguous ->
                return Decision(SportsChannelOffer.none(SportsAccessIssue.SEVERAL_PLAYLISTS, snapshot.profileId), emptyList())
            SportsPlaylistSelection.Choice.None ->
                return Decision(SportsChannelOffer.none(SportsAccessIssue.NO_ELIGIBLE_PLAYLIST, snapshot.profileId), emptyList())
        }
        val resolved = SportsChannelResolver(SnapshotLookup(source, data)).resolve(refs).accepted
        val allowed = SportsChannelVisibility.filter(resolved, snapshot.rules, adultCheck(snapshot.profileId))
        // A profile switch while this ran: every answer above belongs to the old profile.
        if (data.activeProfileId() != snapshot.profileId) {
            return Decision(SportsChannelOffer.none(SportsAccessIssue.PROFILE_CHANGED, snapshot.profileId), emptyList())
        }
        return Decision(
            SportsChannelOffer(allowed.map { it.first }, snapshot.profileId, filteredOut = resolved.size - allowed.size),
            allowed,
        )
    }

    /** The play-time adult check, asked once per category within one decision. */
    private fun adultCheck(profileId: Long): suspend (Long?) -> Boolean {
        val answers = HashMap<Long?, Boolean>()
        return { categoryId -> answers.getOrPut(categoryId) { data.adultAllowed(profileId, categoryId) } }
    }

    /** The resolver's lookup, pinned to the snapshot's playlist so it cannot drift mid-decision. */
    private class SnapshotLookup(private val source: SourceEntity, private val data: SportsAccessData) : SportsChannelLookup {
        override suspend fun eligibleSource(): SourceEntity = source
        override suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity? = data.findByRemote(sourceId, remoteId)
    }
}

/**
 * The visibility filter applied after identity (pure apart from the adult check it is handed).
 *
 * A channel is dropped when the profile hid it, when its category is hidden for the profile (hidden,
 * or adult on a kids profile), or when the play-time adult check refuses it. Survivors keep the
 * backend's rank order and stay separate feeds; a dropped channel is never replaced by another.
 */
object SportsChannelVisibility {

    suspend fun filter(
        resolved: List<ResolvedSportsChannel>,
        rules: SportsVisibilityRules,
        adultAllowed: suspend (categoryId: Long?) -> Boolean,
    ): List<Pair<VisibleSportsChannel, ChannelEntity>> {
        val out = ArrayList<Pair<VisibleSportsChannel, ChannelEntity>>(resolved.size)
        for (r in resolved) {
            val local = r.channel
            val key = CustomizeKeys.channel(local)
            if (key in rules.hiddenChannelKeys) continue
            if (local.categoryId != null && local.categoryId in rules.hiddenCategoryIds) continue
            if (!adultAllowed(local.categoryId)) continue
            val name = rules.renames[key] ?: local.name
            out += VisibleSportsChannel(
                localChannelId = local.id,
                name = name,
                networkKey = r.ref.networkKey,
                category = r.ref.category?.takeIf { it.isNotBlank() },
                uhd = SportsWatchPresentation.isUhd(local.name) || SportsWatchPresentation.isUhd(r.ref.name),
                number = local.number,
                backendChannelId = r.ref.channelId,
            ) to (if (name == local.name) local else local.copy(name = name))
        }
        return out
    }
}
