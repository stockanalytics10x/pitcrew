import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    useLibrary("android.car")
    namespace = "com.example.washguard"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.washguard"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Pleos Connect runs on arm64 cars and x86_64 emulators; other ABIs only add size.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        // EV charger API key (한국환경공단, data.go.kr) from local.properties: EV_CHARGER_KEY=... (not in git).
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { stream -> this.load(stream) }
        }
        val evKey = (localProps.getProperty("EV_CHARGER_KEY") ?: "").trim().trim('"', '\'')
        val opinetKey = (localProps.getProperty("OPINET_KEY") ?: "").trim().trim('"', '\'')
        buildConfigField("String", "OPINET_KEY", "\"" + opinetKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        buildConfigField("String", "EV_CHARGER_KEY", "\"" + evKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }

    // Release signing from keystore/release.properties (not in git). Without it the release build is unsigned.
    val signProps = Properties().apply {
        rootProject.file("keystore/release.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (signProps.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file("keystore/" + signProps.getProperty("storeFile"))
            storePassword = signProps.getProperty("storePassword")
            keyAlias = signProps.getProperty("keyAlias")
            keyPassword = signProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    testImplementation("org.json:json:20240303")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
