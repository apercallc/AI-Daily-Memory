plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs stable unit, coverage, and Android lint gates expected before merge."
    dependsOn(
        ":core:recording:check",
        ":apps:android:testDebugUnitTest",
        ":apps:wear:testDebugUnitTest",
        ":apps:android:lintDebug",
        ":apps:wear:lintDebug",
    )
}
