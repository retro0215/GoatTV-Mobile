package tv.own.owntv.mobile.sports.live

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * Channel identity: the TV app's resolver tests (v4.6.1 `SportsChannelResolverTest`, adapted to the
 * phone's [SportsChannelLookup.eligibleSource]) plus the cases Phase 2A adds. Synthetic channels only.
 */
class SportsChannelResolverTest {

    private val xtream = SourceEntity(id = 7, name = "Synthetic Xtream", type = SourceType.XTREAM, url = "user-entered")
    private val other = SourceEntity(id = 9, name = "Other Xtream", type = SourceType.XTREAM, url = "user-entered")

    private fun channel(id: Long, sourceId: Long, remoteId: String, name: String, epg: String?) =
        ChannelEntity(id = id, sourceId = sourceId, name = name, streamUrl = "built-locally", epgChannelId = epg, remoteId = remoteId)

    private fun ref(remoteId: String, name: String, epg: String?, channelId: String = "sch_$remoteId") =
        SportsChannelRef(channelId, remoteId, epg, name, "USA | Sports", null, "espn", 95, "network_exact")

    /** Local channel table keyed by (sourceId, remoteId) — unique, as core's index is; records every lookup. */
    private class FakeLookup(var eligible: SourceEntity?, channels: List<ChannelEntity>) : SportsChannelLookup {
        val byKey = channels.associateBy { it.sourceId to it.remoteId!! }
        val lookups = mutableListOf<Pair<Long, String>>()
        override suspend fun eligibleSource() = eligible
        override suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity? {
            lookups += sourceId to remoteId
            return byKey[sourceId to remoteId]
        }
    }

    private val locals = listOf(
        channel(1, 7, "52001", "US: ESPN", "ESPN.us"),
        channel(2, 7, "52002", "US: ESPN FHD", "ESPN.us"),
        channel(3, 7, "52099", "ESPN 4K UHD", "ESPN4K.us"),
        channel(4, 7, "61000", "US: NBC Sports Bay Area", null),
        channel(5, 7, "70000", "US: Fox Sports 1 HD", "FoxSports1.us"),
        channel(6, 9, "52001", "US: ESPN", "ESPN.us"), // same remote id on ANOTHER playlist
        channel(8, 7, "52003", "US: ESPN HD", null),
    )

    private suspend fun resolve(vararg refs: SportsChannelRef, eligible: SourceEntity? = xtream, lookup: FakeLookup = FakeLookup(eligible, locals)) =
        SportsChannelResolver(lookup).resolve(refs.toList())

    // ------------------------------------------------------------------ ported from the TV app

    @Test
    fun exactRemoteIdOnTheEligiblePlaylist_preservesBackendOrder() = runBlocking {
        val lookup = FakeLookup(xtream, locals)
        val r = resolve(ref("52001", "US: ESPN", "ESPN.us"), ref("52002", "US: ESPN FHD", "ESPN.us"), ref("52099", "ESPN 4K UHD", "ESPN4K.us"), lookup = lookup)
        assertEquals(listOf(1L, 2L, 3L), r.accepted.map { it.channel.id })
        assertTrue(r.rejected.isEmpty())
        assertTrue("only the eligible playlist is ever queried", lookup.lookups.all { it.first == 7L })
    }

    @Test
    fun epgAgreement_requiredWhenTheBackendSendsAnEpgId_caseInsensitive() = runBlocking {
        assertEquals(listOf(1L), resolve(ref("52001", "Different Name", "espn.US")).accepted.map { it.channel.id })
        val wrongEpg = resolve(ref("52001", "US: ESPN", "ESPN2.us"))
        assertTrue(wrongEpg.accepted.isEmpty())
        assertEquals(RejectReason.IDENTITY_MISMATCH, wrongEpg.rejected.single().reason)
        val localHasNoEpg = resolve(ref("61000", "US: NBC Sports Bay Area", "NBCSportsBayArea.us"))
        assertEquals("an EPG id the local channel lacks is not agreement", RejectReason.IDENTITY_MISMATCH, localHasNoEpg.rejected.single().reason)
    }

