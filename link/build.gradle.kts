plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

// What the watch and the phone app say to each other over Wear OS's Data Layer: a question from
// the watch, and the phone's answer to it as it streams from Jarvis. Plain Kotlin, no Play
// services: each side sends the bytes with its own MessageClient.
android {
  namespace = "app.jarvis.link"
  compileSdk = 36

  defaultConfig { minSdk = 26 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
}
