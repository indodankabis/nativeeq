plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStorePath = System.getenv("EQ_STORE_FILE")?.takeIf { it.isNotBlank() }
    ?: "${System.getProperty("user.home")}/.keystores/nativeeq-release.keystore"
val releaseSigned = File(releaseStorePath).exists()

android {
    namespace = "com.nativeeq.equalizer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nativeeq.equalizer"
        minSdk = 26
        targetSdk = 35
        versionCode = 46
        versionName = "1.0.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    flavorDimensions += "dist"
    productFlavors {
        // full = GitHub/Obtainium build incl. ML Kit genai-prompt (proprietary)
        // foss = F-Droid build, zero non-free deps (Nano backend stubbed out)
        create("full")
        create("foss")
    }

    signingConfigs {
        create("release") {
            storeFile = file(releaseStorePath)
            storePassword = System.getenv("EQ_STORE_PASSWORD")
            keyAlias = System.getenv("EQ_KEY_ALIAS") ?: "nativeeq"
            keyPassword = System.getenv("EQ_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigned) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    "fullImplementation"("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
