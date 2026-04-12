plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.firestream.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.firestream.chat"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            // Include x86_64 in debug so the app runs natively on x86_64 emulators.
            // Without this, only arm64-v8a ships and the emulator falls back to
            // Berberis (ARM→x86 translator), which produces spurious VerifyError /
            // "Failure to verify dex" crashes on Compose-heavy classes like
            // MessageBubbleKt. Release builds keep arm64-only via defaultConfig.
            ndk {
                abiFilters += "x86_64"
            }
            packaging {
                jniLibs {
                    // Encryption is disabled in debug (BuildConfig.DEBUG guard) — exclude libsignal native lib (~70 MB)
                    excludes += "**/libsignal_jni.so"
                }
            }
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable.add("NonNullableMutableLiveData")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "**/*.dylib"
            excludes += "**/*.dll"
        }
        jniLibs {
            // libsignal_jni_testing.so is a test-only artifact (~316 MB across 4 ABIs)
            excludes += "**/libsignal_jni_testing.so"
        }
    }

    testOptions {
        // Required for Robolectric to access Android resources (res/values, fonts, etc.)
        // when running Compose UI tests on the JVM.
        unitTests.isIncludeAndroidResources = true
        // Non-Robolectric tests rely on the Android Mock SDK returning null / 0
        // for unmocked methods (e.g. android.util.Log.d). Without this, the
        // existing RealtimePresenceSourceTest crashes on its first Log call.
        // Robolectric tests bypass this and use Robolectric shadows instead.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.database)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Signal Protocol
    implementation(libs.libsignal.android)
    implementation(libs.libsignal.client)

    // Image Loading
    implementation(libs.coil.compose)

    // ExifInterface
    implementation(libs.androidx.exifinterface)

    // DataStore
    implementation(libs.datastore.preferences)

    // Networking
    implementation(libs.okhttp)

    // QR Code
    implementation(libs.zxing.core)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Reorderable drag-and-drop
    implementation(libs.reorderable)

    // WebRTC
    implementation(libs.stream.webrtc.android)

    // Play Services Location
    implementation(libs.play.services.location)

    // Core Library Desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    // Compose UI test on JVM (Robolectric); the BOM aligns versions across artifacts.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
