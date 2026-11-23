# AGENTS.md — Guía para agentes de IA en PackForge

> Punto de entrada para cualquier agente de IA (Claude Code, Cursor, Windsurf, Gemini, Copilot…) que trabaje sobre este repositorio.
> **Léelo completo antes de tocar código.** El detalle vive en `docs/ai/` y se enlaza según la tarea.
> Si tu herramienta busca otro nombre (`CLAUDE.md`, `GEMINI.md`, `.cursorrules`, `.windsurfrules`), copia o enlaza este archivo con ese nombre.

---

## 1. Qué es PackForge

App nativa Android (Kotlin + Jetpack Compose + Material 3 Expressive) que **fusiona varios addons de Minecraft Bedrock Edition (`.mcaddon` / `.mcpack` / `.zip`) en un único `.mcaddon` funcional**: un Resource Pack (RP) y un Behavior Pack (BP) fusionados, con manifiestos nuevos y sin pérdida de contenido.

El producto vive o muere por una promesa: **el modpack exportado se comporta en el juego como la suma de los addons originales** (sin entidades invisibles, texturas rotas, bloques sin nombre ni crashes al cargar). Todo el motor existe para sostener esa promesa y para **avisar con honestidad** cuando no se puede.

- Paquete: `com.packforge.app` · minSdk 26 · targetSdk/compileSdk 35 · Kotlin 2.3.x · Room · DataStore · Coil · Retrofit/OkHttp/Gson.
- Arquitectura: Clean Architecture + MVVM (`StateFlow`, corrutinas).
- Idioma del código: **comentarios, logs y textos de UI en español**. Mantén el idioma y el estilo del archivo que editas.

## 2. Documentos pilar (lee según tu tarea)

| Si vas a… | Lee antes |
|---|---|
| Tocar cualquier cosa del motor de fusión (`domain/engine`) | [`docs/ai/PACKFORGE_ENGINE.md`](docs/ai/PACKFORGE_ENGINE.md) (pipeline real, componentes, invariantes, tests) |
| Entender o decidir cómo se fusiona un tipo de archivo Bedrock (JSON, `.geo.json`, manifest, lang, sonidos, UI, scripts) | [`docs/ai/BEDROCK_ADDONS_REFERENCE.md`](docs/ai/BEDROCK_ADDONS_REFERENCE.md) (cómo funcionan RP/BP en Bedrock + qué hace PackForge con cada pieza) |
| Depurar un bug de “mob invisible”, geometrías, conflictos falsos o rendimiento | [`DIAGNOSTICO_FUSION_BETTER_MC.md`](DIAGNOSTICO_FUSION_BETTER_MC.md) (historial de causas raíz medidas con packs reales) |
| Cambiar UI / tema | [`MATERIAL3_EXPRESSIVE_REDESIGN.md`](MATERIAL3_EXPRESSIVE_REDESIGN.md) y `ui/theme/` |

Regla: **si tu cambio contradice uno de estos documentos, actualiza el documento en el mismo commit** (ver §9).

## 3. Stack y comandos

```bash
./gradlew test                 # tests unitarios (suite grande: ~70 clases en domain/engine)
./gradlew test --tests "com.packforge.app.domain.engine.JsonDeepMergerTest"
./gradlew assembleDebug        # APK debug → app/build/outputs/apk/debug/app-debug.apk
```

- **Windows**: usa `run-tests.ps1` (`-Task`, `-Filter`). Sanea el `PATH` porque rutas con espacios (p. ej. “Microsoft VS Code”) rompen el ejecutor de tests de Gradle.
- **Lint**: `./gradlew lint` se salta en el build de release porque el análisis K2 crashea con `PackForgeHealer.kt` (bug de la herramienta, no del código).
- **CI**: `.github/workflows/android-ci.yml` (JDK 17) ejecuta `test` y `assembleDebug` en `master`/`main`.

## 4. Mapa rápido del repositorio

