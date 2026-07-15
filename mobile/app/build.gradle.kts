plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.roamly"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.roamly"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "1.14.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Upload keystore is supplied via env vars (CI) or a local roamly-upload.jks
    // + gradle.properties (dev machine). Falls back to the debug keystore when
    // absent so local builds without secrets still work.
    val uploadStorePath = System.getenv("ROAMLY_UPLOAD_STORE_FILE")
        ?: (project.findProperty("ROAMLY_UPLOAD_STORE_FILE") as String?)
    // A bare filename resolves against the :app module dir here, NOT the repo root
    // where CI writes the keystore — so try the module-relative path first, then
    // fall back to resolving against rootProject. Without this the release silently
    // fell back to the debug key, which is regenerated per CI runner, so every
    // release was signed with a different key and updates failed ("App not
    // installed"). An absolute path passes through file() unchanged.
    val uploadStoreFile = uploadStorePath?.let { p ->
        file(p).takeIf { it.exists() } ?: rootProject.file(p)
    }
    val hasUploadSigning = uploadStoreFile != null && uploadStoreFile.exists()

    // On CI (ROAMLY_REQUIRE_UPLOAD_SIGNING=1) a missing keystore must fail the
    // build rather than quietly producing a debug-signed, un-updatable APK.
    if (System.getenv("ROAMLY_REQUIRE_UPLOAD_SIGNING") == "1" && !hasUploadSigning) {
        throw GradleException(
            "Upload signing required but keystore not found (ROAMLY_UPLOAD_STORE_FILE=" +
                "'${uploadStorePath ?: "<unset>"}'). Refusing to sign the release with the debug key."
        )
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("release") {
                storeFile = uploadStoreFile
                storePassword = System.getenv("ROAMLY_UPLOAD_STORE_PASSWORD")
                    ?: project.findProperty("ROAMLY_UPLOAD_STORE_PASSWORD") as String?
                keyAlias = System.getenv("ROAMLY_UPLOAD_KEY_ALIAS")
                    ?: project.findProperty("ROAMLY_UPLOAD_KEY_ALIAS") as String?
                keyPassword = System.getenv("ROAMLY_UPLOAD_KEY_PASSWORD")
                    ?: project.findProperty("ROAMLY_UPLOAD_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = if (hasUploadSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Network
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Preferences
    implementation(libs.datastore.preferences)

    // Map (OpenStreetMap, no API key needed)
    implementation(libs.osmdroid)

    // Location
    implementation(libs.play.services.location)

    // Room (offline cache)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // WorkManager + Hilt integration
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Coroutines
    implementation(libs.coroutines.android)

    // Image loading (journal photos)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
