plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // No google-services plugin: Firebase is configured in code (see FirebaseConfig.kt),
    // so the app builds without a google-services.json file.
}

android {
    namespace = "com.kidguard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kidguard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("vc") as String?)?.toInt() ?: 2
        versionName = (project.findProperty("vn") as String?) ?: "1.1"
    }

    // Fixed signing key so every build has the SAME signature.
    // This lets new versions install OVER the old one (and over-the-air updates work)
    // without uninstalling. It's a sign-only key for this sideloaded app.
    signingConfigs {
        create("cellhome") {
            storeFile = file("cellhome.keystore")
            storePassword = "cellhome2026"
            keyAlias = "cellhome"
            keyPassword = "cellhome2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("cellhome")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("cellhome")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    implementation("com.google.android.gms:play-services-location:21.3.0")
}
