# ⚒️ PackForge

**PackForge** es una app para Android que fusiona múltiples addons de Minecraft Bedrock Edition en **un solo modpack funcional**, listo para importar y jugar. Si alguna vez intentaste juntar dos o más addons a mano y terminaste con texturas rotas, bloques que desaparecen o el juego crasheando al cargar el mundo… para eso existe PackForge.

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin 2.3.21"/>
  <img src="https://img.shields.io/badge/Android%20(min)-8.0-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Min SDK 26"/>
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-green?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Compose Material 3"/>
  <img src="https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white" alt="GitHub Actions"/>
  <img src="https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge" alt="MIT License"/>
</p>

---

## 📱 Capturas

<p align="center">
  <img src="https://www.genspark.ai/api/files/s/XcA2A8fJ" width="280" alt="Pantalla Importar"/>
  &nbsp;&nbsp;&nbsp;
  <img src="https://www.genspark.ai/api/files/s/QcmfA8zG" width="280" alt="Pantalla Exportar"/>
  &nbsp;&nbsp;&nbsp;
  <img src="https://www.genspark.ai/api/files/s/X6JyUYPZ" width="280" alt="Pantalla Studio"/>
</p>

<p align="center"><i>Izquierda a derecha: Selector de addons (Importar), Detalles del modpack (Exportar) y Gestión de fuentes Bedrock (Studio).</i></p>

> 📌 **Nota sobre las capturas:** si las imágenes no se renderizan en GitHub, súbelas a `/screenshots/` dentro del repositorio y reemplaza las URLs por rutas locales (ej. `screenshots/import.png`).

---

## 🧩 ¿Qué hace?

- **Toma varios addons** (`.mcaddon` / `.zip`) de Minecraft Bedrock.
- **Los fusiona de verdad**: combina bloques, ítems, texturas, sonidos, recetas, entidades y scripts — no se limita a comprimirlos juntos.
- **Resuelve conflictos automáticamente** (por ejemplo, dos addons que modifican el mismo bloque o usan el mismo nombre de textura) y te muestra exactamente qué se resolvió y cómo.
- **Genera un único `.mcaddon`** listo para abrir directamente con Minecraft e importar.

## 🎯 ¿Por qué PackForge y no hacerlo a mano?

Fusionar addons manualmente implica editar manifests, resolver IDs duplicados, unir archivos JSON sin romper la sintaxis y vincular correctamente el behavior pack con el resource pack. Un solo error en cualquiera de esos pasos hace que Minecraft rechace el paquete o cargue el mundo con errores. **PackForge automatiza todo el proceso.**

---

## 🚀 Características

- **Fusión real de contenido** — Motor que desempaca, analiza y combina el contenido interno de cada addon (no un simple zip conjunto).
- **Motor de conflictos** — Detecta colisiones de identificadores, texturas y scripts mediante `ConflictEngine` y las registra en un reporte legible.
- **Remapeo inteligente de IDs** — `IdentifierRemapper` evita colisiones renombrando identificadores duplicados sin romper referencias.
- **Fusión profunda de JSON** — `JsonDeepMerger` une manifests y archivos de configuración preservando la sintaxis válida.
- **Generación automática de manifests** — `ManifestGenerator` crea manifests válidos y enlaza behavior + resource packs.
- **Análisis de compatibilidad Bedrock** — Evalúa versiones de Minecraft y formatos de manifest (`BedrockCompatibilityAnalyzer`).
- **Resolución de dependencias de entidades** — `EntityDependencyResolver` mantiene coherencia entre entidades, spawn rules y más.
- **Exportación rápida** — `FastModpackExporter` genera el `.mcaddon` final sin pérdidas.
- **Integración con Modrinth y MCPEDL** — Busca y añade addons directamente desde la app (API de Modrinth + buscador de MCPEDL).
- **Apertura directa** — Registrada como manejadora de archivos `.mcaddon` y `.mcpack`: ábrelos desde el explorador.
- **Detección de Minecraft** — Comprueba si tienes Minecraft Bedrock instalado en tu dispositivo.
- **Modpacks guardados** — Guarda, renombra y reutiliza tus combinaciones (Room).
- **Temas personalizables** — Oscuro/claro y personalización de color (DataStore).

---

## 🧪 Cómo usarla

1. **Importar** → selecciona los addons (`.mcaddon` o `.zip`) que quieres combinar.
2. **Studio** → gestiona tus modpacks guardados y explora fuentes Bedrock (MCPEDL, CurseForge, ModBay).
3. **Conflictos** → revisa el reporte de conflictos resuelto automáticamente.
4. **Exportar** → configura nombre, autor, versión, portada y exporta el `.mcaddon`.
5. **Abrir con Minecraft** → importa el modpack resultante.

---

## 📲 Instalación

