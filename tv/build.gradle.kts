plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

// Jarvis on the bedroom TV: a launcher in the phone app's look, talked to with the remote's mic.
// The TV is a Skyworth on Android 8 (API 26) with a 32-bit chip and 2 GB, so no WebRTC, no
// Meta toolkit and no native code here.
android {
  namespace = "app.jarvis.tv"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.jarvis.tv"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  buildTypes {
    // Compose is slow unshrunk on an old 32-bit TV chip: release is the build to try it with,
    // signed with the debug key so it installs over adb like a debug build.
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
}

dependencies {
  implementation(project(":core"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.junit)
}
