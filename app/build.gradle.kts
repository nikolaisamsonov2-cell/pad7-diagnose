plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "at.nikolai.diagnose"
    compileSdk = 35

    defaultConfig {
        applicationId = "at.nikolai.diagnose"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Fester Schlüssel aus der CI (siehe .github/workflows/build.yml), damit sich neue Versionen
    // über alte installieren lassen. Lokal ohne Schlüssel wird mit dem Debug-Schlüssel signiert.
    val keystorePath = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "pad7"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/**",
                "META-INF/*.version",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("androidx.work:work-runtime:2.9.1")
}
