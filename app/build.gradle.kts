import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fatih.yuztanima"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fatih.yuztanima"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Model dosyaları sıkıştırılmamalı (TFLite / MediaPipe doğrudan bellekten okur)
    androidResources {
        noCompress += listOf("tflite", "task")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Açık kaynak: yüz bulma + göz kırpma (Apache 2.0)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    // Açık kaynak: MobileFaceNet yüz imzası (Apache 2.0)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}

// ---- Açık kaynak modelleri derleme öncesi otomatik indir ----
val modelFiles = mapOf(
    "mobile_face_net.tflite" to
        "https://raw.githubusercontent.com/estebanuri/face_recognition/dbf9732725cf1ab6e06b412651bb4858b5dfe0df/android/app/src/main/assets/mobile_face_net.tflite",
    "face_landmarker.task" to
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
)

val downloadModels by tasks.registering {
    val assetsDir = file("src/main/assets")
    outputs.dir(assetsDir)
    doLast {
        assetsDir.mkdirs()
        modelFiles.forEach { (name, url) ->
            val target = File(assetsDir, name)
            if (!target.exists() || target.length() < 100_000) {
                println("Model indiriliyor: $name")
                URL(url).openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadModels) }
