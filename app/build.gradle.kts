plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

// Must match native/build-deps.sh (NDK_VERSION) and the ABI list below.
val ndkVersionUsed = "29.0.14206865"
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "app.sipdialer"
    compileSdk = 37
    ndkVersion = ndkVersionUsed

    defaultConfig {
        applicationId = "app.sipdialer"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // One APK per ABI (an App Bundle does the same automatically for Play / F-Droid).
    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = false
        }
    }
}

// PJSIP, OpenSSL and Opus as static libraries (native/build/<abi>), linked into
// libsipdialer.so by CMake. The script is incremental: it skips ABIs that are up to date.
val nativeDeps =
    tasks.register<Exec>("buildNativeDeps") {
        val root = rootProject.layout.projectDirectory
        inputs.files(root.file("native/build-deps.sh"), root.file("native/config_site.h"))
        outputs.files(abis.map { root.file("native/build/$it/.stamp") })
        // Locals only: the configuration cache can't serialize references to the script.
        val sdkDir = androidComponents.sdkComponents.sdkDirectory
        val ndk = ndkVersionUsed
        doFirst {
            val sdk = sdkDir.get().asFile
            environment("ANDROID_HOME", sdk.path)
            environment("ANDROID_NDK_ROOT", sdk.resolve("ndk/$ndk").path)
        }
        commandLine(listOf(root.file("native/build-deps.sh").asFile.path) + abis)
    }
tasks.named("preBuild") { dependsOn(nativeDeps) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.telecom)
    implementation(libs.androidx.media)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
