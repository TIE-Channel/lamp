plugins {
  id("com.android.application")
}

android {
  namespace = "home.lampremote"
  compileSdk = 36

  defaultConfig {
    applicationId = "home.lampremote"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // Personal app: the debug key is enough to install it over adb.
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    abortOnError = false
  }
}

dependencies {
  testImplementation("junit:junit:4.13.2")
  // The Android stub of org.json does nothing in JVM tests.
  testImplementation("org.json:json:20240303")
}
