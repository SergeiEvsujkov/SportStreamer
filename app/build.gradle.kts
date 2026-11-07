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
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.sportstreamer"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"

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

    /**
     * Две версии приложения:
     * rustore — для RuStore, с SDK обновлений;
     * direct  — обычная сборка без магазина и без обращений к RuStore.
     */
    flavorDimensions += "store"
    productFlavors {
        create("rustore") {
            dimension = "store"
            versionNameSuffix = "-rustore"
        }
        create("direct") {
            dimension = "store"
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

    // SDK обновлений RuStore подключается только в варианте rustore.
    "rustoreImplementation"(platform("ru.rustore.sdk:bom:2026.10.01"))
    "rustoreImplementation"("ru.rustore.sdk:appupdate")

    // Палитра выбора цвета собирается из MaterialCardView, отдельная библиотека не нужна.
}
