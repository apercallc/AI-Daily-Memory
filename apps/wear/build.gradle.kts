plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        debug {
            // Mirrors apps/android so debug phone + debug watch builds can talk to each other.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.wear.compose.material3)

    testImplementation(libs.kotlin.test.junit)
}
