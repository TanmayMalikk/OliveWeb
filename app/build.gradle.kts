import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val firebaseConfigured = file("google-services.json").exists() && providers.gradleProperty("oliveLocalTest").orNull != "true"
if (firebaseConfigured) pluginManager.apply("com.google.gms.google-services")
// Optional separate output directory for checks while Android Studio indexes the default build.
providers.gradleProperty("oliveBuildDir").orNull?.let { layout.buildDirectory.set(file(it)) }
val localProperties = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
val placesApiKey = localProperties.getProperty("PLACES_API_KEY").orEmpty()
require(placesApiKey.matches(Regex("[A-Za-z0-9_-]*"))) { "PLACES_API_KEY contains unsupported characters" }
val releaseProperties = Properties().apply {
    val source = rootProject.file("keystore.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
val releaseKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningReady = releaseKeys.all { !releaseProperties.getProperty(it).isNullOrBlank() }

android {
    lint {
        disable += "TypographyDashes" // ASCII hyphens are an intentional visual text choice.
        disable += "TypographyEllipsis" // Three periods are an intentional visual text choice.
        disable += "LockedOrientationActivity" // Olive intentionally uses a phone-first portrait layout.
    }
    @Suppress("UnstableApiUsage") // Hide the experimental API warning; this setting includes all languages so users can switch languages in the app.
    bundle {
        // Every supported locale ships with the base APK because users can change language inside Olive.
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
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
        buildConfigField("String", "PLACES_API_KEY", "\"$placesApiKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    // App and instrumented tests both need the same Compose version constraints.
    listOf("implementation", "androidTestImplementation").forEach { configuration ->
        add(configuration, platform("androidx.compose:compose-bom:2026.09.00"))
    }
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation(platform("com.google.firebase:firebase-bom:35.0.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.android.libraries.places:places:6.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
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
        check(releaseSigningReady) { "Release requires keystore.properties. See docs/DISTRIBUTION.md." }
        check(rootProject.file(releaseProperties.getProperty("storeFile")).isFile) { "Release signing keystore was not found." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseConfiguration) }
