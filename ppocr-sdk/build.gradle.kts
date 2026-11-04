// Contains the build.gradle code for this part of Olive.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.paddle.ocr"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.keepDebugSymbols += setOf(
            "**/libc++_shared.so",
            "**/libonnxruntime.so",
            "**/libonnxruntime4j_jni.so",
            "**/libopencv_java4.so",
        )
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.opencv:opencv:4.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.19.1")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test:monitor:1.8.0")
    androidTestImplementation("junit:junit:4.13.2")
}