```
app/src/main/java/com/packforge/app/
├─ domain/engine/   ← EL MOTOR (≈25k líneas con el resto). Pipeline, mergers, resolvers, validadores
├─ domain/model/    ← Addon, Conflict(+Severity/Resolution), MergeConflict, SavedModpack…
├─ service/         ← MergeForegroundService + MergeSession (fusión en 1.er plano, sobrevive a segundo plano)
├─ ui/              ← screens (Import, Studio, Conflicts, ExportSetup, Theme, Web…), components, theme, viewmodel
├─ data/            ← Room (SavedModpack, DAO, migraciones), DataStore, descarga/actualización de addons
├─ util/            ← FileUtils, AddonStorageManager, PackForgeLog/Config, MinecraftTextSanitizer
└─ assets/vanilla_all_keys_complete.txt  ← catálogo vanilla (ids/rutas que el juego resuelve solo)
app/src/test/…      ← tests del motor (regresión con casos reales; ver PACKFORGE_ENGINE.md §12)
```

## 5. Reglas de oro (invariantes del motor)

Cada regla nace de un bug real documentado en el código o en `DIAGNOSTICO_FUSION_BETTER_MC.md`. No las rompas “por simplicidad”.

1. **Nunca renombres identificadores `minecraft:*`.** Son overrides de vanilla. Si dos addons tocan la misma entidad vanilla, se fusiona **por componente** (`BedrockComponentMerger`, `mergePlayerEntity`), no por renombrado.
2. **Los ids persistentes (entidad, bloque, ítem) solo se renombran en el addon perdedor**, con el namespace `pf_<hash>` (`IdentifierRemapper`), reescribiendo **todas** las referencias de *ese* addon (JSON + `.lang` + claves de catálogo indexadas por id: `blocks.json`, `texture_data`, `entity_sounds.entities`) y nunca tocando al ganador. Los archivos de **declaración única** (`entity/`, `attachables/`) se agrupan por `description.identifier`, no por nombre de archivo (`DeclarationFileRouter`): dos ids distintos que comparten `entity/mob.json` deben salir en dos archivos, o la entidad del perdedor se queda sin `client_entity` (mob invisible). Los **recursos** (partículas, binarios, geometrías/animaciones/render controllers sombreados) usan alias `_pf<hash>` (`ParticleDeduplicator`, `ResourcePathRegistry`, `EntityDependencyResolver`). **El token `_pf` se deriva de contenido o de los UUID de los manifiestos (`AddonOwnerRegistry.stableSeed`), nunca de la ruta extraída** (`extracted_<epochMs>_…`): lo renombrado vive en los mundos y debe resolverse al reinstalar.
3. **Una “referencia” no es un string cualquiera.** Antes de validar o reescribir, clasifica con `BedrockRefs` (id/ruta real vs. alias de la entidad como `Geometry.default`, vs. expresión Molang, vs. objeto condicional). Hacer `optString()`/stringificar valores produjo miles de falsos positivos.
4. **Lo vanilla no se valida ni se copia.** Lo decide `VanillaAssets` (catálogo + `assets/vanilla_all_keys_complete.txt`) con coincidencia **por prefijo de segmento**, nunca por substring. **No mantengas listas de entidades vanilla a mano** (se quedan cortas con cada versión del juego); “¿es un mob real?” se responde mirando si el propio pack lo usa (`EntityOrphanClassifier`). El catálogo se **autocarga** desde el classpath en la primera consulta (`ensureCatalog()`), sin depender de `PackForgeApplication`: cuando solo se cargaba en Android, generar un pack desde un test JVM lo dejaba vacío, `controller.render.armadillo.v2` no se reconocía como vanilla y `EntityDependencyResolver` lo borraba como referencia colgante — dejando **los mobs vanilla invisibles** en el juego. Por eso además, **si el catálogo no está disponible no se neutraliza ninguna referencia**: ante la duda se conserva (el juego la resuelve o la ignora), que es mucho menos dañino que un mob sin render.
5. **El JSON de Bedrock no es JSON estricto.** Existen comentarios `//` y `/* */`, claves duplicadas (addons cerrados), BOM y homógrafos cirílicos. Usa `LenientJson`; no asumas que `org.json` parsea todo ni declares “JSON inválido” sin pasar por él.
6. **Nunca copies un archivo sobre sí mismo.** `File.copyTo(overwrite = true)` borra el destino antes de copiar; con origen = destino el archivo se autodestruye en silencio. `FileUtils.fastCopy` ya protege con `canonicalFile`; mantén esa guarda en cualquier copia nueva.
7. **Healer y Validator no borran ni inventan contenido.** `PackForgeHealer.heal()` es solo diagnóstico. `repair()` copia texturas/modelos que existen en algún addon original y solo genera geometría *placeholder* si **no existe en ningún addon, no es vanilla y el pack aún no la define**. Jamás un placeholder encima de un id vanilla o ya existente. **Política de stubs (TICKET-CORE-012):** un stub solo se justifica si convierte «no se ve nada» en «se ve algo» **sin tapar un diagnóstico**. Por eso **no** se generan stubs de textura (el marcador de textura ausente de Bedrock es información; un PNG neutro dejaría al mob invisible) ni de animación (una referencia colgante se **neutraliza** quitándola). El unique candidato a stub es un render controller para entidades **custom** con el controlador realmente ausente —nunca para `minecraft:*`, donde la definición la pone el juego—. Todo stub se reporta como generado.
8. **Un conflicto es una diferencia real, no una clave repetida.** Si el valor entrante es idéntico (`canonicalOf`), no se registra conflicto. Distingue severidades (cambiar un `identifier` ≠ cambiar un `format_version`).
9. **Toda fusión dedicada se declara y se ejecuta de forma verificable.** Añade su nombre a `MergePipelineGuard.REQUIRED_MERGES` y ejecútala con `ejecutarMerge(...)`; el guard existe porque `mergeSoundDefinitions` y `mergeParticles` estuvieron documentadas pero sin llamar, y se perdían sonidos y partículas en silencio.
10. **Móvil primero: memoria y E/S.** Usa `DirIndexCache` (no `walkTopDown()` en bucles), respeta `LIMITE_FUSIONES_PARALELAS` (2–4), emite progreso con throttle (≈250 ms), nunca `withContext(Dispatchers.Main)` por archivo, no cargues packs enteros en memoria. Una fusión paralela sin tope llegó a 482 MB de pico y a swap → app “colgada”. **El parseo es CPU puro y en ART va 10–50× más lento que en el JVM de escritorio** (medido: `LenientJson` 3.0 ms frente a `org.json` 0.9 ms para un `.geo.json` de 3.4 KB, y 1.57 ms solo para crear el `MessageDigest`). `GeometryAnimRCIndex` tardaba **1020 s en un móvil** con 16 entidades porque parseaba *todos* los `.json` del pack para extraer unas pocas claves; filtrar por directorio y usar la vía rápida de `org.json` con `LenientJson` como respaldo lo dejó en **1.8 s en JVM (6×)**. Regla: no parsees un fichero si no puede contener lo que buscas, y reutiliza `MessageDigest` por hilo.
11. **Scripts: no se reescribe lógica JS.** Cada BP conserva su árbol de scripts íntegro en `scripts/modules/<addon_id>/…` (con la ruta relativa original, para que los `import "./jobs.js"` internos sigan resolviéndose) y se carga desde un único `scripts/main.js` generado por `ScriptRouterEngine`. La raíz `scripts/` está **reservada** al bootstrapper: no se empaquetan copias planas de los `.js` de cada addon (dos addons con `scripts/index.js` se pisaban entre sí y podían sobrescribir el bootstrapper). El bootstrapper usa **`import` ESTÁTICO** (`import "./modules/<id>/<entry>";`), el patrón documentado por Mojang para varios archivos en un pack: evalúa los módulos en orden de declaración y el addon base queda registrado antes que sus dependientes. **No usar `import()` dinámico**: no está documentado para Bedrock y con él el pack de prueba real arrancaba los datos pero **ningún script se ejecutaba**. La única reescritura permitida es el *literal* de `registerCustomComponent` del addon perdedor (`CustomComponentRewriter`). Si un script no se puede preservar, **se detiene la exportación**, no se exporta un addon “plano”.
12. **Manifiestos nuevos y coherentes** (`ManifestGenerator`): UUIDs v4 nuevos (header y módulos, todos distintos), `min_engine_version` = **máximo** de los addons, BP depende del UUID del header del RP fusionado, dependencias `@minecraft/*` unificadas, módulo `script` con `entry` si hay scripts, `header.subpacks` conservados con las carpetas desambiguadas por `SubpackRegistry` (sin `subpacks` el juego ignora las variantes), `capabilities` unidas y `min_engine_version` máximo también en el RP (`applyOriginalRpSettings`), dependencias por UUID **solo** hacia los packs del propio `.mcaddon` (una dependencia externa impide que Bedrock cargue nada: se elimina y se reporta), escritura UTF-8 **sin BOM**.
13. **ZIP seguro y limpio.** Protege contra *zip slip* (`isSecurePath`), no empaquetes basura (`.git`, `.vscode`, `.bak` internos), sin *data descriptor* (compatibilidad con Bedrock) y audita el `.mcaddon` final (`auditFinalMcAddon`).
14. **Android**: acceso a archivos por SAF/`DocumentFile`; no asumas rutas absolutas a `Android/data`. Gson ignora nulabilidad y defaults de Kotlin → usa `Addon.withSafeLists()` al deserializar. Toda migración de Room lleva test (`DatabaseMigrationTest`).
15. **No hay “arreglos silenciosos”.** Si el motor omite, renombra, descarta o repara algo, debe quedar en `ConflictRegistry`/`FusionReportBuilder` (y en el reporte `fusion_report.txt`). Un `catch` que se traga la excepción sin reportar es un bug.

