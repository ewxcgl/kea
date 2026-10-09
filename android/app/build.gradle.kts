plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.kea"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.kea"
        minSdk = 26          // notification channels + foreground service types
        targetSdk = 35       // mandatory foreground-service type declarations
        versionCode = 1
        versionName = "1.0.0"

        // Overridable at build time so forks can point at their own relay:
        //   ./gradlew assembleRelease -PkeaRelayUrl=wss://my-relay.example/ws
        val relay = (project.findProperty("keaRelayUrl") as String?)
            ?: "ws://172.20.10.4:8080/ws"
        buildConfigField("String", "RELAY_URL", "\"$relay\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "de")
    }

    signingConfigs {
        // Debug uses the default debug keystore. Release expects a keystore at
        // keystore/kea.jks with these properties, so a clone without
        // secrets still builds — it just produces an unsigned-able debug-grade
        // artefact rather than failing.
        val ks = rootProject.file("keystore/kea.jks")
        if (ks.exists()) {
            create("release") {
                storeFile = ks
                storePassword = (project.findProperty("keaStorePass") as String?) ?: "kea"
                keyAlias = (project.findProperty("keaKeyAlias") as String?) ?: "kea"
                keyPassword = (project.findProperty("keaKeyPass") as String?) ?: "kea"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // The user asked for the smallest artefact we can honestly ship.
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // debug signing keeps `assembleRelease` runnable without secrets.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")

            // Only real-device ABIs. Add x86_64 here if you test on an emulator.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xcontext-receivers",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
        jniLibs {
            // These AARs ship duplicate or unneeded .so files.
            excludes += setOf("**/libc++_shared.so", "**/libjsi.so", "**/libfbjni.so")
        }
    }

// MapLibre ships ~10 MB of native code per ABI, which is the overwhelming
    // majority of this APK. Per-ABI splits mean a phone downloads one copy
    // instead of two; the universal APK is kept for sideloading and for CI.
    //
    // x86_64 is included because development happens against emulators
    // (MuMu/AVD), which are usually x86_64 guests even on an ARM64 Windows
    // host. Real phones only ever need arm64-v8a or armeabi-v7a, and Play
    // delivers only those, so shipping x86_64 costs nothing in the store.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // Vector tiles are remote (OpenFreeMap); nothing is stripped from assets.
    androidResources {
        noCompress += "json"
    }

    lint {
        abortOnError = false          // report, do not block the build
        checkReleaseBuilds = false
    }
}


dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)          // per-app locales
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)

    // Map rendering. OpenFreeMap tiles, no API key.
    implementation(libs.maplibre.android)

    // Ed25519 + ChaCha20Poly1305 + HKDF-SHA256, matching PROTOCOL.md §4.
    // BouncyCastle exposes the raw-byte primitives Tink hides, and R8 strips
    // the ~99% of BC that a location app never touches.
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
}

// TEMP DIAGNOSTIC
tasks.register("diagCP") {
    doLast {
        listOf(
            "debugCompileClasspath",
            "debugRuntimeClasspath",
            "debugUnitTestCompileClasspath",
            "debugUnitTestRuntimeClasspath",
        ).forEach { name ->
            println("=== $name ===")
            try {
                val files = configurations.getByName(name).resolve()
                println("resolved ${files.size}")
                files.sortedBy { it.absolutePath.length }.takeLast(6).forEach {
                    println("  len=${it.absolutePath.length} ${it.absolutePath}")
                }
            } catch (t: Throwable) {
                println("THREW ${t::class.java.name}")
                var c: Throwable? = t
                var d = 0
                while (c != null && d < 10) {
                    println("  [$d] ${c::class.java.name}: ${c.message}")
                    c.stackTrace.take(3).forEach { println("        at $it") }
                    c = c.cause
                    d++
                }
            }
        }
    }
}
