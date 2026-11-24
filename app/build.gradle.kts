plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tonda.tether"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tonda.tether"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Only one dependency: AppCompat, for AppCompatActivity. The proxy is
    // plain sockets and the UI is stock Android Views — no paid libraries,
    // no networking libraries, fewer surprises and a smaller APK.
    implementation("androidx.appcompat:appcompat:1.7.0")
}
