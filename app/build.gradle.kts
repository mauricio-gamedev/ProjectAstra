plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val astraSigningFile = System.getenv("ASTRA_DEV_KEYSTORE_FILE")
val astraSigningStorePassword = System.getenv("ASTRA_DEV_KEYSTORE_PASSWORD")
val astraSigningAlias = System.getenv("ASTRA_DEV_KEY_ALIAS")
val astraSigningKeyPassword = System.getenv("ASTRA_DEV_KEY_PASSWORD")
val hasAstraSigning = listOf(
    astraSigningFile,
    astraSigningStorePassword,
    astraSigningAlias,
    astraSigningKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.astromg01.launcher"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "io.github.astromg01.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.1.0-alpha14"
        buildConfigField(
            "String",
            "SIGNING_MODE",
            "\"${if (hasAstraSigning) "stable" else "temporary"}\""
        )

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    signingConfigs {
        if (hasAstraSigning) {
            create("astraDev") {
                storeFile = file(astraSigningFile!!)
                storePassword = astraSigningStorePassword
                keyAlias = astraSigningAlias
                keyPassword = astraSigningKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (hasAstraSigning) {
                signingConfig = signingConfigs.getByName("astraDev")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
