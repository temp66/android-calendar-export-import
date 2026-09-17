plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.caltransfer.providerdump"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.caltransfer.providerdump"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // Minification is deliberately off: only debug builds have been run on a device, and an
        // unverified R8 configuration would be worse than none.
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    testOptions {
        // The unit tests never call into android.* ; they drive the fake provider instead.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    // No third-party runtime dependencies: JSON comes from the platform's org.json.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
