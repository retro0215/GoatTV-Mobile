import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations

// Packaged locale qualifiers are read from tools/i18n/locales.json entries where packaged = true.
// That catalogue is owned by the core repo (ahXN00/OwnTV_Core), which holds the strings; the copy
// here exists only because Gradle needs the list before any dependency is resolved, so core's copy
// never reaches this build. The pin-bump PR refreshes both files together — never edit one alone,
// or a new language is silently stripped out of the APK while the build stays green.
// The build consumes the ``resourceQualifier`` field specifically (NOT languageTag, NOT weblateCode):
// a runtime BCP-47 tag fed straight into localeFilters is the bug this schema exists to prevent.
val localesCatalogueFile = rootProject.file("tools/i18n/locales.json")
@Suppress("UNCHECKED_CAST")
val packagedLocaleQualifiers: Set<String> = run {
    if (!localesCatalogueFile.isFile) return@run emptySet()
    val raw = groovy.json.JsonSlurper().parseText(localesCatalogueFile.readText()) as List<Map<String, Any>>
    raw.mapNotNull { entry ->
        if ((entry["packaged"] as? Boolean) == true) entry["resourceQualifier"] as? String else null
    }.toSet()
}

plugins {
    alias(libs.plugins.android.application)
    // Kotlin is provided by AGP 9's built-in Kotlin support; this plugin pins the Compose compiler
    // to the same version the TV app and core use.
    alias(libs.plugins.compose.compiler)
    // Consumes :baselineprofile's recording and packages it as baseline.prof.
    alias(libs.plugins.baselineprofile)
}

// The GitHub owner/repo whose Releases carry all four brands' Mobile APKs, each on its own tag
// prefix. Committed in gradle.properties (it is not a secret) and baked into BuildConfig.
val mobileReleaseRepo: String = providers.gradleProperty("goattv.mobile.releaseRepo").orNull.orEmpty().trim()

// The production version, for release builds only. See verifyMobileRelease at the end of this file.
val mobileReleaseVersionName: String? =
    (System.getenv("VERSION_NAME") ?: providers.gradleProperty("goattv.mobile.versionName").orNull)?.takeIf { it.isNotBlank() }
val mobileReleaseVersionCodeOverride: String? =
    (System.getenv("VERSION_CODE") ?: providers.gradleProperty("goattv.mobile.versionCode").orNull)?.takeIf { it.isNotBlank() }
val mobileReleaseVersion: Pair<String, Int>? = mobileReleaseVersionName?.let { name ->
    val parts = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(name)?.groupValues?.drop(1)?.map { it.toInt() }
        ?: return@let null
    val (major, minor, patch) = parts
    if (minor > 99 || patch > 99 || major > 2000) return@let null
    val code = major * 10000 + minor * 100 + patch
    if (code <= 0) return@let null
    if (mobileReleaseVersionCodeOverride != null && mobileReleaseVersionCodeOverride.toIntOrNull() != code) return@let null
    name to code
}

