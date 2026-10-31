plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tradutorvivo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tradutorvivo"
        minSdk = 29          // Android 10+ (necessário para capturar o áudio interno)
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Chave fixa: permite instalar atualizações por cima da versão anterior
    signingConfigs {
        create("fixa") {
            storeFile = file("tradutor.keystore")
            storePassword = "tradutor123"
            keyAlias = "tradutor"
            keyPassword = "tradutor123"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixa") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
