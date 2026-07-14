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
        versionCode = 15
        versionName = "1.10.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Upload keystore is supplied via env vars (CI) or a local roamly-upload.jks
    // + gradle.properties (dev machine). Falls back to the debug keystore when
    // absent so local builds without secrets still work.
    val uploadStorePath = System.getenv("ROAMLY_UPLOAD_STORE_FILE")
        ?: (project.findProperty("ROAMLY_UPLOAD_STORE_FILE") as String?)
    val hasUploadSigning = uploadStorePath != null && file(uploadStorePath).exists()

    signingConfigs {
        if (hasUploadSigning) {
            create("release") {
                storeFile = file(uploadStorePath!!)
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
