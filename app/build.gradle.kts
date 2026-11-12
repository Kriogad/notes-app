import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.notes.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.notes.app"
        minSdk = 26
        targetSdk = 36
        // Номер сборки GitHub — чтобы каждая новая версия ставилась поверх старой
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    // Постоянный ключ: без него Android не даст обновить приложение поверх старой версии
    signingConfigs {
        create("notes") {
            storeFile = file("notes.jks")
            storePassword = "notes123"
            keyAlias = "notes"
            keyPassword = "notes123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("notes")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
}