    @Test
    fun withoutAnEpgId_theNormalizedNamesMustAgree() = runBlocking {
        assertEquals(listOf(4L), resolve(ref("61000", "NBC Sports Bay Area HD", null)).accepted.map { it.channel.id })
        assertEquals(RejectReason.IDENTITY_MISMATCH, resolve(ref("61000", "NBC Sports California", null)).rejected.single().reason)
        assertTrue(SportsChannelResolver.identityAgrees(ref("x", "USA | Fox Sports 1", null), channel(9, 7, "x", "US: Fox Sports 1 HD", null)))
        assertFalse("4K never agrees with the normal feed", SportsChannelResolver.identityAgrees(ref("x", "ESPN 4K", null), channel(9, 7, "x", "US: ESPN", null)))
    }

    @Test
    fun aRemoteIdMissingOnThePlaylist_isRejected_neverANameSearch() = runBlocking {
        val lookup = FakeLookup(xtream, locals)
        val r = resolve(ref("99999", "US: ESPN", "ESPN.us"), lookup = lookup)
        assertTrue(r.accepted.isEmpty())
        assertEquals(RejectReason.NOT_FOUND, r.rejected.single().reason)
        assertEquals("exactly one lookup, by (eligible playlist, remote id)", listOf(7L to "99999"), lookup.lookups)
    }

    @Test
    fun aRowFromAnotherPlaylist_isRejected_evenIfTheLookupReturnedIt() = runBlocking {
        val leaky = object : SportsChannelLookup {
            override suspend fun eligibleSource() = xtream
            override suspend fun findByRemote(sourceId: Long, remoteId: String) = locals.first { it.sourceId == 9L }
        }
        assertEquals(RejectReason.WRONG_SOURCE, SportsChannelResolver(leaky).resolve(listOf(ref("52001", "US: ESPN", "ESPN.us"))).rejected.single().reason)
    }

    @Test
    fun m3uStalkerAndLocalBackup_areNotEligible_nothingIsLookedUp() = runBlocking {
        for (type in listOf(SourceType.M3U, SourceType.STALKER, SourceType.LOCAL_BACKUP)) {
            val lookup = FakeLookup(xtream.copy(type = type), locals)
            val r = resolve(ref("52001", "US: ESPN", "ESPN.us"), lookup = lookup)
            assertTrue(type.name, r.accepted.isEmpty())
            assertEquals(RejectReason.NO_ELIGIBLE_SOURCE, r.rejected.single().reason)
            assertTrue(lookup.lookups.isEmpty())
        }
        assertEquals(RejectReason.NO_ELIGIBLE_SOURCE, resolve(ref("52001", "US: ESPN", "ESPN.us"), eligible = null).rejected.single().reason)
    }

    @Test
    fun theEligiblePlaylistDecides_theSameRemoteIdElsewhereIsNeverUsed() = runBlocking {
        val r = resolve(ref("52001", "US: ESPN", "ESPN.us"), ref("52002", "US: ESPN FHD", "ESPN.us"), eligible = other)
        assertEquals(listOf(6L), r.accepted.map { it.channel.id })
        assertEquals(RejectReason.NOT_FOUND, r.rejected.single().reason)
    }

    // ------------------------------------------------------------------ Phase 2A additions

    @Test
    fun twoReferencesClaimingOneLocalChannel_theHigherRankedKeepsIt_theOtherIsRecorded() = runBlocking {
        val r = resolve(ref("52001", "US: ESPN", "ESPN.us", "sch_a"), ref("52001", "US: ESPN", "ESPN.us", "sch_b"))
        assertEquals(listOf("sch_a"), r.accepted.map { it.ref.channelId })
        assertEquals(listOf("sch_b" to RejectReason.DUPLICATE), r.rejected.map { it.ref.channelId to it.reason })
    }

