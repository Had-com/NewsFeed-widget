plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.readyou.widget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.readyou.widget"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        setProperty("archivesBaseName", "NewsFeed-v${versionName}-${versionCode}")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // debug APK keeps same naming pattern
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    // Glance (widget)
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Kotlin serialization (for config JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Drag-to-reorder in Compose
    implementation("sh.calvin.reorderable:reorderable:2.3.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // HTTP client for RSS fetching
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
