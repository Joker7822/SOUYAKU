import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.epic.souyaku"

    val tokenCallServerUrl = providers.gradleProperty("SOUYAKU_TOKEN_CALL_SERVER_URL")
        .orElse(providers.environmentVariable("SOUYAKU_TOKEN_CALL_SERVER_URL"))
        .orElse("wss://souyaku-token-relay.onrender.com")
        .get()
    compileSdk = 36

    defaultConfig {
        applicationId = "com.epic.souyaku.interpreter"
        minSdk = 26
        targetSdk = 36
        versionCode = 32
        versionName = "1.3.27"
        buildConfigField("String", "TOKEN_CALL_SERVER_URL", "\"${tokenCallServerUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