    @Test
    fun conflictingClaimsOnOneRemoteId_onlyTheOneWhoseIdentityAgreesWins() = runBlocking {
        val r = resolve(ref("52001", "US: ESPN 2", "ESPN2.us", "sch_wrong"), ref("52001", "US: ESPN", "ESPN.us", "sch_right"))
        assertEquals(listOf("sch_right"), r.accepted.map { it.ref.channelId })
        assertEquals(RejectReason.IDENTITY_MISMATCH, r.rejected.single { it.ref.channelId == "sch_wrong" }.reason)
    }

    @Test
    fun hdFhdAnd4kFeeds_staySeparateChannels_inBackendOrder() = runBlocking {
        val r = resolve(
            ref("52003", "ESPN HD", null), // name-only identity: "HD" is ignored, so it agrees with "US: ESPN HD"
            ref("52002", "US: ESPN FHD", "ESPN.us"),
            ref("52099", "ESPN 4K UHD", "ESPN4K.us"),
        )
        assertEquals(listOf(8L, 2L, 3L), r.accepted.map { it.channel.id })
        assertEquals(3, r.accepted.map { it.channel.id }.toSet().size)
        assertFalse(
            "a 4K reference never lands on the HD channel by name",
            SportsChannelResolver.identityAgrees(ref("52003", "ESPN 4K", null), locals.first { it.id == 8L }),
        )
    }

    @Test
    fun aRowWhoseRemoteIdDiffers_isNotThisChannel() = runBlocking {
        val odd = object : SportsChannelLookup {
            override suspend fun eligibleSource() = xtream
            override suspend fun findByRemote(sourceId: Long, remoteId: String) = channel(1, 7, "52001-other", "US: ESPN", "ESPN.us")
        }
        assertEquals(RejectReason.NOT_FOUND, SportsChannelResolver(odd).resolve(listOf(ref("52001", "US: ESPN", "ESPN.us"))).rejected.single().reason)
    }

    @Test
    fun missingDeletedOrBlankChannels_areRejected_othersStillResolve() = runBlocking {
        val afterSync = FakeLookup(xtream, locals.filterNot { it.id == 2L }) // the FHD feed left the playlist
        val r = resolve(ref("52001", "US: ESPN", "ESPN.us"), ref("52002", "US: ESPN FHD", "ESPN.us"), ref("   ", "US: ESPN", "ESPN.us"), lookup = afterSync)
        assertEquals(listOf(1L), r.accepted.map { it.channel.id })
        assertEquals(listOf(RejectReason.NOT_FOUND, RejectReason.NOT_FOUND), r.rejected.map { it.reason })
        assertEquals("a blank remote id is never looked up", listOf("52001", "52002"), afterSync.lookups.map { it.second })
    }

    @Test
    fun noReferences_noLookups() = runBlocking {
        val lookup = FakeLookup(xtream, locals)
        val r = SportsChannelResolver(lookup).resolve(emptyList())
        assertTrue(r.accepted.isEmpty() && r.rejected.isEmpty())
        assertTrue(lookup.lookups.isEmpty())
    }

    @Test
    fun theResolvedChannel_isTheLocalRow_neverAnythingFromTheBackend() = runBlocking {
        val r = resolve(ref("52001", "Backend Display Name", "ESPN.us"))
        val local = r.accepted.single().channel
        assertEquals("US: ESPN", local.name)
        assertEquals("built-locally", local.streamUrl)
    }

    @Test
    fun nameNormalization() {
        assertEquals("espn", SportsChannelResolver.normalizeChannelName("US: ESPN FHD"))
        assertEquals("espn", SportsChannelResolver.normalizeChannelName("[US] | ESPN HD"))
        assertEquals("espn 4k", SportsChannelResolver.normalizeChannelName("US: ESPN 4K"))
        assertEquals("sky sports plus", SportsChannelResolver.normalizeChannelName("UK: Sky Sports+"))
        assertEquals("a and e", SportsChannelResolver.normalizeChannelName("A&E 1080p"))
        assertEquals("", SportsChannelResolver.normalizeChannelName("HD"))
        assertFalse("a name that normalizes to nothing never agrees", SportsChannelResolver.identityAgrees(ref("x", "HD", null), channel(9, 7, "x", "FHD", null)))
    }
}