### Opción A — APK de CI
Descarga el APK de depuración desde los artefactos del workflow [Android CI](https://github.com/Lord4rk2028/PackForge/actions).

### Opción B — Compilar desde el código
```bash
# Requisitos: JDK 17 y Android SDK (compile/target SDK 35)
git clone https://github.com/Lord4rk2028/PackForge.git
cd PackForge
./gradlew assembleDebug
# APK generado en: app/build/outputs/apk/debug/app-debug.apk
```

Ejecutar los tests unitarios:

```bash
./gradlew test
```

---

## 📋 Requisitos

- **Android 8.0 (API 26) o superior**.
- **Minecraft Bedrock Edition** instalado para importar y jugar el resultado.
- Addons compatibles con el formato estándar de Bedrock (con `manifest.json`).

---

## 🛠️ Stack tecnológico

| Capa | Tecnología |
|---|---|
| Lenguaje | Kotlin 2.3.21 |
| UI | Jetpack Compose (BOM 2025) + Material 3 & Material 3 Expressive 1.4.0 |
| Arquitectura | MVVM (ViewModels + StateFlow) con capas `domain` / `data` / `ui` |
| Persistencia | Room 2.8.4 (KSP) + DataStore Preferences |
| Red | Retrofit 2.11 + Gson + OkHttp 4.12 |
| Imágenes | Coil 2.6.0 |
| Navegación | Navigation Compose 2.8.5 |
| Build | Gradle (AGP 9.3.1) + KSP, `compileSdk`/`targetSdk` 35, `minSdk` 26 |
| CI | GitHub Actions (tests + build + artefacto APK) |

---

## 🗂️ Estructura del proyecto

```
app/src/main/java/com/packforge/app/
├── MainActivity.kt / PackForgeApplication.kt
├── domain/
│   ├── engine/          # 🧠 Motor de fusión
│   │   ├── AddonExtractor.kt            # Desempaqueta .mcaddon/.zip (con seguridad)
│   │   ├── AddonParser.kt / AddonMerger.kt
│   │   ├── ConflictEngine.kt            # Detecta y resuelve conflictos
│   │   ├── IdentifierRemapper.kt        # Remapea IDs duplicados
│   │   ├── JsonDeepMerger.kt            # Fusión JSON profunda
│   │   ├── ManifestGenerator.kt         # Manifests válidos
│   │   ├── BedrockCompatibilityAnalyzer.kt
│   │   ├── EntityDependencyResolver.kt
│   │   ├── ScriptCollisionAnalyzer.kt
│   │   └── FastModpackExporter.kt       # Exporta el .mcaddon final
│   └── model/           # Addon, Conflict, MergeResult, ExportState…
├── data/
│   ├── PackForgeDatabase.kt / SavedModpackDao.kt
│   ├── ThemePreferences.kt
│   └── modrinth/        # Cliente de la API de Modrinth
├── ui/
│   ├── screens/         # Import, Studio, Conflicts, Export, CoverPicker,
│   │                    # ModrinthSearch, McpedlSearch, ThemeSettings, WebBrowser
│   ├── navigation/      # Rutas
│   ├── components/      # Componentes reutilizables
│   ├── theme/           # Tema Material 3 (claro/oscuro, colores)
│   └── viewmodel/       # ViewModels
└── util/                # FileUtils, PackForgeLog, PackForgeConfig
```

---

## 🗺️ Roadmap (ideas)

- [ ] Soporte para más formatos de addon y casos extremos de manifests.
- [ ] Editor manual de conflictos en la UI (elegir qué addon "gana").
- [ ] Modos de fusión configurables (prioridad, orden, reemplazo).
- [ ] Notificaciones de progreso en segundo plano para modpacks grandes.
- [ ] Publicación en Google Play.
- [ ] Más tests de regresión del motor de fusión.

---

## 🤝 Contribuir

¡Las contribuciones son bienvenidas! Para aportar:

1. Haz un **fork** del repositorio.
2. Crea tu rama: `git checkout -b feat/mi-mejora`.
3. Haz tus cambios y **añade tests** del motor de fusión si tocan `domain/engine`.
4. Envía un **pull request** a la rama `main`.

Por favor, ejecuta `./gradlew test` antes de abrir el PR.

---

## 📄 Licencia

Este proyecto está licenciado bajo la **MIT License** — consulta el archivo [`LICENSE`](LICENSE) para más detalles.

En resumen: puedes usar, modificar y distribuir el código libremente, incluso con fines comerciales, manteniendo el aviso de copyright original.

---

## ⚠️ Notas

- PackForge **no modifica el contenido creativo** de los addons originales: solo los combina.
- Si dos addons son fundamentalmente incompatibles (por ejemplo, requieren versiones de Minecraft distintas), la fusión puede generar advertencias — **revisa siempre el reporte de conflictos antes de jugar**.
- Proyecto personal en **desarrollo activo**; puede tener errores o casos de addons aún no soportados.

---

*Proyecto no afiliado a Mojang ni a Microsoft. Minecraft es una marca de Mojang Synergies AB / Microsoft.*
