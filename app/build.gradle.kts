plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.androidx.baselineprofile)
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

baselineProfile {
    // Explicit opt-in: profile regeneration is a maintenance task, not part of every assembleRelease.
    automaticGenerationDuringBuild = false
}

// Fails the build when any @Composable function has more than COMPOSABLE_PARAM_MAX
// explicit parameters. Above this threshold the Kotlin/Compose compiler's register
// allocator has emitted `copy-cat1` over Reference slots that ART's class verifier
// rejects at render time (crash at chat open on both emulator and device).
// See ~/.claude/.../memory/feedback_composable_param_limit.md for background.
val COMPOSABLE_PARAM_MAX = 10

tasks.register("checkComposableParamCount") {
    group = "verification"
    description = "Fails if any @Composable has more than $COMPOSABLE_PARAM_MAX explicit parameters"

    val sources = fileTree("src/main/java") { include("**/*.kt") }
    inputs.files(sources)

    doLast {
        val violations = mutableListOf<String>()
        sources.forEach { file ->
            val text = file.readText()
            Regex("@Composable\\b").findAll(text).forEach annotations@{ annotation ->
                val start = annotation.range.first
                val funKeyword = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)").find(text, start) ?: return@annotations
                val funName = funKeyword.groupValues[1]
                val parenStart = text.indexOf('(', funKeyword.range.last)
                if (parenStart == -1) return@annotations
                // Walk to matching ')' tracking brackets, strings, and comments.
                var depth = 1
                var i = parenStart + 1
                var inString = false
                var inLineComment = false
                var inBlockComment = false
                while (i < text.length && depth > 0) {
                    val c = text[i]
                    when {
                        inLineComment -> if (c == '\n') inLineComment = false
                        inBlockComment -> if (c == '*' && i + 1 < text.length && text[i + 1] == '/') { inBlockComment = false; i++ }
                        inString -> if (c == '"' && text[i - 1] != '\\') inString = false
                        c == '"' -> inString = true
                        c == '/' && i + 1 < text.length && text[i + 1] == '/' -> { inLineComment = true; i++ }
                        c == '/' && i + 1 < text.length && text[i + 1] == '*' -> { inBlockComment = true; i++ }
                        c == '(' || c == '<' || c == '{' || c == '[' -> depth++
                        c == ')' || c == '>' || c == '}' || c == ']' -> depth--
                    }
                    i++
                }
                val parenEnd = i - 1
                val paramsText = text.substring(parenStart + 1, parenEnd)
                // Top-level comma count.
                var commas = 0
                var nest = 0
                var s = false; var lc = false; var bc = false
                var j = 0
                while (j < paramsText.length) {
                    val c = paramsText[j]
                    when {
                        lc -> if (c == '\n') lc = false
                        bc -> if (c == '*' && j + 1 < paramsText.length && paramsText[j + 1] == '/') { bc = false; j++ }
                        s -> if (c == '"' && paramsText[j - 1] != '\\') s = false
                        c == '"' -> s = true
                        c == '/' && j + 1 < paramsText.length && paramsText[j + 1] == '/' -> { lc = true; j++ }
                        c == '/' && j + 1 < paramsText.length && paramsText[j + 1] == '*' -> { bc = true; j++ }
                        c == '(' || c == '<' || c == '{' || c == '[' -> nest++
                        c == ')' || c == '>' || c == '}' || c == ']' -> nest--
                        c == ',' && nest == 0 -> commas++
                    }
                    j++
                }
                // Kotlin allows a trailing comma after the last param; don't count it.
                val trimmedTail = paramsText.replace(Regex("(?s)\\s+$"), "")
                val paramCount = when {
                    paramsText.isBlank() -> 0
                    trimmedTail.endsWith(",") -> commas
                    else -> commas + 1
                }
                if (paramCount > COMPOSABLE_PARAM_MAX) {
                    val line = text.substring(0, start).count { it == '\n' } + 1
                    val rel = file.relativeTo(rootDir)
                    violations += "$rel:$line  @Composable fun $funName has $paramCount params (max $COMPOSABLE_PARAM_MAX)"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Composable param-count check failed (${violations.size} violation(s)):\n" +
                    violations.joinToString("\n") { "  $it" } +
                    "\n\nKeep @Composable functions <= $COMPOSABLE_PARAM_MAX explicit params. " +
                    "Collapse callbacks and per-instance display state into @Immutable holder data classes " +
                    "(see MessageBubble.kt for the MessageBubbleCallbacks / MessageBubbleState pattern)."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("checkComposableParamCount")
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

    // Baseline Profile
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

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
