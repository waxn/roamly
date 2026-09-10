plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // Applied conditionally in app/build.gradle.kts, only when a
    // google-services.json is present -- see the comment there.
    alias(libs.plugins.google.services) apply false
}