16. **No inventes renombrados de componentes ni subas `format_version` a ciegas.** Una transformación de esquema solo es válida si el nombre destino **existe en contenido oficial**: verifícalo contra `bedrock-samples` (`behavior_pack/entities`, `blocks`, `items`). La primera versión de `SchemaNormalizer` renombraba `minecraft:collision_box` → `minecraft:collider` y `minecraft:movement` → `minecraft:movement.general`; los destinos aparecen en **0 de 130** entidades oficiales mientras los originales aparecen en **126** y **93**, así que habría dejado a cada entidad sin caja de colisión y sin movimiento. Lo mismo con `minecraft:damage` (7 de 82 ítems oficiales, es la DURABILIDAD) → `minecraft:weapon` (0 apariciones). Además: `format_version` **selecciona el esquema**, subirla no convierte el contenido; y las variables Molang se **definen** en el entity JSON y se **leen** en `animations/` y `render_controllers/`, que son otros archivos, así que prefijarlas en uno solo deja la lectura apuntando a algo que no existe. Ante la duda: **detectar y reportar** (`SchemaNormalizer.inspect`), nunca mutar.

17. **El reporte no afirma lo que no ha verificado.** Un diagnóstico que se equivoca con seguridad es peor que uno que duda: el usuario persigue fantasmas y deja de creer el resto del informe. Caso real (TICKET-CORE-013): la auditoría de integridad marcaba como «cifrado por sustitución — **el juego ignorará este archivo**» a **77** ficheros de `ui/` de Desse-UI que eran perfectamente válidos, porque su clave raíz es el **nombre del elemento** (`{"blast_furnace_screen":{"$is_bl...":true}}`, usada en 31 de 190 ficheros de ese addon) o una **ruta de modificación** (`{"beacon_panel/root_panel":{"modifications":[…]}}`, 49 de 190) y no aparecía en el catálogo de claves. En `ui/` la raíz ES el mapa de elementos: `MergeReportGenerator` acepta ahora esas claves **solo bajo una carpeta `ui/`** y **solo si todas** tienen forma de elemento (`PATRON_ELEMENTO_UI`), para no absolver un archivo cifrado de verdad. Además, ese aviso contradecía al Decifrador del **mismo** informe («no se detectó contenido closed-source que recuperar»). Regla: cuando dos componentes del reporte discrepen del mismo hecho, uno de los dos está mal y hay que resolverlo antes de publicar.

