plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

// Jarvis on the Galaxy Watch (Wear OS 6, a 480 × 480 round screen, 32-bit, 2 GB): the watch hears
// the question with its own recogniser, and the phone app asks Jarvis with its sign-in, over Wear
// OS's Data Layer (:link). So no WebRTC, no native code and no Jarvis client here.
android {
  namespace = "app.jarvis.wear"
  compileSdk = 36

  defaultConfig {
    // The phone app's: Wear OS's Data Layer joins only apps of the same id and signing key.
    applicationId = "app.jarvis.glasses"
    minSdk = 30
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
}

dependencies {
  implementation(project(":core"))
  implementation(project(":link"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.navigation)
  implementation(libs.wear.tiles)
  implementation(libs.androidx.concurrent.futures)
  implementation(libs.wear.protolayout)
  implementation(libs.wear.protolayout.material3)
  implementation(libs.wear.complications.data.source)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.play.services.wearable)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
