plugins {
    id("com.android.application")
}

android {
    namespace = "com.geoffchan.bigglucose"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geoffchan.bigglucose"
        // Watch Face Format v2 requires Wear OS 5 (API 34) or newer.
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // There is no Java or Kotlin in this project -- the watch face is
    // entirely declarative XML in res/raw/watchface.xml.
    buildFeatures {
        buildConfig = false
    }
}
