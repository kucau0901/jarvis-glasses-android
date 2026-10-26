import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

// Machine-local values (gitignored). "0" is DAT's Developer Mode placeholder: attestation is
// skipped there, which is how this app runs on the owner's own glasses.
val localProperties: Properties =
    Properties().apply {
      rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

android {
  namespace = "app.jarvis.glasses"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.jarvis.glasses"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
    // WebRTC's native libraries are large; current phones (and the Apple-silicon emulator) are arm64.
    ndk { abiFilters += "arm64-v8a" }

    // As string resources: a numeric android:value in the manifest is read back as an Integer,
    // which the toolkit sees as no ID at all (facebook/meta-wearables-dat-android#80).
    resValue("string", "mwdat_application_id", localProperties.getProperty("mwdat_application_id", "0"))
    resValue("string", "mwdat_client_token", localProperties.getProperty("mwdat_client_token", "0"))
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    resValues = true
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.stream.webrtc)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  debugImplementation(libs.mwdat.mockdevice)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
}
