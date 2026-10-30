import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.findbridge.android"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.findbridge.android"
        minSdk = 28
        targetSdk = 37
        versionCode = 7
        versionName = "3.2.0-rc2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    val signingFile =
        providers.environmentVariable("FINDBRIDGE_SIGNING_PROPERTIES").orNull?.let(::file)
    if (signingFile?.isFile == true) {
        val signing = Properties().apply { signingFile.inputStream().use(::load) }
        signingConfigs.create("privateRelease") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("privateRelease")
        }
    }

    buildFeatures {
        buildConfig = true
    }
    bundle { language { enableSplit = false } }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        // The compact APK intentionally targets arm64 phones and arm64 emulators.
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("androidx.window:window-java:1.5.1")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
