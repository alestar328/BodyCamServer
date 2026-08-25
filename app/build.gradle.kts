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
        versionCode = 2
        versionName = "1.1"
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

    lint {
        // La app se instala por adb en la unidad bodycam, no se distribuye por
        // Google Play: el minimo de targetSdk 33 que exige la tienda no aplica.
        // targetSdk 28 es deliberado (almacenamiento legacy y los servicios sin
        // foregroundServiceType, ver AndroidManifest).
        disable.add("ExpiredTargetSdkVersion")
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        // Atado a Kotlin 1.9.22 (ver build.gradle.kts raíz): si se sube Kotlin
        // hay que subir esta versión en el mismo commit o el build falla.
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    // NanoHTTPD va incorporado como fuente en src/main/java/fi/iki/elonen con un
    // patch: sin el reverse DNS bloqueante que retrasaba ~10 s cada peticion.
    implementation("io.agora.rtc:full-sdk:4.3.0")

    // Compose, solo para el panel de control (MainActivity). RecordingActivity
    // sigue en Views: lleva un TextureView atado a Camera2 y las capas giradas
    // del overlay, que no ganan nada pasando a Compose.
    //
    // La app del móvil (AeriaNexusPrototype) ya es Compose entera: esto deja las
    // dos aplicaciones del proyecto en la misma pila de UI.
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
