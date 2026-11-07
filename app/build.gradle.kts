plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.example.sportstreamer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.sportstreamer"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Настройка viewBinding ровно одна: раньше был ещё дублирующий блок viewBinding { enable = true }.
    buildFeatures {
        viewBinding = true
    }

    // buildToolsVersion не задаём: версию выбирает AGP, лишняя привязка к 36.1.0 не нужна.
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    implementation("com.github.pedroSG94.RootEncoder:library:2.6.7")
    // extra-sources добавляет CameraX/UVC-источники.
    implementation("com.github.pedroSG94.RootEncoder:extra-sources:2.6.7")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // QR-сопряжение удалённого пульта: генерация кода и сканирование камерой.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Палитра выбора цвета собирается из MaterialCardView, отдельная библиотека не нужна.
}
