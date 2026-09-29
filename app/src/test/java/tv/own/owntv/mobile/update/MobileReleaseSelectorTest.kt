package tv.own.owntv.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.brand.MobileBrand

/** Each brand finds only its own releases in the shared repository, and only its own APK. */
class MobileReleaseSelectorTest {

    private val repo = "owner/GoatTV-Mobile"
    private val goat = MobileBrand.GOAT.releaseTrack
    private val allAccess = MobileBrand.ALLACCESS.releaseTrack

    private fun asset(tag: String, name: String, size: Long = 50_000_000) =
        GitHubAsset(name, "https://github.com/$repo/releases/download/$tag/$name", size)

    private fun release(tag: String, vararg assets: GitHubAsset, draft: Boolean = false, prerelease: Boolean = false) =
        GitHubRelease(tag, draft, prerelease, notes = "notes for $tag", assets = assets.toList())

    private fun goatRelease(version: String) =
        release("goattv-mobile-v$version", asset("goattv-mobile-v$version", "GoatTV-Mobile-v$version.apk"))

    private fun allAccessRelease(version: String) =
        release("allaccess-mobile-v$version", asset("allaccess-mobile-v$version", "AllAccess-Mobile-v$version.apk"))

    @Test
    fun versionCodes() {
        assertEquals(10000, MobileVersion.codeOf("1.0.0"))
        assertEquals(900, MobileVersion.codeOf("0.9.0"))
        assertEquals(10203, MobileVersion.codeOf("1.2.3"))
        assertNull(MobileVersion.codeOf("1.0"))
        assertNull(MobileVersion.codeOf("1.0.0-beta"))
        assertNull(MobileVersion.codeOf("1.100.0"))
        assertNull(MobileVersion.codeOf("0.0.0"))
    }

    @Test
    fun everyBrandHasItsOwnTrack() {
        val expected = mapOf(
            MobileBrand.GOAT to ("goattv-mobile-v" to "GoatTV-Mobile-v1.0.0.apk"),
            MobileBrand.ALLACCESS to ("allaccess-mobile-v" to "AllAccess-Mobile-v1.0.0.apk"),
            MobileBrand.FIVESTAR to ("5star-ultra-mobile-v" to "5Star-Ultra-Mobile-v1.0.0.apk"),
            MobileBrand.SUPREME to ("supreme-tv-mobile-v" to "Supreme-TV-Mobile-v1.0.0.apk"),
        )
        for ((brand, pair) in expected) {
            assertEquals(pair.first, brand.releaseTrack.tagPrefix)
            assertEquals(pair.second, brand.releaseTrack.assetName("1.0.0", x86 = false))
        }
        // No track reads another's tags.
        for (a in MobileBrand.entries) for (b in MobileBrand.entries) if (a != b) {
            assertNull("${a.name} read ${b.name}'s tag", a.releaseTrack.versionOf(b.releaseTrack.tagFor("1.0.0")))
        }
    }

    @Test
    fun picksNewestOnOwnTrack_ignoringNewerReleasesOfOtherBrands() {
        val releases = listOf(allAccessRelease("3.0.0"), goatRelease("1.0.0"), goatRelease("1.1.0"), allAccessRelease("2.0.0"))
        val choice = MobileReleaseSelector.choose(releases, goat, repo, installedVersionCode = 10000, x86 = false)
        choice as MobileReleaseChoice.Newer
        assertEquals("1.1.0", choice.release.version)
        assertEquals(10100, choice.release.versionCode)
        assertEquals("GoatTV-Mobile-v1.1.0.apk", choice.release.assetName)
    }

    @Test
    fun anotherBrandsReleaseIsNeverOffered() {
        val releases = listOf(allAccessRelease("9.0.0"))
        assertEquals(
            MobileReleaseChoice.UpToDate,
            MobileReleaseSelector.choose(releases, goat, repo, installedVersionCode = 10000, x86 = false),
        )
    }

