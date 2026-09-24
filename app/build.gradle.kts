import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val firebaseConfigured = file("google-services.json").exists() && providers.gradleProperty("oliveLocalTest").orNull != "true"
if (firebaseConfigured) apply(plugin = "com.google.gms.google-services")
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
        // Set oliveCatalogEndpoint to your HTTPS menu backend when the real restaurant provider is ready.
        val catalogEndpoint = providers.gradleProperty("oliveCatalogEndpoint").orElse("").get()
        require(catalogEndpoint.isEmpty() || catalogEndpoint.matches(Regex("https://[A-Za-z0-9./_-]+")))
        buildConfigField("String", "CATALOG_ENDPOINT", "\"$catalogEndpoint\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.android.libraries.places:places:5.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
val verifyReleaseConfiguration by tasks.registering {
    doLast {
        check(firebaseConfigured) { "Release requires app/google-services.json. Local test accounts are debug-only." }
        check(releaseSigningReady) { "Release requires keystore.properties. See docs/DISTRIBUTION.md." }
        check(rootProject.file(releaseProperties.getProperty("storeFile")).isFile) { "Release signing keystore was not found." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseConfiguration) }
