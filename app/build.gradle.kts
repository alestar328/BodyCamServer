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
        versionCode = 5
        versionName = "1.4"

        // Version piloto sin identidad (2026-09-29): para el manager, que prueba en
        // otro pais sin ordenador y no puede dar de alta la unidad (el alta hoy
        // solo va por adb). Con true la unidad sigue aceptando comandos en claro de
        // un telefono sin alta aunque algun dia se exija el canal cifrado. Se pide
        // en local.properties (PILOTO_SIN_IDENTIDAD=true), nunca por defecto.
        buildConfigField(
            "boolean", "PILOTO_SIN_IDENTIDAD",
            (localProperties.getProperty("PILOTO_SIN_IDENTIDAD") ?: "false").toBoolean().toString(),
        )

        // Solo ARM: x86/x86_64 son de emulador y eran ~90 MB del APK de 217 MB que
        // el manager tenia que pasar a la unidad por Bluetooth.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            // Extensiones de Agora que la app no activa nunca (IA de ruido/eco,
            // segmentacion, caras, audio espacial, AV1...). Agora documenta que se
            // pueden quitar; si algun dia se usa una, sacarla de esta lista.
            excludes += "**/libagora_*_extension.so"
            // Las .so van comprimidas dentro del APK: mas lento de instalar,
            // mucho menos que transferir.
            useLegacyPackaging = true
        }
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
