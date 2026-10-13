import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// SAME key as apps/android (ADR-0008): the Wear Data Layer only delivers messages between
// apps with the same package name AND signature. Lives outside the repo; absent = unsigned.
val releaseKeystoreProperties: Properties? by lazy {
    val file = File(System.getProperty("user.home"), ".android-keystores/ai-daily-memory.properties")
    if (!file.isFile) return@lazy null
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.apercallc.aidailymemory.wear"
    compileSdk = 37

    defaultConfig {
        // Must equal the phone app's applicationId (and share its signing key): the Wear OS
        // Data Layer only delivers messages between apps with the same package. See ADR-0008.
        applicationId = "com.apercallc.aidailymemory"
        minSdk = 30
        targetSdk = 37
        versionCode = 1_000_002 // Wear range starts at 1_000_000; keep distinct from phone (ADR-0008).
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // Mirrors apps/android so debug phone + debug watch builds can talk to each other.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            releaseKeystoreProperties?.let { props ->
                signingConfig = signingConfigs.create("releaseKeystore") {
                    storeFile = File(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Version-drift checks depend on what was published upstream today, not on this
        // change, so they would turn main red without a commit. Dependabot owns upgrades.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

// Fail closed instead of producing an APK that looks releasable but cannot update an install.
val verifyReleaseSigning by tasks.registering {
    doLast {
        check(releaseKeystoreProperties != null) {
            "Release signing properties are missing; configure the protected keystore before building release artifacts."
        }
    }
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(project(":core:recording"))
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.activity.compose)
    implementation(libs.sentry.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.wear.compose.material3)

    testImplementation(libs.kotlin.test.junit)
}