18. **Mide antes de optimizar, y publica el desglose que permite medir.** El tramo del reporte decía «(empaquetado/cierre)» cuando en realidad era el **diagnóstico**; con esa etiqueta se optimizaba el ZIP mientras el coste estaba en las auditorías. Y la Fase 3 lanza 22 fusiones en paralelo: su total no identifica a la culpable. Por eso cada fusión registra su tiempo y el reporte lo publica entero en `0c. FUSIONES CRÍTICAS`. Un `println` de diagnóstico **no** llega a `fusion_report.txt` en Android (va a logcat): si quieres un dato en el informe, pásalo por `MergeReportGenerator.Inputs`.

## 6. Flujo de trabajo recomendado

1. **Reproduce con un test** antes de arreglar. Mira los tests existentes con nombre parecido (p. ej. `Fase5*Test`, `Diagnostico*Test`, `MergePipelineEndToEndTest`, `RealPackP0AuditTest`) y copia su estilo de fixtures.
2. Localiza el componente dueño del comportamiento en `PACKFORGE_ENGINE.md §4`; **no dupliques lógica** en otro objeto (ya hubo validadores con criterios divergentes).
3. Haz el cambio mínimo, **general y agnóstico del addon** (nada de reglas “para Better MC” o “para CoreCraft”; los casos reales son fixtures de test, no condiciones en el código).
4. Corre `./gradlew test` (o el filtro del paquete afectado). Si tocaste el pipeline, ejecuta también `MergePipelineEndToEndTest` y `MergePipelineEdgeCasesTest`.
5. Actualiza documentación si cambió el pipeline, una regla o un contrato (§9).