android {
    // Distinct from the TV app's tv.own.owntv, so both install side by side on one device and each
    // keeps its own data. This value can never change after the first release — the launcher label
    // and icon can.
    namespace = "tv.own.owntv.mobile"
    compileSdk {
        version = release(37)
    }

    // The Mobile release key's credentials AND local-only build switches, kept in a standalone
    // properties file OUTSIDE the repo, next to the keystore itself. Gradle only reads
    // gradle.properties from GRADLE_USER_HOME or the project dir, so this one is loaded by hand.
    // Declared here because defaultConfig below already needs it. Deliberately NOT the TV app's
    // owntv-signing.properties: these four apps have their own key, and a fallback to the TV file is
    // exactly how the wrong key would get used without anyone noticing.
    val localSigningProps = Properties().apply {
        val f = File(System.getProperty("user.home"), ".goattv-signing/goattv-mobile-signing.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }

    defaultConfig {
        applicationId = "tv.own.owntv.mobile"
        minSdk = 26
        targetSdk = 36
        // Development values only. Debug builds keep them and install as their own package (see the
        // debug build type's applicationIdSuffix), so they can never sit above a production install.
        // A release build takes its version from VERSION_NAME / goattv.mobile.versionName instead —
        // see the androidComponents block — and refuses to build without one.
        versionCode = 99999
        versionName = "99.99.99"

        // The three switches core reads through CoreBuildInfo. Same resolution order as the TV app:
        // env var (CI) > Gradle property > the out-of-repo properties file.
        buildConfigField(
            "boolean",
            "DIAGNOSTIC_BUILD",
            (providers.gradleProperty("diagnosticBuild").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean",
            "DEV_TOOLS",
            (
                (
                    providers.gradleProperty("owntv.devTools").orNull
                        ?: localSigningProps.getProperty("owntv.devTools")
                    ) == "true"
                ).toString(),
        )
        // Shared secret the metadata Worker's edge rule requires (`x-owntv-key`). NEVER in the repo.
        // A blank key is a working configuration: core falls back to the unprotected base URL.
        val edgeKey = System.getenv("OWNTV_EDGE_KEY")
            ?: providers.gradleProperty("owntv.edgeKey").orNull
            ?: localSigningProps.getProperty("owntv.edgeKey")
            ?: ""
        buildConfigField("String", "TMDB_EDGE_KEY", "\"${edgeKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        val localProperties = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.isFile) f.inputStream().use { load(it) }
        }

        val supabaseUrl = System.getenv("SUPABASE_URL")
            ?: providers.gradleProperty("supabaseUrl").orNull
            ?: localSigningProps.getProperty("supabaseUrl")
            ?: localProperties.getProperty("SUPABASE_URL")
            ?: localProperties.getProperty("supabaseUrl")
            ?: ""
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        val supabaseAnonKey = System.getenv("SUPABASE_ANON_KEY")
            ?: providers.gradleProperty("supabaseAnonKey").orNull
            ?: localSigningProps.getProperty("supabaseAnonKey")
            ?: localProperties.getProperty("SUPABASE_ANON_KEY")
            ?: localProperties.getProperty("supabaseAnonKey")
            ?: ""
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseAnonKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        buildConfigField("String", "MOBILE_RELEASE_REPO", "\"$mobileReleaseRepo\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ABI split via product flavors, mirroring the TV app: `standard` is what real phones and tablets
    // run, `x86_64` exists for the emulator. The player engine ships large prebuilt .so files, so a
    // universal APK would be roughly double the size for no one's benefit.
    flavorDimensions += listOf("brand", "abi")
    productFlavors {
        create("goat") {
            dimension = "brand"
            applicationId = "tv.goat.tv.mobile"
            manifestPlaceholders["appLabel"] = "GoatTV"
        }
        create("allaccess") {
            dimension = "brand"
            applicationId = "tv.allaccess.app.mobile"
            manifestPlaceholders["appLabel"] = "AllAccess"
        }
        create("fivestar") {
            dimension = "brand"
            applicationId = "tv.fivestar.ultra.mobile"
            manifestPlaceholders["appLabel"] = "5Star Ultra"
        }
        create("supreme") {
            dimension = "brand"
            applicationId = "tv.supreme.app.mobile"
            manifestPlaceholders["appLabel"] = "Supreme TV"
        }

        create("standard") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        create("x86_64") {
            dimension = "abi"
            ndk { abiFilters += listOf("x86_64") }
        }
    }

    // ONE Mobile-only production key signs all four brands (goattv-mobile-release.jks, alias
    // goattv-mobile). Never the TV app's key and never the debug key. Env vars first (that is how CI
    // injects the GitHub secrets), then user-wide Gradle properties, then the out-of-repo file.
    // The names are Mobile-specific on purpose: the TV app's KEYSTORE_* / owntv.* names are not read
    // at all, so a TV credential left in an environment can never sign one of these APKs.
    // Nothing configured still builds DEBUG; a release build fails (verifyMobileRelease below).
    fun signingValue(env: String, property: String): String? =
        (System.getenv(env) ?: providers.gradleProperty(property).orNull ?: localSigningProps.getProperty(property))
            ?.takeIf { it.isNotBlank() }

    val releaseKeystore = signingValue("MOBILE_KEYSTORE_FILE", "goattv.mobile.keystoreFile")
    val releaseStorePassword = signingValue("MOBILE_KEYSTORE_PASSWORD", "goattv.mobile.keystorePassword")
    val releaseKeyAlias = signingValue("MOBILE_KEY_ALIAS", "goattv.mobile.keyAlias")
    val releaseKeyPassword = signingValue("MOBILE_KEY_PASSWORD", "goattv.mobile.keyPassword")
    val releaseSigningComplete = releaseKeystore != null && file(releaseKeystore).isFile &&
        releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null
    signingConfigs {
        if (releaseSigningComplete) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storeType = "PKCS12"
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    testOptions {
        // JVM unit tests reach android.util.Log / SystemClock through core; return defaults instead
        // of "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        debug {
            // Pseudolocales (en-XA / ar-XB) are the layout-stress instrument: they lengthen every
            // string and mirror the layout, so an overflowing screen shows up before a translator
            // ever sees it. localeFilters below would otherwise strip them, so the debug-only
            // qualifiers are added back through the per-variant API in the androidComponents block.
            isPseudoLocalesEnabled = true
            // A debug build is its own package (tv.goat.tv.mobile.debug, …), signed with the debug
            // key and versioned 99999. Sharing the production package would let it block a signed
            // release install — a signature conflict, or a "downgrade" from 99999 to 10000.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningComplete) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Packages only the catalogue entries marked packaged = true — the same 24 locales the TV
        // app ships, because both read the same strings out of core. Without this, every library
        // locale folder ships too (appcompat alone contributes ~85), and nothing can strip a locale
        // afterwards: shrinkResources removes unreferenced resources, never locales. The debug-only
        // pseudolocale qualifiers are added back per variant below; release ships neither.
        localeFilters.addAll(packagedLocaleQualifiers)
    }

    packaging {
        jniLibs {
            // Every .so here is an already-stripped prebuilt from a dependency, so AGP's strip step
            // has nothing to remove and merely fails loudly on a machine with no NDK.
            keepDebugSymbols += "**/*.so"
        }
    }

    lint {
        // CI gates on this (see .github/workflows/android.yml), so an error must mean something.
        abortOnError = true
        warningsAsErrors = false
        // A counted sentence must use Android plural resources; keep this invariant fatal so a new
        // extraction cannot reintroduce English-only quantity wording. The strings live in core, but
        // the call site that needs a plural is here.
        fatal += "PluralsCandidate"
        // Media3's player API surface is almost entirely @UnstableApi; this app is built on core's
        // player, so the check fires ~30 times across the player, Live and Multiview code and
        // carries no signal. Opting in file-by-file would only move the same acknowledgement into a
        // handful of annotations. The television app disables it for the same reason.
        disable += "UnsafeOptInUsageError"
        // local.properties is developer-local and never committed (its Windows SDK path cannot be
        // escaped without breaking the local tooling that writes it). CI has no such file at all.
        disable += "PropertyEscape"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Re-add the debug-only pseudolocale qualifiers that the shared `localeFilters` set above would
// otherwise strip. This is the per-variant SetProperty form (the androidResources block sets the
// MutableSet extension form, which applies to every variant equally and so cannot keep
// pseudolocales out of release). Release variants ship neither pseudolocale.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.androidResources.localeFilters.addAll("en-rXA", "ar-rXB")
    }
    // Production versions. versionCode is derived from versionName (major*10000 + minor*100 + patch:
    // 1.0.0 -> 10000, 0.9.0 -> 900), so the two can never disagree; an explicit VERSION_CODE must
    // match the formula. Every brand shares the numbers — their applicationIds differ, so each has
    // its own upgrade lineage. Missing or malformed values fail the release build (below).
    onVariants(selector().withBuildType("release")) { variant ->
        val version = mobileReleaseVersion ?: return@onVariants
        variant.outputs.forEach { output ->
            output.versionName.set(version.first)
            output.versionCode.set(version.second)
        }
    }
}

// A baseline profile is a list of code paths, not machine code, so one recording serves both ABI
// flavors. `mergeIntoMain` writes it to `src/main/generated/baselineProfiles/` rather than the
// recording flavor's own source set, so a profile recorded on an arm phone also ships in the
// x86_64 APK — and so there is one file to review in a diff instead of two.
baselineProfile {
    mergeIntoMain = true
}

// --- hardcoded-literal gate ----------------------------------------------------------------
//
// The same check CI runs, moved onto the developer's own machine. CI is still the enforcing gate —
// this only makes the failure arrive seconds after writing the string instead of minutes after
// pushing it. Wired in while this app is still empty, on purpose: retrofitting it onto twenty
// finished screens is a far worse job.
//
// Deliberately NOT offered: any flag that records the literal and turns the build green. A red build
// means the string moves into core's strings_*.xml or is declared technical — those are the only two
// exits.
abstract class VerifyI18nLiterals : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinSources: ConfigurableFileCollection

    /** The checker and its two reviewed manifests: edit any of them and the verdict may change. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val toolInputs: ConfigurableFileCollection

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    private fun interpreter(): String? = listOf("python", "python3").firstOrNull { candidate ->
        runCatching {
            execOps.exec {
                commandLine(candidate, "--version")
                isIgnoreExitValue = true
                standardOutput = ByteArrayOutputStream()
                errorOutput = ByteArrayOutputStream()
            }.exitValue == 0
        }.getOrDefault(false)
    }

    @TaskAction
    fun verify() {
        val python = interpreter()
        if (python == null) {
            // Failing here would block anyone without Python from building at all. Warn loudly
            // instead — CI still enforces it, so the worst case is a late failure, not a missed one.
            logger.warn(
                "\n  WARNING: Python was not found, so the hardcoded-text check did not run." +
                    "\n  Install Python 3 to catch untranslatable text before pushing; CI will still catch it.\n",
            )
            stamp.get().asFile.writeText("skipped: no python interpreter\n")
            return
        }
        val output = ByteArrayOutputStream()
        val result = execOps.exec {
            workingDir = repoRoot.get().asFile
            commandLine(python, "tools/i18n/check_hardcoded_strings.py", "verify", "--bootstrap")
            environment("PYTHONIOENCODING", "utf-8")
            isIgnoreExitValue = true
            standardOutput = output
            errorOutput = output
        }
        if (result.exitValue != 0) {
            logger.error(output.toString(Charsets.UTF_8))
            throw GradleException("Hardcoded text check failed — see the report above.")
        }
        stamp.get().asFile.writeText("ok\n")
    }
}

val verifyI18nLiterals = tasks.register<VerifyI18nLiterals>("verifyI18nLiterals") {
    group = "verification"
    description = "Fails the build on user-visible text left hardcoded in Kotlin."
    // :app is the only module in this repo. Core's own Kotlin is gated by the identical task in the
    // core repo, so a literal cannot escape by moving between the two. A new module here needs a
    // line added HERE as well as in check_hardcoded_strings.py's SRC_ROOTS — Plan 1 Phase 9 found
    // an undeclared input silently skipping the gate whenever only that module changed.
    kotlinSources.from(fileTree("src/main/java") { include("**/*.kt") })
    toolInputs.from(
        rootProject.file("tools/i18n/check_hardcoded_strings.py"),
        rootProject.file("tools/i18n/hardcoded_baseline.txt"),
        rootProject.file("tools/i18n/safe_literals.txt"),
    )
    repoRoot.set(rootProject.layout.projectDirectory)
    stamp.set(layout.buildDirectory.file("i18n/literal-inventory.txt"))
}

// preBuild fronts every variant, so debug compile checks and release assembles are both covered.
// Inputs are declared above, so an unchanged source tree makes this UP-TO-DATE and free.
tasks.named("preBuild") { dependsOn(verifyI18nLiterals) }

dependencies {
    // The shared engine, from its own repository — https://github.com/ahXN00/OwnTV_Core. Set
    // owntv.corePath in ~/.gradle/gradle.properties to build against its source instead of the pin.
    implementation(libs.owntv.core)
    // The shared playback engine. It renders nothing itself, which is exactly why the same engine
    // serves the TV HUD and this app's touch controls. Always on core's version.
    implementation(libs.owntv.player.core)
    // libmpv is NOT declared here. `:player-core` exposes it as `api`, so it arrives on this app's
    // compile classpath with core — which it must, because `OwnTVPlayer`'s supertype is
    // `MPVLib.EventObserver`. It was declared explicitly while the pin was older than `1.0.6`, where
    // core still hid it behind `implementation`; the pin is far past that, and two hosts each naming
    // their own version is how a packaging conflict over a native library starts.

    // Core
    implementation(libs.androidx.core.ktx)
    // The launch screen: the flip-card animation, one theme per icon colour (see themes.xml).
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Compose (BOM-managed) — Material 3 for touch. Never androidx.tv.*; see libs.versions.toml.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // Window size classes — this app has to lay out for a phone and a tablet from one build.
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.graphics.shapes)

    // Lifecycle / Navigation
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // WorkManager — core's sync/EPG workers, whose auto-initializer core's manifest removes.
    implementation(libs.androidx.work.runtime)

    // Paging — core's catalog DAOs return PagingSource.
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Image loading
    // Media3's SubtitleView, for the image subtitles the engine renders through ExoPlayer.
    implementation(libs.androidx.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Casting. The sender half only: the Chromecast decodes the stream itself, so nothing of the
    // playback engine crosses over — see tv.own.owntv.mobile.cast.
    implementation(libs.play.services.cast.framework)

    // Supabase & Ktor (Home Promotions & Social Rooms)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.mediarouter)

    // Local sync pairing: the camera reads the QR code the other device shows. zxing decodes it —
    // the same library core uses to draw one.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // Dependency injection
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Debug tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Records the profile this module then packages. Recording needs a real device, so this is
    // never exercised by CI; see baselineprofile/BaselineProfileGenerator.kt.
    baselineProfile(project(":baselineprofile"))
}

// --- production release gate -----------------------------------------------------------------
//
// A release APK is only ever a customer-installable one: signed with the Mobile production key,
// carrying a real version, and pointing its updater at a real repository. Anything missing fails
// the build before compilation, instead of producing an unsigned or 99999 APK that looks like a
// release. Debug builds never run this.
//
// The single escape is -Pgoattv.mobile.unsignedInspectionBuild=true, for CI jobs that only inspect
// a release APK's contents (i18n.yml's pseudolocale check) and never distribute it.
val verifyMobileRelease by tasks.registering {
    group = "verification"
    description = "Fails a release build that is unsigned, unversioned or has no update repository."
    val inspectionOnly = providers.gradleProperty("goattv.mobile.unsignedInspectionBuild").orNull == "true"
    val problems = if (inspectionOnly) emptyList() else buildList {
        if (android.signingConfigs.findByName("release") == null) {
            add(
                "Mobile production signing is not configured. Set MOBILE_KEYSTORE_FILE, MOBILE_KEYSTORE_PASSWORD, " +
                    "MOBILE_KEY_ALIAS and MOBILE_KEY_PASSWORD, or the goattv.mobile.* keys in " +
                    "~/.goattv-signing/goattv-mobile-signing.properties (and the keystore file must exist).",
            )
        }
        if (mobileReleaseVersion == null) {
            add(
                "No valid release version. Set VERSION_NAME (or -Pgoattv.mobile.versionName) to X.Y.Z; " +
                    "VERSION_CODE, if set, must equal major*10000 + minor*100 + patch. Got name=" +
                    "${mobileReleaseVersionName ?: "<unset>"}, code=${mobileReleaseVersionCodeOverride ?: "<derived>"}.",
            )
        }
        if (mobileReleaseRepo.isBlank()) {
            add("goattv.mobile.releaseRepo is blank in gradle.properties; the in-app updater would have nowhere to look.")
        }
    }
    doLast {
        if (problems.isNotEmpty()) {
            throw GradleException("Refusing to build a Mobile release APK:\n  - " + problems.joinToString("\n  - "))
        }
    }
}

// Exactly the customer release variants (preGoatStandardReleaseBuild, ...). The baseline profile
// plugin's nonMinifiedRelease / benchmarkRelease variants are recording tools, not releases, and
// are left alone.
tasks.matching { it.name.matches(Regex("^pre(Goat|Allaccess|Fivestar|Supreme)(Standard|X86_64)ReleaseBuild$")) }
    .configureEach { dependsOn(verifyMobileRelease) }
