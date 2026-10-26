import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Read secrets from the repo-root .env (one level above android/)
fun envVarOrNull(key: String): String? {
    val envFile = rootProject.file("../.env")
    if (envFile.exists()) {
        envFile.readLines().forEach { line ->
            if (!line.startsWith("#") && line.contains("=")) {
                val (k, v) = line.split("=", limit = 2)
                if (k.trim() == key && v.isNotBlank()) return v.trim()
            }
        }
    }
    return System.getenv(key)
}

fun envVar(key: String): String = envVarOrNull(key) ?: error("Missing env var: $key")

// Release signing key (RELEASING.md). Play and the website APK are signed with
// the same key, so users can move between them. Without it, release builds
// come out unsigned.
val releaseKeystore = envVarOrNull("ANDROID_KEYSTORE_PATH")

android {
    namespace   = "dev.brainfence"
    compileSdk  = 36

    defaultConfig {
        applicationId   = "dev.brainfence"
        minSdk          = 26
        targetSdk       = 36
        // Every Play upload needs a higher versionCode (RELEASING.md).
        versionCode     = 1
        versionName     = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL",            "\"${envVar("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${envVar("SUPABASE_PUBLISHABLE_KEY")}\"")

    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile     = file(releaseKeystore)
                storePassword = envVar("ANDROID_KEYSTORE_PASSWORD")
                keyAlias      = envVar("ANDROID_KEY_ALIAS")
                keyPassword   = envVar("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose      = true
        buildConfig  = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// Cross-platform test cases shared with the macOS and Windows clients.
tasks.withType<Test>().configureEach {
    systemProperty("brainfence.fixtures", rootProject.file("../shared/fixtures").absolutePath)
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)

    // Security
    implementation(libs.androidx.security.crypto)

    // Google Play Services — Location & Geofencing
    implementation(libs.play.services.location)

    // OpenStreetMap
    implementation(libs.osmdroid)

    // PowerSync
    implementation(libs.powersync.core)
    implementation(libs.powersync.connector.supabase)

    // Testing
    testImplementation(libs.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
