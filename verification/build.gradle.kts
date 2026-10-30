import java.util.Properties

plugins { id("com.android.application") }

// Keep the default R8 driver free of a second GMS SDK: instrumentation's classloader
// can otherwise replace manifest-kept GMS classes with incompatible unminified parents.
val includeGmsProbe = providers.gradleProperty("includeGmsProbe").orNull == "true"

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
        manifestPlaceholders["verificationTargetProcess"] =
            providers.gradleProperty("verificationTargetProcess").orNull ?: "dev.findbridge.android"
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
    if (includeGmsProbe) sourceSets.getByName("main").java.srcDir("src/gms/kotlin")
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    if (includeGmsProbe) implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test:core:1.7.0")
    implementation("junit:junit:4.13.2")
}
