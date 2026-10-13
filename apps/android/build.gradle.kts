import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Release signing lives outside the repository (no secrets in git). A release artifact cannot
// be assembled from a fresh clone unless the production signing properties are configured.
// The SAME key signs the Wear app (ADR-0008): the Wear Data Layer requires matching
// applicationId + signature.
val releaseKeystoreProperties: Properties? by lazy {
    val file = File(System.getProperty("user.home"), ".android-keystores/ai-daily-memory.properties")
    if (!file.isFile) return@lazy null
    Properties().apply { file.inputStream().use { load(it) } }
}

room {
    // Exported and committed: every schema version is reviewable and migration-testable.
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.apercallc.aidailymemory"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.apercallc.aidailymemory"
        minSdk = 30
        targetSdk = 37
        versionCode = 2 // Phone range: 1..999_999; Wear starts at 1_000_000 (ADR-0008).
        versionName = "0.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Version-drift checks depend on what was published upstream today, not on this
        // change, so they would turn main red without a commit. Dependabot owns upgrades.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
        checkDependencies = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // MigrationTestHelper reads exported schemas as assets, and Robolectric unit tests only
        // see the debug variant's merged assets. Schemas hold no user data; release never
        // includes them.
        getByName("debug").assets.directories.add("$projectDir/schemas")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.wearable)
    implementation(libs.sentry.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
