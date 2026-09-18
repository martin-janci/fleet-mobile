plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // Since AGP 9, `com.android.library` is incompatible with the Kotlin
    // Multiplatform plugin; this is its KMP-aware replacement.
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(21)

    androidLibrary {
        namespace = "dev.claudefleet.mobile.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    // A plain JVM target so commonTest runs on the host without a device or emulator.
    jvm()

    // Kotlin/Native cross-compiles these to klibs on Linux, but linking the
    // framework, running simulator tests and the Xcode build all need macOS.
    //
    // There is deliberately no `iosX64()`. Compose Multiplatform stopped
    // publishing that variant (`org.jetbrains.compose.runtime:runtime-uikitx64`)
    // after 1.10.3, so declaring it makes `./gradlew build` and
    // `:shared:assemble` fail outright on the *commonMain* metadata transform,
    // and prints error-severity resolution blocks on every other invocation.
    // iosX64 is the Intel-Mac simulator only: iosArm64 covers devices and
    // iosSimulatorArm64 the Apple-Silicon simulator, which is every Mac this
    // will be built on. So dropping it costs an Intel-Mac developer a local
    // simulator run and nothing on device or in CI.
    //
    // Do NOT "fix" this by pinning compose-multiplatform back to 1.10.3, the
    // last version that publishes iosX64: that trades every release since for
    // a simulator nobody here runs. If iosX64 is ever genuinely needed, bring
    // it back with a CMP version that publishes it, not by re-adding the
    // target under 1.12.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: `:shared` owns the Compose UI, and the
            // thin platform hosts compose it into their own entry points.
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.ui)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // EncryptedSharedPreferences, and the Keystore-backed master key
            // behind it. See `store/Secrets.android.kt` for the deprecation.
            implementation(libs.androidx.security.crypto)

            // The QR scanner (`ui/scan/QrScanner.android.kt`). `activity-compose`
            // is here for the permission launcher, not for an Activity: the
            // camera permission is asked from inside the shared Pair screen.
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.zxing.core)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}
