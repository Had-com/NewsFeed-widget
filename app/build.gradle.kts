plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// "Send to developer" (docs/REPORT_SERVER.md): the collector's address and its app key are NOT in the repository,
// the build injects them. Order: Gradle property (-PREPORT_BASE_URL=... -PREPORT_APP_KEY=...), then an environment
// variable of the same name (CI secrets), else "". With either one empty the app hides the Send button.
// Values are trimmed; a value with control characters is ignored (treated as empty) rather than altered.
fun reportBuildValue(name: String): String =
    listOfNotNull(project.findProperty(name) as String?, System.getenv(name))
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && it.none { c -> c.isISOControl() } }
        ?: ""

fun asBuildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.newsfeed.widget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.newsfeed.widget"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("buildVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "1.0"

        buildConfigField("String", "REPORT_BASE_URL", asBuildConfigString(reportBuildValue("REPORT_BASE_URL")))
        buildConfigField("String", "REPORT_APP_KEY", asBuildConfigString(reportBuildValue("REPORT_APP_KEY")))
    }

    signingConfigs {
        getByName("debug") {
            // Committed on purpose — see keystore/README or Task 1 of the self-update plan.
            // Every build (CI and local) must sign with the SAME key or a downloaded "update"
            // APK can never install over the one already running (Android refuses a signature
            // mismatch). Gradle's implicit per-machine debug keystore doesn't guarantee that
            // across CI's fresh-machine-every-run environment.
            storeFile     = file("../keystore/newsfeed-debug.keystore")
            storePassword = "newsfeed-debug"
            keyAlias      = "newsfeed-debug-key"
            keyPassword   = "newsfeed-debug"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
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

    // Real HTML DOM parser for full-article extraction (FetchFullArticleCallback) — replaces a
    // regex-based tag stripper that had no way to distinguish an ad/related-content block sitting
    // inside <article> from real body text, only whole noise *tags* like <nav>/<script>.
    implementation("org.jsoup:jsoup:1.17.2")

    // Unit testing
    testImplementation("junit:junit:4.13.2")

    // Real org.json implementation for local unit tests. CrashLogStoreTest exercises
    // CrashLogStore.recordsToJson()/recordsFromJson(), which use org.json.JSONObject/JSONArray.
    // Those classes ship inside android.jar, and testDebugUnitTest runs against the "mockable
    // android.jar" whose method bodies are stubbed to throw RuntimeException("... not mocked")
    // - true for every android.jar class, org.json included, not just the usual Log/Color
    // examples. This pulls in the original json.org reference implementation (source/binary
    // compatible with org.json's JSONObject/JSONArray API) so the unit test classpath has a
    // real, working implementation instead of the throwing stub. testImplementation only, so
    // it never ships in the release APK.
    testImplementation("org.json:json:20231013")
}
