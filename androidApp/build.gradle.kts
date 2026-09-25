plugins {
    // AGP 9 brings its own Kotlin support, so `org.jetbrains.kotlin.android` is
    // neither needed nor allowed here — applying it clashes with the Kotlin
    // plugin AGP already puts on the classpath.
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "dev.claudefleet.mobile.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.claudefleet.mobile"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Overridable from the command line — the release workflow passes
        // `-PversionName=<tag without v> -PversionCode=<run number>`. These
        // defaults are what a plain local build still gets.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "0.1.0"
        // The instrumentation tests below need a runner. Declared here rather
        // than in a `testOptions` block because this is the only one.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A `release` signing config exists only when all four of these are in the
    // environment. CI decodes `ANDROID_KEYSTORE_BASE64` into a file and sets
    // the rest before invoking `assembleRelease`; locally, with none of them
    // set, `assembleRelease` still succeeds and produces an unsigned APK.
    // There is deliberately no fallback to the debug key for a release build:
    // when the config is absent, the release build type is simply unsigned.
    val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    val hasReleaseSigning = !releaseKeystoreFile.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        // A debug build is signed with whichever machine's debug key built it,
        // the release with the release key — Android refuses to update one
        // with the other. Its own application id lets a local debug build sit
        // next to the installed release (each paired on its own) instead of
        // demanding an uninstall. `src/debug/res` renames it on the launcher.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // `BuildConfig.VERSION_NAME` is what the Settings screen shows as the
        // app's version, so there is one place a release bumps it.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    // The host builds the Ktor engine and hands it to `AppContainer`, so it
    // needs the engine itself; `:shared` depends on it only `implementation`.
    implementation(libs.ktor.client.okhttp)

    // Instrumentation tests for the two things only a device can answer about
    // this module: whether the manifest actually routes a `claudefleet:` URL
    // here, and whether the shared Compose UI draws at all. Both live in
    // `androidApp` rather than in `:shared` because both are properties of the
    // *app* — its merged manifest and its activity — which `:shared` has no
    // access to.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
