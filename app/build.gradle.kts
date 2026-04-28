import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize") // Required by Samsung Health Data SDK internal Parcelable usage
}

// Load secrets from local.properties (gitignored)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.artem.healthagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.artem.healthagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Server URL and schedule are configured by the user at runtime via SettingsActivity.
        // No secrets needed in build config.
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Samsung Health SDK — place samsung-health-data-*.aar in app/libs/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
