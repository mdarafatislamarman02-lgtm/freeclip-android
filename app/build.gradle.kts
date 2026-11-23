plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.freeclip"
    compileSdk = 34

    androidResources { noCompress += listOf("zip") }

    defaultConfig {
        applicationId = "app.clipforge"
        minSdk = 29
        targetSdk = 34
        versionCode = 7
        versionName = "1.0.0"
    }

    // One fixed key, so a new build installs over the old one (no uninstall needed from now on).
    signingConfigs {
        create("clipforge") {
            storeFile = file("clipforge.jks")
            storePassword = "clipforge123"
            keyAlias = "clipforge"
            keyPassword = "clipforge123"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("clipforge")
        }
        debug { signingConfig = signingConfigs.getByName("clipforge") }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.guava:guava:33.2.1-android")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.compose.material:material-icons-core")

    // Offline speech recognition with word timestamps
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47") {
        exclude(group = "net.java.dev.jna")
    }

    // Link import (YouTube)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
