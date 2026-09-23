plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs the repository verification tasks expected before merge."
    dependsOn(
        ":core:recording:testDebugUnitTest",
        ":core:recording:koverVerify",
        ":apps:android:testDebugUnitTest",
        ":apps:wear:testDebugUnitTest",
    )
}
