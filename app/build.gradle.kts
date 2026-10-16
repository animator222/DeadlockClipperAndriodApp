plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.deadlockclipper.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deadlockclipper.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.17.1"
        // Phones (64 and 32 bit ARM) and the Android emulator / Chromebooks (x86_64).
        buildConfigField("String", "NEWPIPE_VERSION", "\"" + ((project.findProperty("newpipeVersion") as String?)?.takeIf { it.isNotBlank() } ?: "v0.24.8") + "\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        // A fixed debug key is committed so every build (CI or local) can be installed over
        // the previous one without uninstalling first.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")

    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("org.opencv:opencv:4.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // YouTube keeps changing, so CI builds with NewPipeExtractor's newest release (see the workflow);
    // this version is only the fallback for local builds.
    val newPipe = (project.findProperty("newpipeVersion") as String?)?.takeIf { it.isNotBlank() } ?: "v0.24.8"
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newPipe")
}
