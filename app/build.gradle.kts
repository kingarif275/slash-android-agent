plugins {
    id("com.android.application")
}

android {
    namespace = "com.slash.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.slash.agent"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".reconstructed"
            versionNameSuffix = "-reconstructed"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