## 7. Anti-patrones (no lo hagas)

- Reescribir `blocks.json`, `terrain_texture.json`, `item_texture.json`, `sound_definitions.json`, `sounds.json`, `.lang`, `ui/*` con el deep-merge genérico: tienen fusión dedicada (`CRITICAL_DIRS*` en el orquestador).
- Re-empaquetar o reescalar texturas de entidades (rompe los UV de los `.geo.json`).
- Aliasar `.json` de definición con `ResourcePathRegistry`: ese registro aliasa solo *binarios* (png, ogg…) por hash MD5; su KDoc excluye explícitamente todo `.json`, `manifest.json`, `pack_icon.png`, `scripts/` y `texts/`. Los ids duplicados se resuelven por identifier (`IdentifierRemapper`, `EntityDependencyResolver`), no renombrando archivos a ciegas.
- Resolver por substring (`contains`) lo que debe resolverse por segmento/prefijo exacto.
- Poner umbrales o topes de reporte que enmascaren cifras reales (p. ej. “41 materiales” era un tope, no un conteo).
- Añadir dependencias pesadas o trabajo en el hilo principal para “arreglar” rendimiento.
- Inventar contenido que el autor del addon no escribió (mobs, geometrías, componentes).
- Renombrar componentes o subir `format_version` sin comprobar que el nombre destino existe en `bedrock-samples`. Y no reescribir variables Molang: se definen en el entity JSON y se leen en `animations/` y `render_controllers/`, que son otros archivos, así que prefijarlas en uno solo deja la lectura rota (regla 16).

## 8. Definición de “hecho”

- Tests nuevos/actualizados pasando y sin romper la suite.
- Sin nuevos `catch` silenciosos; todo hallazgo relevante llega al reporte.
- Cambios agnósticos del addon y consistentes con §5.
- Documentación actualizada si aplica.
- Para cambios de UI: respeta la paleta oscura/púrpura y los componentes de `ui/components`; textos en español.

## 9. Mantener esta documentación

Esta documentación es la **memoria compartida** entre humanos y agentes. Actualízala cuando:

- cambies el orden o las fases del pipeline (`PACKFORGE_ENGINE.md §3`),
- añadas/quites un merger, resolver, detector o tipo de conflicto (`§4`, `§6`),
- descubras una regla de Bedrock que el doc no recoja (`BEDROCK_ADDONS_REFERENCE.md`),
- cierres una deuda técnica listada en `PACKFORGE_ENGINE.md §13`.

Los documentos describen el estado del código al momento de escribirlos (versión `1.9.1`, `versionCode 191`). Ante discrepancia, **manda el código**: corrige el documento.
