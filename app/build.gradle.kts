plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tinydj"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tinydj"
        minSdk = 31
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.5"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Oboe ships as a prefab AAR; c++_shared is required to link it.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            // Trim the APK to the ABIs worth shipping. arm64-v8a is every modern phone.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // If your installed NDK differs, change this (SDK Manager > NDK (Side by side)),
    // or delete the line to use the project default.
    ndkVersion = "27.0.12077973"

    buildFeatures {
        compose = true
        prefab = true // exposes the Oboe prefab package to CMake
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Lets the JVM render harness (Robolectric, NATIVE graphics) reach resources so
    // we can screenshot Compose to a PNG without a device. Test-only; does not affect
    // the app build.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.oboe)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // JVM render harness (test-only): render Compose to PNG headlessly.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
