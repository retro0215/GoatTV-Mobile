package tv.own.owntv.mobile.update

/**
 * One brand's update track inside the shared GoatTV-Mobile repository.
 *
 * All four Mobile brands publish to ONE repository, so "the latest release" means nothing here: it
 * could be any brand's. Each app instead reads only the releases whose tag carries its own prefix
 * (`goattv-mobile-v1.0.0`) and only the asset carrying its own name (`GoatTV-Mobile-v1.0.0.apk`).
 * Neither match can cross brands: no prefix is the start of another, and the version after it must
 * be a plain X.Y.Z.
 */
data class MobileReleaseTrack(val tagPrefix: String, val assetBaseName: String) {

    /** The version this tag names on this track, or null when it is another track's (or junk). */
    fun versionOf(tag: String): String? {
        if (!tag.startsWith(tagPrefix)) return null
        val version = tag.substring(tagPrefix.length)
        return version.takeIf { MobileVersion.codeOf(it) != null }
    }

    fun tagFor(version: String): String = "$tagPrefix$version"

    /** The one asset this track's release may carry for the device's ABI. */
    fun assetName(version: String, x86: Boolean): String =
        if (x86) "$assetBaseName-x86_64-v$version.apk" else "$assetBaseName-v$version.apk"
}

/** The production numbering, identical to app/build.gradle.kts: major×10000 + minor×100 + patch. */
object MobileVersion {
    private val PATTERN = Regex("""^(\d{1,4})\.(\d{1,2})\.(\d{1,2})$""")

    /** 1.0.0 → 10000, 0.9.0 → 900; null for anything that is not a plain X.Y.Z. */
    fun codeOf(version: String): Int? {
        val (major, minor, patch) = PATTERN.matchEntire(version)?.destructured ?: return null
        return (major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()).takeIf { it > 0 }
    }
}

/** A release as GitHub's list endpoint describes it — only the fields the choice depends on. */
data class GitHubRelease(
    val tag: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val notes: String,
    val assets: List<GitHubAsset>,
)

data class GitHubAsset(val name: String, val downloadUrl: String, val size: Long)

/** What the updater will download: this track's newest release and its one matching asset. */
data class MobileRelease(
    val version: String,
    val versionCode: Int,
    val notes: String,
    val assetName: String,
    val apkUrl: String,
    val apkSize: Long,
)

sealed interface MobileReleaseChoice {
    data object UpToDate : MobileReleaseChoice

    /** A newer release exists on this track, but it carries no APK for this device's ABI. */
    data object NoCompatibleApk : MobileReleaseChoice

    data class Newer(val release: MobileRelease) : MobileReleaseChoice
}

object MobileReleaseSelector {

    /**
     * The newest published release on [track], if it is newer than [installedVersionCode].
     *
     * Drafts and pre-releases are never offered. Of the rest, only tags on this track count; the
     * newest one must carry exactly [MobileReleaseTrack.assetName], downloadable from this very
     * repository and tag — an asset named right but served from anywhere else is not accepted.
     */
    fun choose(
        releases: List<GitHubRelease>,
        track: MobileReleaseTrack,
        repo: String,
        installedVersionCode: Int,
        x86: Boolean,
    ): MobileReleaseChoice {
        val newest = releases
            .asSequence()
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { release -> track.versionOf(release.tag)?.let { version -> release to version } }
            .maxByOrNull { (_, version) -> MobileVersion.codeOf(version)!! }
            ?: return MobileReleaseChoice.UpToDate
        val (release, version) = newest
        val code = MobileVersion.codeOf(version)!!
        if (code <= installedVersionCode) return MobileReleaseChoice.UpToDate

        val name = track.assetName(version, x86)
        val expectedUrl = "https://github.com/$repo/releases/download/${track.tagFor(version)}/$name"
        // GitHub treats owner/repo case-insensitively; the asset name itself must match exactly.
        val asset = release.assets.firstOrNull {
            it.name == name && it.downloadUrl.equals(expectedUrl, ignoreCase = true) && it.size > 0
        }
            ?: return MobileReleaseChoice.NoCompatibleApk
        return MobileReleaseChoice.Newer(
            MobileRelease(
                version = version,
                versionCode = code,
                notes = release.notes,
                assetName = asset.name,
                apkUrl = asset.downloadUrl,
                apkSize = asset.size,
            ),
        )
    }
}
