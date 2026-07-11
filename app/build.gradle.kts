plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.falconone.bodycamserver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.falconone.bodycamserver"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    // NanoHTTPD va incorporado como fuente en src/main/java/fi/iki/elonen con un
    // patch: sin el reverse DNS bloqueante que retrasaba ~10 s cada peticion.
    implementation("io.agora.rtc:full-sdk:4.3.0")
}
