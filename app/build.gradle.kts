plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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
    
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
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
