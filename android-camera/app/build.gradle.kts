// app/build.gradle.kts — configuração do módulo do app.
plugins {
    // Com AGP 9, este único plugin já compila Kotlin (Kotlin "embutido").
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.oldcam.camera"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.oldcam.camera"
        minSdk = 26          // Android 8.0 — decisão registrada no CLAUDE.md
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 encolhe e ofusca o APK de release. Regras extras em proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        // ViewBinding gera uma classe por layout (ex.: ActivityMainBinding), evitando findViewById.
        viewBinding = true
        buildConfig = true
    }

    // Bytecode Java 17 (o Kotlin segue o mesmo alvo). Compila com o JDK 17+ que estiver
    // instalado — o Android Studio já traz o JDK 21.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Falhas de lint graves quebram o build — melhor descobrir cedo.
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    // RootEncoder: encoder H.264 por hardware + cliente RTSP/RTSPS.
    implementation(libs.rootencoder.library)
    // extra-sources traz o CameraXSource (fonte de vídeo baseada em CameraX).
    implementation(libs.rootencoder.extra.sources)
    // Fixamos o CameraX numa versão estável mais nova que a usada pelo RootEncoder.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
