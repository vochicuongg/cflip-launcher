plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Import after the plugins block: in a Gradle Kotlin script, writing
// java.util.Properties() inline fails with "Unresolved reference: util"
// because `java` collides with Gradle's own java extension. An explicit
// import avoids that collision.
import java.util.Properties

// Release signing (added 25/10/2026): reads the developer's own keystore
// from keystore.properties at the project root, which is NEVER committed
// (see .gitignore and keystore.properties.example). If that file is absent
// - e.g. on GitHub Actions or a fresh checkout - the release build simply
// has no signing config, exactly as before, and nothing fails at sync time.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
val hasReleaseKey = keystorePropertiesFile.exists() &&
    keystoreProperties.getProperty("storePassword") != null

android {
    namespace = "com.cuong.cflip"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cuong.cflip"
        // Flip 5 ships Android 13 / One UI 5.1.1 -> minSdk 33 covers Flip 5/6/7.
        minSdk = 33
        targetSdk = 35
        versionCode = 66
        versionName = "3.8.16-samsung-local"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(keystoreProperties.getProperty("storeFile", "../cflip-release.keystore"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias", "cflip")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
