plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lately.msgarchive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lately.msgarchive"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
        testInstrumentationRunner = "com.lately.msgarchive.StoreCheck"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Framework APIs only: a message archive should carry as little third-party code as possible.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
