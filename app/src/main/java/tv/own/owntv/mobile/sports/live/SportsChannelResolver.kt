package tv.own.owntv.mobile.sports.live

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * Resolves backend-matched Sports channels ([SportsChannelRef]) to the customer's LOCAL channels.
 * Ported from the GoatTV Android TV app (tag v4.6.1); the contract is unchanged:
 *
 *  1. Only the eligible playlist [SportsChannelLookup.eligibleSource] picks, and only if it is an
 *     Xtream source — M3U and Stalker channel ids are not the provider stream ids the backend matched.
 *  2. Lookup is exactly `ChannelEntity(sourceId = eligible, remoteId = ref.remoteId)` — unique per
 *     playlist in core's schema, so one reference can never match two local channels.
 *  3. Identity is verified before acceptance: if the ref has an EPG id, the local EPG id must agree;
 *     otherwise the normalized channel names must agree. Any mismatch → the channel is rejected.
 *  4. There is NO fallback: no global name search, no other playlist, no backend URL. Playback keeps
 *     being built locally from the customer's own authenticated playlist (the returned [ChannelEntity]).
 *
 * Backend ranking is preserved (primary feeds first, accepted 4K/UHD alternates after). Separate
 * feeds of one network (HD, FHD, 4K) are separate references with their own remote ids and stay
 * separate; only two references that land on the SAME local channel collapse, to the higher-ranked.
 *
 * Phase 2A of the Mobile Sports work: identity and playlist isolation only. Adult and hidden-channel
 * rules are applied on top of this before anything can be watched (Phase 2B).
 */
class SportsChannelResolver(private val lookup: SportsChannelLookup) {

    suspend fun resolve(refs: List<SportsChannelRef>): SportsChannelResolution {
        if (refs.isEmpty()) return SportsChannelResolution(emptyList(), emptyList())
        val source = lookup.eligibleSource()
        if (source == null || source.type != SourceType.XTREAM) {
            return SportsChannelResolution(emptyList(), refs.map { RejectedSportsChannel(it, RejectReason.NO_ELIGIBLE_SOURCE) })
        }
        val accepted = ArrayList<ResolvedSportsChannel>()
        val rejected = ArrayList<RejectedSportsChannel>()
        val seenLocal = HashSet<Long>()
        for (ref in refs) {
            val remoteId = ref.remoteId.trim()
            if (remoteId.isEmpty()) { rejected += RejectedSportsChannel(ref, RejectReason.NOT_FOUND); continue }
            val local = lookup.findByRemote(source.id, remoteId)
            when {
                local == null -> rejected += RejectedSportsChannel(ref, RejectReason.NOT_FOUND)
                local.sourceId != source.id -> rejected += RejectedSportsChannel(ref, RejectReason.WRONG_SOURCE)
                // The DAO looks up by remote id; a row that came back with another one is not this channel.
                local.remoteId?.trim() != remoteId -> rejected += RejectedSportsChannel(ref, RejectReason.NOT_FOUND)
                !identityAgrees(ref, local) -> rejected += RejectedSportsChannel(ref, RejectReason.IDENTITY_MISMATCH)
                // The same local channel claimed twice: the higher-ranked claim keeps it.
                !seenLocal.add(local.id) -> rejected += RejectedSportsChannel(ref, RejectReason.DUPLICATE)
                else -> accepted += ResolvedSportsChannel(ref, local)
            }
        }
        return SportsChannelResolution(accepted, rejected)
    }

    companion object {
        /** EPG agreement when the backend knows the EPG id; otherwise normalized name agreement. */
        fun identityAgrees(ref: SportsChannelRef, local: ChannelEntity): Boolean {
            val refEpg = ref.epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
            return if (refEpg != null) {
                val localEpg = local.epgChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
                refEpg.equals(localEpg, ignoreCase = true)
            } else {
                val a = normalizeChannelName(ref.name)
                a.isNotEmpty() && a == normalizeChannelName(local.name)
            }
        }

        private val COUNTRY_PREFIX = Regex("""^\s*(?:\[\s*[a-z]{2,3}\s*]|\(\s*[a-z]{2,3}\s*\)|[a-z]{2,3})\s*[:|]\s*""", RegexOption.IGNORE_CASE)
        private val QUALITY = Regex("""\b(?:hd|fhd|sd|hevc|h26[45]|1080[pi]?|720p|60\s*fps|50\s*fps)\b""", RegexOption.IGNORE_CASE)

        /**
         * Conservative name normalization for the no-EPG case: case, punctuation, a leading country/
         * region tag ("US:", "USA |") and plain quality tags are ignored. 4K/UHD is kept, so a 4K feed
         * never "agrees" with the normal feed.
         */
        fun normalizeChannelName(raw: String): String {
            var s = raw
            repeat(2) { s = s.replace(COUNTRY_PREFIX, "") }
            return s.replace(QUALITY, " ")
                .lowercase()
                .replace("&", " and ")
                .replace("+", " plus ")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        }
    }
}

/** Where the resolver reads local state from (a seam for tests). */
interface SportsChannelLookup {
    /**
     * The active profile's ONE eligible Xtream playlist ([SportsPlaylistSelection]), or null when it
     * has none, or several and no default — nothing resolves then.
     */
    suspend fun eligibleSource(): SourceEntity?

    suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity?
}

data class ResolvedSportsChannel(val ref: SportsChannelRef, val channel: ChannelEntity)

data class RejectedSportsChannel(val ref: SportsChannelRef, val reason: RejectReason)

enum class RejectReason {
    /** No eligible playlist: none, not Xtream, or several without a default. */
    NO_ELIGIBLE_SOURCE,

    /** Not in the eligible playlist (missing, deleted, or no remote id). */
    NOT_FOUND,

    /** A row from another playlist. */
    WRONG_SOURCE,

    /** Found, but its EPG id / name says it is a different channel. */
    IDENTITY_MISMATCH,

    /** A local channel a higher-ranked reference already resolved to. */
    DUPLICATE,
}

data class SportsChannelResolution(
    val accepted: List<ResolvedSportsChannel>,
    val rejected: List<RejectedSportsChannel>,
)
