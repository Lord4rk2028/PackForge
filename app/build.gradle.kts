import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ── Firma de release (opcional) ──────────────────────────────────────────
// Crea un "keystore.properties" en la raíz del repo (ya está en .gitignore) con:
//   storeFile=../mi-keystore.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// Si el archivo no existe, "assembleRelease" se ensambla sin firmar
// (útil para probar R8); cuando tengas keystore, la app sale firmada.
// Nota: hay que importar Properties porque "java.util.Properties" (FQN) no
// resuelve aquí: "java" shadowa con la extensión del plugin Java/AGP.
val keystoreProps: Properties? = rootProject.file("keystore.properties").let { f ->
    if (f.exists()) Properties().apply { f.inputStream().use { load(it) } } else null
}

android {
    namespace = "com.packforge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.packforge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 191
        versionName = "1.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // R8 (código) + resource shrinking (recursos): APK más ligero en release
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Necesario para PackForgeLog (usa BuildConfig.DEBUG en vez de un flag fijo)
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Nota: en máquinas cuyo PATH incluye rutas con espacios (p.ej. "Microsoft VS Code"),
        // el runner de tests JVM puede fallar ("main class VS"). Se resuelve lanzando Gradle
        // con un PATH sin rutas con espacios, no hardcodeando rutas del sistema aquí.
    }
}

dependencies {
    implementation(libs.coil.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.material)
    
    // DataStore para preferencias de tema
    implementation(libs.androidx.datastore.preferences)

    // Room dependencies with KSP
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    implementation(libs.okhttp)

    // Gson es dependencia DIRECTA: la app lo usa en producción (Gson() en
    // MergeForegroundService, PackForgeViewModel, StudioScreen). Antes llegaba
    // transitiva desde retrofit-converter-gson; al quitar Retrofit hay que pedirlo.
    implementation(libs.gson)
    testImplementation(libs.junit)
    // En tests unitarios locales, org.json viene del mockable-android.jar (stubs vacíos);
    // se añade la implementación real para poder parsear JSON en los tests.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ─── ALIASES DE TAREAS DE TEST ─────────────────────────────
// Permite invocaciones tipo ":app:assemble :app:unitTestClasses :app:androidTestClasses"
// que buscan tareas de compilación de tests. El nombre canónico de AGP es
// "assembleUnitTest" (unit tests) y "assembleAndroidTest" (instrumented tests).
tasks.register("unitTestClasses") {
    group = "verification"
    description = "Compila las clases de tests unitarios (alias de assembleUnitTest)."
    dependsOn("assembleUnitTest")
}

tasks.register("androidTestClasses") {
    group = "verification"
    description = "Compila las clases de tests instrumentados (alias de assembleAndroidTest)."
    dependsOn("assembleAndroidTest")
}

// Ubicación de los esquemas Room exportados (exportSchema = true en PackForgeDatabase).
// Se versionan en git y permiten validar migraciones y auditar
// cambios de esquema en cada PR que toque entidades.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
