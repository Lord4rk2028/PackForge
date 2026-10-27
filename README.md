# ⚒️ PackForge

<div align="center">

> **Fusiona múltiples addons de Minecraft Bedrock Edition en un solo modpack funcional**  
> *Sin texturas rotas, sin conflictos manuales, sin dolores de cabeza.*

🌐 **Sitio Web Oficial:** [packforge.netlify.app](https://packforge.netlify.app/)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-8.0+_(API_26)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203%20Expressive-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![CI Status](https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/Lord4rk2028/PackForge/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](LICENSE)

</div>

---

## 📖 Acerca de PackForge (About)

**PackForge** es una aplicación nativa para Android diseñada específicamente para la comunidad de **Minecraft Bedrock Edition**. Su objetivo principal es resolver uno de los problemas más comunes y frustrantes para jugadores y creadores: **la incompatibilidad y sobreescritura al intentar jugar con varios addons a la vez**.

Normalmente, combinar addons de forma manual implica abrir archivos comprimidos, descifrar y editar múltiples `manifest.json`, sincronizar UUIDs, fusionar archivos JSON complejos (`blocks.json`, `terrain_texture.json`, `item_texture.json`, `sound_definitions.json`) y renombrar identidades en conflicto para evitar crashes en el motor Bedrock.

**PackForge automatiza todo este proceso en segundos**, analizando las dependencias de Resource Packs (RP) y Behavior Packs (BP), resolviendo colisiones con estrategias configurables y generando un modpack limpio (`.mcaddon`) listo para importar directamente en el juego.

---

## 📱 Recorrido por la Aplicación

Explora cada fase del flujo de PackForge a través de su interfaz en Jetpack Compose & Material 3:

---

### 1️⃣ Importación & Taller de Addons
> **Carga rápida y soporte multiformato:** Arrastra o selecciona archivos `.mcaddon`, `.mcpack` o `.zip` directamente desde el almacenamiento.

<div align="center">
  <img src="screenshots/01-import.jpg" width="300" alt="Pantalla Importar Addons" style="max-width: 85%; border-radius: 16px; border: 1px solid #333;" />
</div>

- 📂 **Explorador integrado:** Compatible con SAF (*Storage Access Framework*) y exploradores de archivos externos.
- ⚡ **Extracción segura:** Desempaquetado optimizado en segundo plano con validación de manifests.

---

### 2️⃣ Mesa de Crafteo & Diagnóstico de Compatibilidad
> **Supervisión predictiva del modpack:** Organiza visualmente tus paquetes activos y mide su tasa de compatibilidad antes de fusionar.

<div align="center">
  <img src="screenshots/03-studio.jpg" width="300" alt="Mesa de Crafteo y Diagnóstico" style="max-width: 85%; border-radius: 16px; border: 1px solid #333;" />
</div>

- 🧱 **Mesa 3x3 Interactiva:** Visualiza las miniaturas y estados de hasta 9 addons activos a la vez.
- 📈 **Índice de Compatibilidad:** Algoritmo que calcula el % de éxito (p. ej. *90% de viabilidad*) clasificando avisos leves vs. errores críticos.

---

### 3️⃣ Centro de Resolución de Conflictos
> **Toma el control absoluto de las colisiones:** Elige estrategias automáticas o inspecciona recurso por recurso.

<div align="center">
  <img src="screenshots/02-conflicts.jpg" width="300" alt="Pantalla de Resolución de Conflictos" style="max-width: 85%; border-radius: 16px; border: 1px solid #333;" />
</div>

- 🎯 **Estrategias en un toque:** Alterna entre `Keep first`, `Keep last` o `Log and skip` para archivos no fusionables.
- 🔍 **Auditoría granular:** Revisa colisiones de texturas, sonidos, animaciones y scripts con indicación del addon ganador.

---

### 4️⃣ Personalización de Interfaz & Temas Bedrock
> **Tu taller, tu estilo:** Personaliza la estética de la app con colores dinámicos y temas inspirados en Minecraft.

<div align="center">
  <img src="screenshots/04-theme.jpg" width="300" alt="Selector de Tema y Paleta de Colores" style="max-width: 85%; border-radius: 16px; border: 1px solid #333;" />
</div>

- 🎨 **Espectro HEX:** Selector cromático completo con soporte de copia y pegado de código hexadecimal.
- 🌙 **Modo Oscuro Adaptativo:** Optimizado para pantallas AMOLED con consumo mínimo de batería.

---

## ⚡ ¿Qué hace único a PackForge?

| Característica | Fusión Manual / Otros Zips | ⚒️ PackForge |
|---|---|---|
| **Manejo de Manifests** | Propenso a errores de UUID y sintaxis | Generación y sincronización de UUIDs v4 automática |
| **Archivos JSON Críticos** | Sobrescritura destructiva | Fusión profunda inteligente (`JsonDeepMerger`) |
| **Colisiones de Texturas** | Una textura elimina la otra | Detección granular y resolución con selección de ganador |
| **Diagnóstico de Salud** | Pruebas a ciegas en el juego | `PackForgeHealer` + `PackForgeValidator` previos al juego |
| **Exportación** | Compresión manual .zip y renombramiento | Exportación optimizada en un solo `.mcaddon` ejecutable |

---

## 🚀 Motor de Fusión Bedrock (`domain/engine`)

El núcleo técnico de PackForge implementa una serie de componentes de ingeniería especializados para Minecraft Bedrock:

- **`PackForgeOrchestrator`**: Administra el pipeline completo: descompresión, indexado, análisis de compatibilidad, fusión de subcomponentes y empaquetado final.
- **`JsonDeepMerger` & `LenientJson`**: Fusión no destructiva de árboles JSON complejos, tolerando discrepancias comunes de sintaxis presentes en addons comunitarios.
- **`ConflictEngine` & `ConflictRegistry`**: Detección anticipada de choques de identificadores, colisiones de recetas, loot tables, animaciones y scripts.
- **`PackForgeHealer`**: Módulo de autodiagnóstico post-merge que detecta referencias rotas y sugiere coincidencias difusas (*fuzzy matching*) sin corromper el contenido original.
- **`PackForgeValidator`**: Validador estricto que asegura que el modpack final cumpla los estándares exigidos por el motor de Bedrock antes de permitir su exportación.
- **`BedrockCriticalFilesMerger`**: Algoritmos dedicados para fusionar armónicamente:
  - `blocks.json` y `terrain_texture.json`
  - `item_texture.json`
  - `sound_definitions.json` y `sounds.json`
  - Definiciones de entidades y attachables en Resource & Behavior Packs

---

## 🧪 Flujo de Trabajo

```mermaid
flowchart TD
    A["📥 Selección de Addons (.mcaddon, .zip)"] --> B["🔍 AddonExtractor & AddonParser"]
    B --> C["📊 Diagnóstico de Compatibilidad & ConflictEngine"]
    C --> D{"¿Hay Conflictos?"}
    D -- "Sí" --> E["⚠️ Resolución (Keep First / Last / Custom)"]
    D -- "No" --> F["🧩 Fusión Profunda (PackForgeOrchestrator)"]
    E --> F
    F --> G["🩺 PackForgeHealer & PackForgeValidator"]
    G --> H["📦 Exportación de Modpack (.mcaddon)"]
    H --> I["🎮 Importación automática en Minecraft"]
```

---

## 📲 Instalación y Descargas

### 🔹 Opción 1: Descargar APK compilado (Recomendado)
Puedes obtener el archivo `.apk` de prueba generado automáticamente por nuestro pipeline de integración continua:
1. Dirígete a la pestaña de [**GitHub Actions (Android CI)**](https://github.com/Lord4rk2028/PackForge/actions).
2. Haz clic en la ejecución más reciente en la rama `master`.
3. En la sección **Artifacts**, descarga el archivo **`debug-apk`**.

### 🔹 Opción 2: Compilar desde el código fuente

```bash
# 1. Clonar el repositorio
git clone https://github.com/Lord4rk2028/PackForge.git
cd PackForge

# 2. Dar permisos de ejecución a Gradle wrapper (Linux/macOS)
chmod +x gradlew

# 3. Compilar APK Debug
./gradlew assembleDebug

# El APK se genera en:
# app/build/outputs/apk/debug/app-debug.apk
```

Para correr las suites de pruebas unitarias locales:
```bash
./gradlew test
```

---

## 🛠️ Especificaciones Técnicas

- **Plataforma objetivo:** Android 8.0+ (API 26 mínima, API 35 destino)
- **Lenguaje:** Kotlin 2.3.21
- **UI Toolkit:** Jetpack Compose con Compose BOM 2025 + Material 3 Expressive (1.4.0)
- **Patrón de Arquitectura:** Clean Architecture + MVVM (StateFlow, Coroutines)
- **Base de Datos Local:** Room Database 2.8.4 con KSP
- **Almacenamiento Clave-Valor:** DataStore Preferences 1.1.1
- **Carga Asíncrona de Imágenes:** Coil Compose 2.6.0
- **Networking:** Retrofit 2.11 + OkHttp 4.12 + Gson
- **Compilador / Build Tool:** Gradle 9.7.1 + Android Gradle Plugin 9.3.1

---

## 📄 Licencia (License)

Este proyecto se distribuye bajo la licencia **MIT**. Puedes consultar el texto completo en el archivo [`LICENSE`](LICENSE).

```text
MIT License
Copyright (c) 2026 Lord4rk2028 (PackForge)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software...
```

---

## ⚠️ Aviso Legal & Descargo de Responsabilidad

- **Minecraft** es una marca comercial registrada de **Mojang Synergies AB / Microsoft Corporation**.
- **PackForge** es un desarrollo independiente que **no** está afiliado, respaldado, autorizado ni vinculado formalmente a Mojang ni a Microsoft.
- PackForge **no distribuye ni comercializa recursos creativos de terceros**: actúa exclusivamente como herramienta técnica en el dispositivo del usuario para fusionar archivos que este provee legítimamente.

---

<div align="center">
  <sub>Desarrollado con dedicación para los creadores y jugadores de Minecraft Bedrock.</sub><br/>
  <sub>¿Encontraste un problema o tienes sugerencias? ¡Abre una <a href="https://github.com/Lord4rk2028/PackForge/issues">Issue</a> o envía un Pull Request!</sub>
</div>
