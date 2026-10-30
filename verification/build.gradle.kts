import java.util.Properties

plugins { id("com.android.application") }

// A self-contained, opt-in driver for the real minified APK. Its dependencies are not
// supplied by the target APK, where R8 may rename or remove Kotlin/AndroidX classes.
android {
    namespace = "dev.findbridge.verification"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.findbridge.verification"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    val signingFile =
        providers.environmentVariable("FINDBRIDGE_SIGNING_PROPERTIES").orNull?.let(::file)
    if (signingFile?.isFile == true) {
        val signing = Properties().apply { signingFile.inputStream().use(::load) }
        signingConfigs.create("targetRelease") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("targetRelease")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test:core:1.7.0")
    implementation("junit:junit:4.13.2")
}