    @Test
    fun anotherBrandsApkInsideThisBrandsTagIsRefused() {
        // A mis-uploaded release: GoatTV's tag carrying AllAccess's APK.
        val tag = "goattv-mobile-v1.1.0"
        val releases = listOf(release(tag, asset(tag, "AllAccess-Mobile-v1.1.0.apk")))
        assertEquals(
            MobileReleaseChoice.NoCompatibleApk,
            MobileReleaseSelector.choose(releases, goat, repo, installedVersionCode = 10000, x86 = false),
        )
    }

    @Test
    fun televisionAndUpstreamApksAreRefused() {
        val tag = "goattv-mobile-v1.1.0"
        for (name in listOf("OwnTV-v1.1.0.apk", "OwnTV-Mobile-v1.1.0.apk", "GoatTV-v1.1.0.apk")) {
            val releases = listOf(release(tag, asset(tag, name)))
            assertEquals(
                name,
                MobileReleaseChoice.NoCompatibleApk,
                MobileReleaseSelector.choose(releases, goat, repo, installedVersionCode = 10000, x86 = false),
            )
        }
        // A plain v* tag (the OwnTV scheme) is on no Mobile track at all.
        val upstream = listOf(release("v2.0.0", asset("v2.0.0", "GoatTV-Mobile-v2.0.0.apk")))
        assertEquals(
            MobileReleaseChoice.UpToDate,
            MobileReleaseSelector.choose(upstream, goat, repo, installedVersionCode = 10000, x86 = false),
        )
    }

    @Test
    fun rightNameFromAnotherRepositoryIsRefused() {
        val tag = "goattv-mobile-v1.1.0"
        val name = "GoatTV-Mobile-v1.1.0.apk"
        val foreign = GitHubAsset(name, "https://github.com/someone/else/releases/download/$tag/$name", 1)
        assertEquals(
            MobileReleaseChoice.NoCompatibleApk,
            MobileReleaseSelector.choose(listOf(release(tag, foreign)), goat, repo, installedVersionCode = 10000, x86 = false),
        )
    }

    @Test
    fun draftsAndPrereleasesAreNeverOffered() {
        val tag = "goattv-mobile-v2.0.0"
        val a = asset(tag, "GoatTV-Mobile-v2.0.0.apk")
        for (r in listOf(release(tag, a, draft = true), release(tag, a, prerelease = true))) {
            assertEquals(
                MobileReleaseChoice.UpToDate,
                MobileReleaseSelector.choose(listOf(r), goat, repo, installedVersionCode = 10000, x86 = false),
            )
        }
    }

    @Test
    fun sameOrOlderIsUpToDate() {
        val releases = listOf(goatRelease("1.0.0"), goatRelease("0.9.0"))
        assertEquals(
            MobileReleaseChoice.UpToDate,
            MobileReleaseSelector.choose(releases, goat, repo, installedVersionCode = 10000, x86 = false),
        )
    }

    @Test
    fun upgradeFromPredecessor() {
        val choice = MobileReleaseSelector.choose(listOf(goatRelease("1.0.0")), goat, repo, installedVersionCode = 900, x86 = false)
        assertTrue(choice is MobileReleaseChoice.Newer)
    }

    @Test
    fun malformedTagsAreIgnored() {
        val tags = listOf("goattv-mobile-v1.2", "goattv-mobile-v1.2.3-beta", "goattv-mobile-vX", "goattv-mobile-v")
        for (tag in tags) {
            assertNull(tag, goat.versionOf(tag))
        }
        assertEquals("1.2.3", goat.versionOf("goattv-mobile-v1.2.3"))
        assertNull(allAccess.versionOf("goattv-mobile-v1.2.3"))
    }

    @Test
    fun x86DevicesTakeOnlyTheX86Asset() {
        val tag = "goattv-mobile-v1.1.0"
        val releases = listOf(release(tag, asset(tag, "GoatTV-Mobile-v1.1.0.apk")))
        assertEquals(
            MobileReleaseChoice.NoCompatibleApk,
            MobileReleaseSelector.choose(releases, goat, repo, installedVersionCode = 10000, x86 = true),
        )
    }
}
