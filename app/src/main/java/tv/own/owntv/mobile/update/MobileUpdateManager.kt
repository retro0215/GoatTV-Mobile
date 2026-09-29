package tv.own.owntv.mobile.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import tv.own.owntv.core.update.UpdateManager
import tv.own.owntv.core.update.UpdateManager.Failure
import tv.own.owntv.core.update.UpdateManager.State
import tv.own.owntv.core.update.UpdateManager.UpdateInfo
import tv.own.owntv.mobile.BuildConfig
import tv.own.owntv.mobile.brand.BrandConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The phone's own updater, replacing core's [UpdateManager] for discovery, download and checking.
 *
 * Core's asks one repository for `releases/latest` and takes its first APK. That is right for the
 * television, which has one app per repository, and wrong here: all four Mobile brands publish to
 * one repository, so "latest" is whichever brand shipped last. This one reads only its own brand's
 * track ([MobileReleaseTrack]) and, before anything reaches the system installer, proves the file is
 * this app's next version:
 *
 *  - the package name is this app's, and this brand's production package;
 *  - it is signed by the same certificate as the installed app;
 *  - its versionCode is higher than the installed one, and its version is the release's;
 *  - it arrived as this brand's exact asset name, complete.
 *
 * So GoatTV can never install AllAccess, a phone can never install the television's APK, and
 * upstream OwnTV's releases are never even asked for.
 *
 * The states and failures are core's own types, so the update sheet and core's strings are reused
 * unchanged; every check failure is shown as core's "damaged download" message and logged precisely.
 */
