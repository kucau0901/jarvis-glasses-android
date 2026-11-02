plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.compose)
}

// The look the phone app and the TV share: Even's colours and tiles, Hark's chat colours, the
// dot-matrix LCD and the line icons. Pure Compose, so it runs on the TV's Android 8.
android {
  namespace = "app.jarvis.core"
  compileSdk = 36

  defaultConfig { minSdk = 26 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  api(libs.androidx.compose.ui)
  api(libs.androidx.compose.material3)
}
