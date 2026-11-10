// Contains the build.gradle code for this part of Olive.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

tasks.named("check") {
    dependsOn(rootProject.tasks.named("checkArchitecture"))
}
val firebaseConfigured = file("google-services.json").exists() && providers.gradleProperty("oliveLocalTest").orNull != "true"
if (firebaseConfigured) pluginManager.apply("com.google.gms.google-services")
// Optional separate output directory for checks while Android Studio indexes the default build.
providers.gradleProperty("oliveBuildDir").orNull?.let { layout.buildDirectory.set(file(it)) }
val localProperties = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
val geoapifyApiKey = localProperties.getProperty("GEOAPIFY_API_KEY").orEmpty()
require(geoapifyApiKey.matches(Regex("[A-Za-z0-9_-]*"))) { "GEOAPIFY_API_KEY contains unsupported characters" }
val mapboxAccessToken = localProperties.getProperty("MAPBOX_ACCESS_TOKEN").orEmpty()
require(mapboxAccessToken.matches(Regex("[A-Za-z0-9._-]*"))) { "MAPBOX_ACCESS_TOKEN contains unsupported characters" }
val realtimeDatabaseUrl = localProperties.getProperty("REALTIME_DATABASE_URL").orEmpty()
require(realtimeDatabaseUrl.isBlank() || realtimeDatabaseUrl.startsWith("https://")) { "REALTIME_DATABASE_URL must use HTTPS" }
val voipApiBaseUrl = localProperties.getProperty("VOIP_API_BASE_URL").orEmpty().trimEnd('/')
require(
    voipApiBaseUrl.isBlank() ||
        voipApiBaseUrl.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?|http://10\\.0\\.2\\.2(:[0-9]{1,5})?"))
) { "VOIP_API_BASE_URL must use HTTPS (or Android emulator loopback)" }
val updateManifestUrl = localProperties.getProperty(
    "UPDATE_MANIFEST_URL",
    "https://yourolivebranch.ca/api/android/releases/latest"
).trim()
require(updateManifestUrl.matches(Regex("https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*)?"))) {
    "UPDATE_MANIFEST_URL must be a valid HTTPS URL"
}
val releaseProperties = Properties().apply {
    val source = rootProject.file("keystore.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
val releaseKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningReady = releaseKeys.all { !releaseProperties.getProperty(it).isNullOrBlank() }
val automatedVersionCode = providers.environmentVariable("OLIVE_VERSION_CODE").orNull?.toIntOrNull()
val automatedVersionName = providers.environmentVariable("OLIVE_VERSION_NAME").orNull

android {
    lint {
        disable += "TypographyDashes" // ASCII hyphens are an intentional visual text choice.
        disable += "TypographyEllipsis" // Three periods are an intentional visual text choice.
        disable += "LockedOrientationActivity" // Olive intentionally uses a phone-first portrait layout.
    }
    @Suppress("UnstableApiUsage") // AGP marks the language-split API as experimental; disabling splits keeps all translations installed.
    bundle {
        // Include every bundled locale in the base APK so translations do not require a separate language download.
        language { enableSplit = false }
    }
    signingConfigs {
        if (releaseSigningReady) create("oliveRelease") {
            storeFile = rootProject.file(releaseProperties.getProperty("storeFile"))
            storePassword = releaseProperties.getProperty("storePassword")
            keyAlias = releaseProperties.getProperty("keyAlias")
            keyPassword = releaseProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("oliveRelease")
        }
    }
    namespace = "com.olive.app"
    compileSdk = 37
    // Change the app ID or release numbers here; a new app ID also needs a matching Firebase config.
    defaultConfig {
        applicationId = "com.olive.app"
        minSdk = 28
        targetSdk = 37
        // v1.0.0.0 maps to 100000000: major, two-digit minor, two-digit revision, four-digit build.
        versionCode = automatedVersionCode ?: 100000000
        versionName = automatedVersionName ?: "v1.0.0.0"
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
        buildConfigField("String", "GEOAPIFY_API_KEY", "\"$geoapifyApiKey\"")
        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"$mapboxAccessToken\"")
        buildConfigField("String", "REALTIME_DATABASE_URL", "\"$realtimeDatabaseUrl\"")
        buildConfigField("String", "VOIP_API_BASE_URL", "\"$voipApiBaseUrl\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions.unitTests.all { it.jvmArgs("--enable-native-access=ALL-UNNAMED") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // These third-party binaries arrive without removable debug sections. Declaring that they
    // should be packaged intact avoids a misleading strip warning on every debug/test build.
    packaging { jniLibs.keepDebugSymbols += "**/*.so" }
}
val cameraxVersion = "1.6.2"

dependencies {
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    implementation("androidx.camera:camera-video:${cameraxVersion}")
    implementation("androidx.camera:camera-extensions:${cameraxVersion}")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    // App and instrumented tests both need the same Compose version constraints.
    listOf("implementation", "androidTestImplementation").forEach { configuration ->
        add(configuration, platform("androidx.compose:compose-bom:2026.09.00"))
    }
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-installations")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation(project(":ppocr-sdk"))
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    // Media transfers use OkHttp directly; do not rely on Coil's transitive dependency.
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.livekit:livekit-android:2.30.0")
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-transformer:$media3Version")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
val verifyReleaseConfiguration = tasks.register("verifyReleaseConfiguration") {
    group = "verification"
    description = "Checks that Firebase and release-signing files are available before a release build."
    doLast {
        check(firebaseConfigured) { "Release requires app/google-services.json. Local test accounts are debug-only." }
        check(geoapifyApiKey.isNotBlank()) { "Release requires GEOAPIFY_API_KEY for address suggestions." }
        check(mapboxAccessToken.isNotBlank()) { "Release requires MAPBOX_ACCESS_TOKEN for navigation." }
        check(releaseSigningReady) { "Release requires keystore.properties. See docs/DISTRIBUTION.md." }
        check(rootProject.file(releaseProperties.getProperty("storeFile")).isFile) { "Release signing keystore was not found." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseConfiguration) }
