import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ruta y contrasenas del keystore de release, leidas de local.properties, que no
// va a git. Mismo keystore que AeriaNexusPrototype: una sola identidad de firma
// para las dos apps del proyecto.
val localProperties = Properties().apply {
    val archivo = rootProject.file("local.properties")
    if (archivo.exists()) {
        archivo.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.falconone.bodycamserver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.falconone.bodycamserver"
        minSdk = 26
        targetSdk = 28
        versionCode = 4
        versionName = "1.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    // Si falta cualquiera de las cuatro propiedades o el fichero no esta, no se
    // declara la config y el release sale sin firmar: preferible a romper el
    // build en una maquina que no tenga la clave.
    val releaseKeystore = localProperties.getProperty("RELEASE_KEYSTORE_FILE")
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = localProperties.getProperty("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                // Con minSdk 26 el esquema v2 es suficiente y es el que acaba
                // verificando (AGP omite el v1 aunque se pida). Se deja pedido
                // por si algun dia baja el minSdk.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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
        // Necesario para BuildConfig.DEBUG: el alta de identidad por intent solo
        // existe en compilaciones de depuracion (ver MainActivity).
        buildConfig = true
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