class MobileUpdateManager(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val repo: String = BuildConfig.MOBILE_RELEASE_REPO
    private val track: MobileReleaseTrack = BrandConfig.currentBrand.releaseTrack

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME

    /** The release behind the current [State.Available], with what the download is checked against. */
    @Volatile
    private var resolved: MobileRelease? = null

    private class CheckHttpException(val code: Int) : IOException()
    private class DownloadHttpException(val code: Int) : IOException()
    private class NoCompatibleApkException : IOException()
    private class InvalidReleaseResponseException(reason: String) : IOException(reason)
    private class EmptyDownloadException : IOException()
    private class NotEnoughSpaceException(val requiredBytes: Long) : IOException()
    private class RejectedApkException(reason: String) : IOException(reason)
    private class InstallException(cause: Throwable) : IOException(cause)

    private fun failureFor(error: Throwable, checking: Boolean): Failure = when (error) {
        is CheckHttpException -> Failure.CheckHttp(error.code)
        is DownloadHttpException -> Failure.DownloadHttp(error.code)
        is NoCompatibleApkException -> Failure.NoCompatibleApk
        is InvalidReleaseResponseException -> Failure.InvalidReleaseResponse
        is EmptyDownloadException -> Failure.EmptyDownload
        is NotEnoughSpaceException -> Failure.NotEnoughSpace(error.requiredBytes)
        is RejectedApkException -> Failure.DamagedDownload
        is InstallException -> Failure.Install
        else -> if (checking) Failure.CheckNetwork else Failure.DownloadNetwork
    }

    /** Lists the repository's releases and keeps this brand's newest; Available / UpToDate / a failure. */
    fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            runCatching {
                if (repo.isBlank()) throw InvalidReleaseResponseException("no release repository configured")
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$repo/releases?per_page=100")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", USER_AGENT)
                    .build()
                val releases = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw CheckHttpException(resp.code)
                    parseReleases(resp.body?.string().orEmpty())
                }
                val wantX86 = Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"
                when (val choice = MobileReleaseSelector.choose(releases, track, repo, installedVersionCode(), wantX86)) {
                    MobileReleaseChoice.UpToDate -> {
                        resolved = null
                        _state.value = State.UpToDate
                    }
                    MobileReleaseChoice.NoCompatibleApk -> throw NoCompatibleApkException()
                    is MobileReleaseChoice.Newer -> {
                        resolved = choice.release
                        _state.value = State.Available(choice.release.toInfo())
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "update check failed: ${error.message}", error)
                _state.value = State.Failed(failureFor(error, checking = true))
            }
        }
    }

    /** Downloads the release APK with progress, checks it, then opens the system installer. */
    fun downloadAndInstall() {
        val info = (_state.value as? State.Available)?.info ?: return
        val release = resolved?.takeIf { it.apkUrl == info.apkUrl } ?: return
        _state.value = State.Downloading(0)
        scope.launch {
            runCatching {
                val dir = File(context.filesDir, "updates").apply { mkdirs() }
                val out = File(dir, DOWNLOAD_FILE)
                out.delete() // never build on top of a previous half-download
                try {
                    download(release, dir, out)
                    verifyApk(out, release)
                    install(out)
                } catch (e: Throwable) {
                    out.delete() // a bad file must not linger and be retried as-is
                    throw e
                }
                _state.value = State.Available(info) // the sheet stays sane if the user cancels install
            }.onFailure { error ->
                Log.w(TAG, "update download failed: ${error.message}", error)
                _state.value = State.Failed(failureFor(error, checking = false), retryInfo = info)
            }
        }
    }

    private fun download(release: MobileRelease, dir: File, out: File) {
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw DownloadHttpException(resp.code)
            val body = resp.body ?: throw EmptyDownloadException()
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            // Written once here and copied again into the install session: room for two.
            if (dir.usableSpace < total * 2) throw NotEnoughSpaceException(total * 2)
            var copied = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        _state.value = State.Downloading((copied * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (copied == 0L) throw EmptyDownloadException()
            // GitHub states the asset's size in the release listing; anything else is not that asset.
            if (copied != release.apkSize) {
                throw RejectedApkException("size: got $copied bytes, release lists ${release.apkSize}")
            }
        }
    }

    /**
     * Refuses anything that is not provably this app's next production version. Each check names what
     * it saw in the log; the user sees core's damaged-download message for all of them.
     */
    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, release: MobileRelease) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw RejectedApkException("unparseable APK (${apk.length()} bytes)")
        val installed = pm.getPackageInfo(context.packageName, flags)

        val expectedPackage = BrandConfig.currentBrand.applicationId
        if (archive.packageName != context.packageName || archive.packageName != expectedPackage) {
            throw RejectedApkException("package ${archive.packageName}, expected $expectedPackage (installed ${context.packageName})")
        }

        val archiveCode = PackageInfoCompat.getLongVersionCode(archive)
        val installedCode = PackageInfoCompat.getLongVersionCode(installed)
        if (archiveCode <= installedCode) {
            throw RejectedApkException("versionCode $archiveCode is not above installed $installedCode")
        }
        if (archiveCode != release.versionCode.toLong() || archive.versionName != release.version) {
            throw RejectedApkException(
                "APK is ${archive.versionName} ($archiveCode), release is ${release.version} (${release.versionCode})",
            )
        }

        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installed)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty() || archiveSigners != installedSigners) {
            throw RejectedApkException("signing certificate does not match the installed app")
        }
    }

    /** SHA-256 of each current signing certificate; empty when the platform reported none. */
    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            val signing = info.signingInfo!!
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                // Ordered original → current; the last one is what signs this APK today.
                signing.signingCertificateHistory?.takeLast(1)?.toTypedArray()
            }
        } else {
            info.signatures
        }
        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().map { sig ->
            sha.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** Through [PackageInstaller], for the real refusal reason instead of "App not installed". */
    private fun install(apk: File) {
        registerInstallReceiver()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Pinning the package makes the installer itself refuse any other one, as a second guard.
        params.setAppPackageName(context.packageName)
        val sessionId = try {
            installer.createSession(params)
        } catch (e: IOException) {
            throw InstallException(e)
        }
        installer.openSession(sessionId).use { session ->
            session.openWrite(SESSION_ENTRY, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(INSTALL_STATUS_ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, 0, intent, flags).intentSender)
        }
        apk.delete() // the session owns its own copy now
    }

    private var installReceiverRegistered = false

    private fun registerInstallReceiver() {
        if (installReceiverRegistered) return
        installReceiverRegistered = true
        ContextCompat.registerReceiver(
            context,
            installStatusReceiver,
            IntentFilter(INSTALL_STATUS_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                // The user still has to approve the install — the system prompt, not a failure.
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { confirm?.let { context.startActivity(it) } }
                        .onFailure { _state.value = State.Failed(Failure.Install) }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "update installed")
                // Declining the system prompt is a choice, not an error.
                PackageInstaller.STATUS_FAILURE_ABORTED -> Log.i(TAG, "install cancelled by user")
                else -> {
                    Log.w(TAG, "install refused status=$status message=$message")
                    _state.value = State.Failed(Failure.InstallRejected(message?.takeIf { it.isNotBlank() }))
                }
            }
        }
    }

    /** Retries the failed phase; a failed download is retried only while its release is still known. */
    fun retry() {
        val failed = _state.value as? State.Failed ?: return
        val info = failed.retryInfo
        val downloadPhase = failed.failure !is Failure.CheckHttp && failed.failure !is Failure.NoCompatibleApk &&
            failed.failure !is Failure.InvalidReleaseResponse && failed.failure !is Failure.CheckNetwork
        if (info != null && downloadPhase && resolved?.apkUrl == info.apkUrl) {
            _state.value = State.Available(info)
            downloadAndInstall()
        } else {
            check()
        }
    }

    fun reset() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    private fun installedVersionCode(): Int =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()

    private fun MobileRelease.toInfo() = UpdateInfo(version = version, notes = notes, apkUrl = apkUrl)

    private fun parseReleases(body: String): List<GitHubRelease> {
        val array = runCatching { JSONArray(body) }.getOrElse { throw InvalidReleaseResponseException("not a release list") }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val assets = o.optJSONArray("assets") ?: JSONArray()
            GitHubRelease(
                tag = o.optString("tag_name"),
                draft = o.optBoolean("draft", true),
                prerelease = o.optBoolean("prerelease", true),
                notes = o.optString("body").take(16_000),
                assets = (0 until assets.length()).mapNotNull { j ->
                    val a = assets.optJSONObject(j) ?: return@mapNotNull null
                    GitHubAsset(a.optString("name"), a.optString("browser_download_url"), a.optLong("size", -1))
                },
            )
        }
    }

    private companion object {
        const val TAG = "MobileUpdate"
        const val USER_AGENT = "GoatTV-Mobile"
        const val DOWNLOAD_FILE = "mobile-update.apk"
        const val SESSION_ENTRY = "mobile-update"
        const val INSTALL_STATUS_ACTION = "tv.own.owntv.mobile.UPDATE_INSTALL_STATUS"
    }
}
