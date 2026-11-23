# PackForge — Motor de fusión: arquitectura, pipeline e invariantes

> Documento pilar para agentes de IA y humanos. Describe **lo que el código hace hoy** (versión `1.9.1`, `versionCode 191`).
> Para el conocimiento de Bedrock en sí (qué es un RP/BP, formatos, JSON, geometrías) ver [`BEDROCK_ADDONS_REFERENCE.md`](BEDROCK_ADDONS_REFERENCE.md).
> Entrada general y reglas de oro: [`../../AGENTS.md`](../../AGENTS.md). Historial de causas raíz medidas: [`../../DIAGNOSTICO_FUSION_BETTER_MC.md`](../../DIAGNOSTICO_FUSION_BETTER_MC.md).
> Rutas relativas a `app/src/main/java/com/packforge/app/` salvo que se indique otra cosa. Ante discrepancia con el código, **manda el código**.

---

## 1. Contrato del producto

**Entrada:** N addons (`.mcaddon`, `.mcpack`, `.zip`), cada uno con un BP, un RP o ambos (a veces varios BP/RP en un mismo archivo).
**Salida:** un único `.mcaddon` con **un RP fusionado + un BP fusionado**, manifiestos nuevos y vinculados, más un `fusion_report.txt` legible y un veredicto con semáforo (`CompatibilitySummary`).
**Garantías que el motor intenta cumplir:**

1. Ningún contenido de un addon se pierde en silencio (si se descarta, se reporta).
2. Los ids que colisionan coexisten (renombrado de ids del perdedor) en lugar de “uno gana”.
3. Las referencias entre archivos siguen cerrándose tras fusionar (entidad ↔ geometría ↔ textura ↔ animación ↔ render controller…).
4. Lo que es vanilla no se trata como error.
5. Si no se puede garantizar algo grave, **se bloquea la exportación con una causa accionable** (script sin entry válido, ciclo de imports, compatibilidad crítica sin resolver).

## 2. Mapa de paquetes

| Paquete | Responsabilidad |
|---|---|
| `domain/engine/` | Todo el motor (≈50 objetos/clases). Ver §4. |
| `domain/model/` | `Addon`, `AddonType`, `Conflict` (+`ConflictType`, `ConflictSeverity`, `ConflictResolution`), `MergeConflict`, `SavedModpack(+Summary)`, `ModpackMetadata`, `ExportState`, `OperationProgress`, `MergeResult`… |
| `service/` | `MergeForegroundService` + `MergeSession` (estado global observable): la fusión corre en primer plano y sobrevive si la app pasa a segundo plano. |
| `ui/viewmodel/` | `PackForgeViewModel` (importar, activar/ordenar addons, conflictos, exportar, historial, actualizaciones), `ThemeViewModel`. |
| `ui/screens/` | `ImportScreen`, `StudioScreen` (mesa 3×3 + compatibilidad), `ConflictsScreen`, `ExportSetupScreen`, `ThemeSettingsScreen`, `McpedlSearchScreen`/`WebBrowserScreen` (importar desde web), `MergeOverlay`, `ModpackAddonsDialog`, `UpdateCenterDialog`. |
| `data/` | Room (`PackForgeDatabase` v3, entidad `SavedModpack`, `SavedModpackDao`, migración 2→3 con test), DataStore (`ThemePreferences`), `download/` y `update/` (descarga y búsqueda de actualizaciones de addons). |
| `util/` | `FileUtils` (`fastCopy` con guarda anti-autocopia), `AddonStorageManager` (snapshot de espacio, limpieza de duplicados/huérfanos), `PackForgeLog`, `PackForgeConfig` (`verboseFileLogs`, apagado por defecto), `MinecraftTextSanitizer`. |
| `assets/` | `vanilla_all_keys_complete.txt`: catálogo de ids/rutas vanilla que el juego resuelve solo (lo consume `VanillaAssets`). |

### Flujo de alto nivel (UI → motor)

```
ImportScreen ──▶ PackForgeViewModel.importAddons ──▶ AddonParser.parseFromUri ──▶ Addon (identificadores indexados)
        │                                                    (AddonIdentifierIndex)
        ▼
StudioScreen / ConflictsScreen
  ConflictEngine.analyze(addons)  ──▶ List<Conflict>  ──▶ ExportCompatibilityGate.evaluate ──▶ ¿puede exportar?
        │   (usuario: orden/prioridad, activar/desactivar, elegir ganador por conflicto)
        ▼
ExportSetupScreen ──▶ PackForgeViewModel.exportModpack ──▶ MergeForegroundService
        ──▶ PackForgeOrchestrator.mergeAddons(addonPaths, …, addonIds, fileWinners)
        ──▶ .mcaddon + fusion_report.txt ──▶ MergeSession (progreso/estado) ──▶ guardar en historial (Room) / abrir en Minecraft
```

La *prioridad* del usuario y el *orden* de addons determinan quién gana en las colisiones simples: **la fusión copia en orden y el último sobrescribe**. `AddonOrderResolver` traduce “gana X” a un reordenado estable; `ResolutionApplier` aplica, **al final**, la elección archivo por archivo (`fileWinners: ruta relativa → id del addon ganador`).

## 3. Pipeline real de `PackForgeOrchestrator.mergeAddons`

Los porcentajes son los del callback de progreso (aproximados). El orden es el del código.

| # | Fase | Qué ocurre | Componentes |
|---|---|---|---|
| 0 | Reset | Se limpian registros globales | `ConflictRegistry`, `FolderAliasRegistry`, `DirIndexCache`, `FusionReportBuilder`, `ResourcePathRegistry.clearGlobalMutations`, `AddonOwnerRegistry`, `MergePipelineGuard`, `SubpackRegistry` |
| 1 | Extracción (4 %) | Cada addon se extrae **en paralelo** a `outputDir/temp_merge/extracted_<ts>_<nombre>`. Fallos → `FusionReportBuilder` `FATAL`. Si no se extrae ninguno → `MergeResult(success=false)`. | `AddonExtractor.extractAddon` (protegido contra *zip slip* con `isSecurePath`) |
| 2 | Clasificación (8 %) | Cada carpeta → `BEHAVIOR_PACK`, `RESOURCE_PACK`, `BOTH`, `MULTI` o `UNKNOWN`. Los `BOTH` se separan **sin copiar** (antes se duplicaban en `separated_*`) y se registran como el mismo addon. `resolvePackRoot` elige la raíz por `modules[].type` (`data`/`resources`), no “el primer manifest”. | `AddonExtractor.classify/analyzeExtractedAddon/resolvePackRoot`, `AddonOwnerRegistry` |
| 3 | Renamespacer | Detecta ids duplicados entre addons y renombra los del **perdedor** a `pf_<hash>:nombre`, reescribiendo referencias (JSON + `.lang`) **y las claves de los catálogos indexados por id** (`blocks.json`, `texture_data`, `entity_sounds.entities`). No toca `minecraft:*`. | `IdentifierRemapper.run` (+ `JsonValueRewriter`) |
| 4 | Desambiguación previa a la copia | Componentes personalizados duplicados (`registerCustomComponent`) → sufijo `_pf<hash4>` en el perdedor. Partículas duplicadas → renombradas **en el origen** del perdedor. | `CustomComponentRewriter.rewrite`, `ParticleDeduplicator.deduplicate` |
| 5 | Fase 1A/1B | `SubpackRegistry.plan` desambigua las carpetas de `header.subpacks` (BP y RP) antes de copiar: dos addons con `subpack_1` no pueden compartir carpeta. Después, plan de mutaciones de recursos binarios (alias `nombre_pf<hex6>.ext` si misma ruta y distinto MD5) y reescritura de referencias **por fuente** (no un mapa global plano). | `SubpackRegistry`, `ResourcePathRegistry`, `FolderAliasRegistry` (structures/fogs/ui con prefijo `pf_<hash6>`) |
| 6 | Fase 1C | Copia eager (bytes, buffer 16 KB). Los JSON no críticos se deep-mergean **en memoria** y se vuelcan al final. `scripts/` y `CRITICAL_DIRS*` se excluyen de la copia genérica (tienen fusión dedicada). Se reescriben `/structure load <stem>` en funciones. | `fastMergeAllSources`, `mergePackType`, `JsonDeepMerger.mergeIntoBuffer/flushBufferToDisk` |
| 7 | Fase 2 | Detección de huecos: parsea las entidades cliente del RP fusionado y rellena lo que falte buscando en los addons originales (clasifica por **contenido**, “duck typing”, no por nombre de archivo, para soportar nombres ofuscados). | `LazyDependencyResolver.resolveAndRepair` |
| 8 | Fase 3 (≈76 %) | **Fusiones críticas en paralelo** (semáforo `LIMITE_FUSIONES_PARALELAS`), incluida `mergeSoundDefinitions` (su mapa de claves renombradas se espera al final). Ver §5. Luego se consulta `MergePipelineGuard` y, síncrono, `mergeMaterialInstances` (77 %). **Cada fusión registra su tiempo** y el reporte lo publica ordenado (§12.1). | `BedrockCriticalFilesMerger`, `BedrockUiMerger`, `MergePipelineGuard` |
| 9 | Decisiones del usuario | Se copia el archivo del ganador elegido sobre el resultado, archivo por archivo (no aplica a rutas derivadas de id con `:` ni a archivos con fusión dedicada). | `ResolutionApplier.apply` |
| 10 | Fase 3B/3D (≈78 %) | Cobertura de texturas; resolución de dependencias de entidades (geometría, texturas, animaciones, render controllers) con alias `_pf<hash6>` cuando el id está sombreado por contenido distinto. | `BedrockCriticalFilesMerger.validateTextureCoverageComplete`, `EntityDependencyResolver.resolve` |
| 11 | Manifiestos (80 %) | Preflight de compatibilidad (puede **bloquear**), fusión de scripts, generación de manifiestos RP/BP vinculados. | `BedrockCompatibilityAnalyzer.analyze`, `mergeScripts`, `ManifestGenerator` |
| 12 | Scripts (82 %) | Hallazgos de colisión en el bundle fusionado. | `ScriptCollisionAnalyzer.analyze` |
| 13 | Decifrador (84 %) | Recupera addons “closed-source” con contenido oculto y claves mezcladas. | `BedrockDecifrator.decrypt` (+ `LenientJson`) |
| 14 | Healer repair (87 %) | Copia texturas/modelos faltantes desde originales; auto-repara referencias por fuzzy-match ≥ 0,95; placeholder de geometría solo como último recurso. | `PackForgeHealer.repair`, `autoRepairReferences` |
| 15 | Validación (89 %) | Cierre de referencias BP↔RP (ítems, bloques, entidades, render controllers, sonidos, lang). | `PackForgeValidator.validate` |
| 16 | Healer heal (91 %) | Diagnóstico no bloqueante: fuzzy-match ≥ 0,85 → aviso; < 0,85 → irrecuperable (HIGH, no aborta). | `PackForgeHealer.heal` |
| 17 | Paso 5c | Referencias que la propia fusión manipula (partículas renombradas, animaciones, materiales, custom components ↔ scripts). | `MergeReferenceValidator.validate/publish` |
| 18 | Empaquetado (93 %) | Verificación previa, ZIP sin *data descriptor*, sin basura interna, auditoría posterior. | `verifyBeforeZip`, `createMcAddon`, `auditFinalMcAddon` |
| 19 | Reporte (96 %) | Sintaxis JSON de todo el pack, auditoría de integridad, validación del ZIP final, `fusion_report.txt`, semáforo. **Una sola pasada de diagnóstico por pack** (`auditPack`: sintaxis + integridad comparten lectura y parseo) y **un solo `GeometryAnimRCIndex`** para el validador de referencias y el grafo de dependencias. | `MergeReportGenerator.auditPack`, `MergeReferenceValidator.validate(unifiedIndex=…)`, `DependencyDAG.build(…, indiceCompartido)`, `EntityDependencyResolver.validateMerge`, `CompatibilitySummary.build` |
| 20 | Limpieza | Se borran temporales (`temp_merge`). | `cleanupTempDirs` |

Notas de diseño que explican el orden:

- **Remapper → rewriters → plan de recursos → copia**: todo lo que muta fuentes ocurre *antes* de copiar, para que cada addon se reescriba de forma aislada y el ganador nunca se contamine.
- **Resolvers de dependencias después del remapper**: índice y referencias ya usan los ids finales (`pf_…` incluidos).
- **Healer/Validator al final**: son red de seguridad, no la solución primaria.

## 4. Catálogo de componentes (`domain/engine/`)

### Ingesta y orden
| Componente | Responsabilidad / invariantes |
|---|---|
| `AddonExtractor` | Extrae ZIP con protección *zip slip*; clasifica (`AddonClassification`: BP/RP/BOTH/MULTI/UNKNOWN); `resolvePackRoot` por `modules[].type`; lee manifiestos y `.lang` para nombre/autor. |
| `AddonParser` | Usa `Context`/`Uri` (no testeable en JVM puro): lee metadatos, icono (`pack_icon` o `icon`), y rellena los identificadores del `Addon`. |
| `AddonIdentifierIndex` | Recolección **testeable** de ids que cada addon declara (entidades, ítems, recetas, loot tables, partículas, materiales, sonidos, animation controllers). Si queda incompleta, `ConflictEngine` nunca reporta ese tipo de conflicto. |
| `AddonOrderResolver` | Traduce los ganadores elegidos por el usuario en un orden de fusión estable (el elegido pasa al final). |
| `AddonOwnerRegistry` | Registra qué carpetas BP/RP pertenecen al **mismo** addon (para que un addon no “colisione consigo mismo”). |
| `AddonUriCache` | Mapa concurrente `addonId → Uri` (se lee desde la UI al pintar la lista y se escribe desde corrutinas de importación; por eso `ConcurrentHashMap`). |

### Identidad y colisiones
| Componente | Responsabilidad / invariantes |
|---|---|
| `IdentifierRemapper` | Renombra ids que colisionan **en el perdedor** a `pf_<hash>:nombre`. `DECLARING_KEYS`: entidad, ítem, bloque, client_entity, attachable, particle_effect, recetas, biome, feature(+rules), spawn_rules, material, fog, music, sounds, particle. **No** renombra `minecraft:*`. Reescribe valores en JSON/`.lang` y **claves** en catálogos por id (`blocks.json`, `texture_data`, `entity_sounds.entities`). Sin lo segundo, un bloque renombrado perdía su mapeo de textura (bloque “?”). Limitación conocida: dos addons que modifican la misma entidad vanilla se resuelven por componente, no aquí. |
| `CustomComponentRewriter` | Desambigua `registerCustomComponent('x', …)` duplicado: sufijo `_pf<hash4>` en el literal JS y en JSON de `blocks/items/entities` del perdedor. Idempotente, no bloqueante, no toca lógica JS. |
| `ParticleDeduplicator` | Renombra partículas duplicadas **dentro del addon perdedor y antes de copiar** (el ganador no se toca). |
| `ResourcePathRegistry` | Binarios con misma ruta: mismo MD5 → dedupe; distinto → alias `nombre_pf<hex6>.ext`. Registro de mutaciones **por fuente** (un mapa global plano se machacaba con 3+ addons). Excluye `.json`, `manifest.json`, `pack_icon.png`, `scripts/`, `texts/`. Maneja ruta con y sin extensión (terrain/item_texture referencian sin extensión). |
| `FolderAliasRegistry` | Igual para carpetas binarias: `structures/`, `fogs/`, `ui/`; prefijo `pf_<hash6>`; actualiza referencias en JSON. |
| `DuplicateIdentifierAuditor` | Red de seguridad **solo-lectura** sobre el pack ya fusionado: reporta como HIGH todo identificador declarado en más de un archivo (con ambas rutas). |
| `JsonValueRewriter` | **Única** fuente de verdad para sustituir valores string en un árbol JSON (exacto + fallback tolerante a espacios). `replaceKeys` cubre un caso distinto: renombrar **claves** solo en los objetos que el llamador declara catálogo por identificador. Nadie debe implementar caminadores propios. |
| `DeclarationFileRouter` | Enruta `entity/` y `attachables/` por **identifier declarado**, no por nombre de archivo. Dos archivos con el mismo nombre y distinto id se separan (`_pf<hash>`, token estable por id); el mismo id repartido en varios archivos se fusiona en uno. Los `minecraft:*` se reemplazan (no se mezclan). Sin esto, un `entity/mob.json` compartido absorbía la entidad del perdedor y dejaba su `client_entity` sin identifier → mob invisible. |

### Fusión
| Componente | Responsabilidad / invariantes |
|---|---|
| `PackForgeOrchestrator` | Pipeline completo (§3). Contiene `CRITICAL_DIRS*` (qué rutas **no** pasan por el deep-merge genérico), el límite de paralelismo y el empaquetado. |
| `JsonDeepMerger` | Deep-merge genérico: objetos recursivos; **arrays por unión estructural** (`concatArrays`); escalares: gana la prioridad, **idéntico ⇒ sin conflicto**; limpia claves/valores (`trim`, caracteres de control). Contexto por `ThreadLocal` (las fusiones corren en paralelo). Conflictos en `ConcurrentLinkedQueue`. `mergeBedrockEvents` apila eventos en `sequence`. Solo dentro de `components` aplica `BedrockComponentMerger`. |
| `BedrockComponentMerger` | Fusión semántica de componentes `minecraft:*` (objetos recursivos, arrays sin duplicados, resto: gana el entrante). |
| `BedrockCriticalFilesMerger` | Fusiones **específicas** por archivo (§5). *No* usa el deep-merge genérico para ellas. |
| `BedrockUiMerger` | `ui/_ui_defs.json` (unión), `_global_variables.json` (detecta colisiones de valor), pantallas compartidas (`modifications`/`controls` sin pisarse), archivos exclusivos. |
| `LenientJson` | Parser tolerante: recorta comentarios, acepta claves duplicadas (gana la **última**), nunca inventa (devuelve `null` si no parsea). |
| `ManifestGenerator` | Manifiestos RP/BP fusionados (§7), incluido `header.subpacks` (§7). |
| `DirIndexCache` | **Un** recorrido por raíz de addon, compartido por todos los componentes (`allFiles`, `jsonFiles`, `textFiles`…). Invalidar tras escribir (`invalidate`). Ganancia medida 3–10× en packs grandes. |
| `MemoryMergeBuffer`, `MergeCache` | Buffer en RAM de JSON hasta el volcado / caché de fusión en disco (`merge_cache/index.ser`). |
| `ModpackExporter` | Solo `isMinecraftInstalled`, versión de Minecraft y abrir el `.mcaddon` en el juego (el flujo real usa orquestador + servicio). |

### Referencias y dependencias
| Componente | Responsabilidad / invariantes |
|---|---|
| `BedrockRefs` | **Normalizador único**: ¿esto es id/ruta, alias propio, Molang u objeto condicional? Ante la duda, *no es referencia*. |
| `VanillaAssets` | Catálogo vanilla (geometrías, texturas, materiales, animaciones…) con actualización remota; coincidencia por **prefijo de segmento**. |
| `BedrockIdentifierIndex`, `GeometryAnimRCIndex` | Índices de ids (geometrías/animaciones/render controllers/entidades) construidos en **una pasada**, clasificando por contenido. |
| `DependencyGraphResolver` | Resolución por **grafo** de referencias reales (recursiva); si queda un `CriticalError` (referencia no vanilla sin origen) el llamador aborta. Tipos: geometría, animación, anim. controller, render controller, partícula, sonido, ruta, spawn rules, bioma, feature rules, material, music. |
| `EntityDependencyResolver` | Para cada entidad cliente garantiza geometría/textura/animación/render controller en destino; decide por **hash de contenido**; alias `_pf<hash6>` para sombreados. `validateMerge` revalida el ZIP final. |
| `LazyDependencyResolver` | Fase 2: detección de huecos (paralela, estructurada) rellenando desde los ZIP originales. |
| `EntityOrphanClassifier` | ¿Una `client_entity` sin `minecraft:entity` es defecto o contenido muerto del origen? Mira si el propio pack la usa (spawn rules, scripts, structures, `/summon`, spawn egg…). **Sin catálogo de mobs vanilla.** |

### Verificación y reporte
| Componente | Responsabilidad / invariantes |
|---|---|
| `PackForgeHealer` | `repair()` copia lo faltante desde originales y auto-repara refs (≥ 0,95); `heal()` solo diagnostica (fuzzy ≥ 0,85 / irrecuperable); máx. 100 reportes de referencia (nunca retener la exportación por miles de avisos similares). Placeholder: `*.placeholder.geo.json` caja 8×8×8 solo si no hay geometría en ningún addon. |
| `PackForgeValidator` | Un índice en memoria (`PackIndex`) al inicio; **cero** `walkTopDown`/`exists()` en bucles; progreso con throttle de 250 ms (`progressState`). |
| `MergeReferenceValidator` | Partículas, animaciones, materiales y `custom_component` ↔ `registerCustomComponent` tras renombres. Ignora `minecraft:*` y `Material.*`. |
| `MergeReportGenerator` | `validateJsonSyntax`, `auditJsonIntegrity`, consolida renombres/alias/hallazgos de script en `fusion_report.txt`. |
| `MergePipelineGuard` | Lista `REQUIRED_MERGES`; detecta fusiones declaradas no ejecutadas (y ejecutadas no declaradas). Ver deuda §13. |
| `ExportCompatibilityGate` | Decide si se puede exportar (§6). |
| `CompatibilitySummary` | Semáforo único (listo / revisar / problemas) agregando conflictos, fusiones no ejecutadas, refs rotas, healer… |
| `FusionReport` (`FusionIssue`, `FusionReportBuilder`) | Acumula incidencias por addon con severidad (`FATAL`…). |
| `ConflictRegistry` | **Única fuente de verdad** de `MergeConflict` (estado `resolved`/`resolution` persiste hasta la siguiente fusión). |

### Scripts
| Componente | Responsabilidad |
|---|---|
| `ScriptRegistry` | Descubre módulos `script` y `entry` de cada BP (carpetas **o** ZIP); guarda el texto del entry y la lista de `.js/.ts`. |
| `ScriptFlowAnalyzer` (+`ScriptFlowTypes`) | Pre-fusión sobre packs de origen: entry inexistente (bloqueo), imports rotos (aviso), **ciclos** (bloqueo), mismo entry en varios packs (aviso), dynamic properties compartidas (aviso), custom component duplicado (aviso). Devuelve `ScriptGateDecision`; solo CRITICAL impide exportar. |
| `ScriptCollisionAnalyzer` | Post-fusión sobre `scripts/`: redeclaraciones globales en el bundle y suscripciones masivas al mismo evento (orden no determinista). No bloquea. |
| `BedrockCompatibilityAnalyzer` | Preflight estático de riesgos *objetivos* de runtime. No reescribe JS (cambiar un programa ajeno es peor que reportarlo). `resolveScriptEntry`. |

### Cierre
| Componente | Responsabilidad |
|---|---|
| `BedrockDecifrator` | Restaura addons “closed-source” (JSON real escondido en rutas absurdas con decoy en la ruta canónica y claves permutadas por token). **Principio: nunca adivinar**: un anagrama es irreversible sin diccionario; se recupera lo recuperable (usando `LenientJson`) y no se inventan claves. Historial: llegó a *borrar* archivos válidos (ver DIAGNOSTICO §4.3 y §7 Fase 3). |

## 5. Fusiones dedicadas (Fase 3) y su estrategia

Ejecutadas en paralelo con `ejecutarMerge(nombre, bloque)` (registra en el guard, mide tiempos, reporta progreso).

**Resource Pack** (`BedrockCriticalFilesMerger` salvo `mergeUi`):

| Fusión | Archivo(s) | Estrategia clave |
|---|---|---|
| `mergeTerrainTexture` / `mergeItemTexture` | `textures/terrain_texture.json`, `item_texture.json` | Unión de `texture_data` por clave; claves saneadas (sin espacios/caracteres de control: causaban bloques “?” e ítems “desconocido”). Colisión de clave con valor distinto ⇒ `TEXTURE_OVERWRITE` (MEDIUM si la clave es un id custom, LOW si es vanilla); antes el segundo addon perdía su textura en silencio. |
| `mergeBlocksJson` | `blocks.json` | Unión por id; colisión con valor distinto ⇒ `BLOCK_RENDER_OVERWRITE` (MEDIUM custom / LOW vanilla). |
| `mergeLangFiles` | `texts/*.lang`, `languages.json` | Unión por clave; normaliza códigos de idioma (`canonicalBedrockLangCode`). |
| `mergeFlipbookTextures` | `textures/flipbook_textures.json` | Concatena con clave de entrada; reporta colisiones. |
| `mergeGeometryFiles` (+`mergeGeoJson`) | `models/**.geo.json` | Acepta formato **moderno y legacy** (`{"geometry.x":{…}}` como clave raíz). Dos addons con el **mismo identifier** y malla distinta: se conserva la primera y la pérdida se registra como `GEOMETRY_IDENTIFIER_COLLISION` (MEDIUM) con los dos addons culpables; si el contenido es equivalente no hay conflicto (regla 8). Antes solo iba a logcat y el mob perdía su modelo en silencio. |
| `mergeRenderControllers`, `mergeAnimations` | `render_controllers/`, `animations/`, `animation_controllers/` | Fusión por id de nivel superior. |
| `mergeSoundsJson`, `mergeSoundDefinitions` | `sounds.json`, `sounds/sound_definitions.json` | Mezcla por id; devuelve mapa de **claves renombradas** (`applySoundKeyRenames`). |
| `mergeEntityDefinitions`, `mergeEntityTextures`, `mergeAttachables` | `entity/`, `attachables/` | `entity/` y `attachables/` se enrutan por identifier (`DeclarationFileRouter`); al fusionar el mismo identifier con `format_version` distinta, `FormatVersionNegotiator` reporta `FORMAT_VERSION_MISMATCH` (MEDIUM) y deja gobernar la del último addon (nunca sube la versión). `mergeEntityTextures` copia los PNG referenciados. `detectDuplicateClientEntities` queda como red de seguridad. |
| `mergeBiomesClient`, `mergeFontGlyphMaps`, `mergeTexturesList` | `biomes_client.json`, `font/`, `textures/texture_list.json` | Unión. |
| `mergeUi` (`BedrockUiMerger`) | `ui/` | Ver §4. `_global_variables.json`: una variable global repetida con otro valor se publica para el perdedor como `$var_pf<hash6>` y sus referencias (`"$var"`) se reescriben **solo** en el UI de ese addon (3.9); antes se reportaba pero la pantalla del perdedor usaba el valor ajeno. |
| `mergeMaterialInstances` | BP `blocks/` ↔ `terrain_texture.json` | **Síncrono al final** (depende de terrain + entidades + animaciones). Si una textura de `material_instances` no está mapeada, la añade al terrain y copia el PNG desde los addons. |

**Behavior Pack:**

| Fusión | Archivo(s) | Estrategia clave |
|---|---|---|
| `mergePlayerEntity` | `player.entity.json` | Candidatos por versión; `mergePlayerEntityObjects` (componentes, eventos); conflictos numéricos registrados (`registerNumericConflicts`); los esquemas distintos también se negocian (`FormatVersionNegotiator`). Existe `mergeConservativePlayerEntity`. |
| `mergeLootTables` | `loot_tables/` | Fusión de pools (`mergeLootPools`). |
| `mergeRecipes` | `recipes/` | Por identifier. |
| `mergeTickJson` | `functions/tick.json` (y `ticks.json`) | Unión de `values`. |
| `mergeDeclarativeFamilies` | `item_catalog/`, `cameras/`, `dimensions/`, `worldgen/feature_rules/` | Familias con raíz fija e `identifier` que se copiaban por ruta: el mismo id en archivos con nombres distintos quedaba **duplicado** y dos addons con el mismo nombre se pisaban. Se enrutan con `DeclarationFileRouter` (una identidad = un archivo, separación `_pf<hash>` para ids distintos, fusión + `format_version` negociado para el mismo id) y se borran las copias obsoletas de la Fase 1. `minecraft:camera_preset` declara `identifier` en la RAÍZ (no en `description`). |
| `mergeTradingTables` | `trading/` | No declara identifier (la tabla se referencia por RUTA): fusión por ruta con la prioridad del pipeline; si dos addons publican la misma tabla con contenido distinto se reporta `TRADING_TABLE_MERGED` (LOW). |

**Genérico** (lo demás): copia por bytes; JSON no crítico → `JsonDeepMerger` en memoria (prioridad por orden). Se **excluye** `scripts/` (tiene `mergeScripts`).

## 6. Conflictos, severidad y compuerta de exportación

- **Modelos:** `Conflict` (UI; `ConflictType`: FILE_OVERLAP, ENTITY_IDENTIFIER, ITEM_IDENTIFIER, RECIPE_IDENTIFIER, SCRIPT_CONFLICT, VERSION_MISMATCH, TEXTURE_ATLAS, SOUND_OVERLAP, MANIFEST_UUID, LOOT_TABLE, PARTICLE_EFFECT, MATERIAL_INSTANCE, FORMAT_VERSION_INCOMPATIBLE, SOUND_DEFINITION, ANIMATION_CONTROLLER, PLAYER_JSON_OVERRIDE) y `MergeConflict` (registro durante la fusión; incluye `jsonPath` y `stableKey` para deduplicar). Tipos propios del registro: `TEXTURE_OVERWRITE`, `BLOCK_RENDER_OVERWRITE`, `GEOMETRY_IDENTIFIER_COLLISION`, `ENTITY_IDENTIFIER_MERGED`, `SUBPACK_RENAMED`, `SUBPACK_TIER_COLLISION`, `SUBPACK_INVALID`, `FORMAT_VERSION_MISMATCH`, `TRADING_TABLE_MERGED`, `UI_GLOBAL_VARIABLE_RENAMED`, `SCRIPT_API_MAJOR_CONFLICT`, `EXTERNAL_DEPENDENCY_DROPPED`.
- **Severidades** (`ConflictSeverity`): `CRITICAL`, `HIGH` (no descartables) · `MEDIUM`, `LOW`, `WARNING` (descartables).
- **Resoluciones** (`ConflictResolution`): `UNRESOLVED`, `FIRST_WINS`, `SECOND_WINS`, `MERGE`, `DISMISSED`. `isAutoResolved` = el motor ya decidió por prioridad (no cuenta como pendiente).
- **Detectores** (`ConflictEngine.analyze`): scripts, entidades, ítems, recetas, solapes de archivos, loot tables, sound definitions, animation controllers, `format_version`, partículas, material instances, UUID de manifest, texturas (el código habla de “quince detectores”).
- **Puntuación:** `ConflictEngine.scoreOfConflicts` → 0–100. **No recalcular `analyze` para obtener el score** (corría los detectores otra vez en el hilo de UI): usa `ExportCompatibilityGate.evaluate(addons, conflicts)`.
- **`ExportCompatibilityGate`:** `OPTIMAL ≥ 95`, `ACCEPTABLE ≥ 70`, `RISKY ≥ 40`, `BLOCKED < 40`. Bloquea si hay algún `CRITICAL` sin resolver o **≥ 5** `HIGH` sin resolver.
- **Semáforo final** (`CompatibilitySummary`): agrega todo el pipeline en un veredicto único para el usuario.

## 7. Manifiestos (`ManifestGenerator`)

El manifiesto fusionado **debe**: conservar el `min_engine_version` **máximo** (nunca `[1,0,0]`); incluir el módulo `script` si hay `scripts/` (fallback: añadirlo si se perdió); conservar dependencias `@minecraft/*` (se toma la **versión máxima**); revincular BP→RP con el UUID del header del RP fusionado; generar UUIDs nuevos (header y módulos); escribir UTF-8 **sin BOM**. `buildMergedBpManifest` / `buildMergedRpManifest` son las versiones completas; `addExperimentalCapabilitiesIfNeeded` añade capabilities cuando hacen falta. El orquestador valida el resultado (validaciones “flexibles”: soportan packs sin scripts) y llama a `applyCustomIcon`/`pickAddonIcon` para la portada.

**Dependencias por UUID (`UuidRemap`).** Al fusionar, cada pack recibe UUID nuevos, pero las `dependencies` copiadas de los manifiestos originales siguen citando los UUID **viejos** (el BP citando su RP, y un RP citando su propio BP, como hace `simple-gravestone_RP`). Bedrock exige que toda dependencia por UUID exista dentro del `.mcaddon`; si no, **no inicializa el pack** y su script nunca corre (los bloques aparecen y la lógica no hace nada). `ManifestGenerator.UuidRemap` mapea `original → nuevo`: las aristas hacia la **pareja fusionada** se eliminan (el `.mcaddon` ya activa RP y BP juntos) y el RP fusionado se vincula al UUID **real** del BP fusionado, que el orquestador fija con `forcedBpHeaderUuid`. `Fase2ManifestInvariantsTest` verifica sobre el pack real que no queda ninguna dependencia colgando.

**`min_engine_version` y `capabilities` del RP (3.6).** El RP fusionado se construía con `generateRpManifest` (UUID nuevo) y un `min_engine_version` FIJO `[1,21,0]`, sin capabilities, así que un addon que exigía una versión superior se instalaba y no cargaba, y flags como `pbr`/`raytraced` se perdían. `applyOriginalRpSettings` negocia ahora `min_engine_version` = máximo (nunca por debajo del suelo histórico `1.21.0`, igual que el BP), une `capabilities` y aplica los `subpacks`. No hereda `pack_scope` a propósito (el modpack se instala como recurso global).

**Dependencias externas por UUID (3.10).** Bedrock **no inicializa** un pack cuyas `dependencies` por UUID no están instaladas: un addon que dependía de otro pack no incluido dejaba el `.mcaddon` entero sin cargar (ni datos ni scripts) y el usuario solo veía «no carga». Tras construir ambos manifiestos, `dropExternalUuidDependencies` elimina toda dependencia por UUID que no sea el BP o el RP fusionados y cada una se reporta (`EXTERNAL_DEPENDENCY_DROPPED`, MEDIUM + nota en el informe). Las de `module_name` (`@minecraft/...`) se conservan: las resuelve el motor.

**Subpacks (`SubpackRegistry`).** `header.subpacks` declara variantes (HD/SD) que viven en carpetas del pack. El motor las **planifica antes de copiar**: `folder_name` repetido entre addons se renombra a `<carpeta>_pf<hash6>` (token SHA-256 de `uuid|carpeta`, estable entre exportaciones) y se reporta `SUBPACK_RENAMED` (LOW); dos variantes con el mismo `memory_tier` se reportan como `SUBPACK_TIER_COLLISION` (MEDIUM, el juego solo aplica una); una entrada sin `folder_name` no se puede cargar y se reporta `SUBPACK_INVALID` (MEDIUM). `ManifestGenerator.collectSubpacks` vuelca las variantes en el header fusionado (BP y RP) y `applySubpacksToManifest` lo aplica al manifiesto RP que genera el orquestador. Antes el header se reconstruía sin `subpacks`: las carpetas seguían en el `.mcaddon` pero el juego nunca las cargaba.

Además, `createMcAddon` retira los **manifiestos sueltos** (`manifest_pf1.json`, …) del paquete: se mueven a `_packforge/manifiestos_descartados/` para dejar rastro en disco sin exponer UUIDs muertos a Bedrock, que escanea `manifest.json` por subcarpeta.

## 8. Scripts en el modpack

- Cada BP con scripts se copia **íntegro** a `scripts/modules/<addon_id>/…`, preservando su ruta relativa original, de modo que los `import "./jobs.js"` internos del addon siguen resolviéndose.
- La raíz `scripts/` está **reservada al bootstrapper**. El pipeline deja copias PLANAS de los `.js` de cada addon (una por cada recorrido de copia), y dos addons con `scripts/index.js` se pisaban entre sí; además un `main.js` de addon podía sobrescribir el bootstrapper. `createMcAddon` **no empaqueta** ningún script plano: solo `scripts/main.js` y `scripts/modules/**`.
- Se genera `scripts/main.js` que **encola** cada entry con `__pfLoadModule('./modules/<id>/<entry>', '<id>')` y luego los carga **encadenados** (`Promise.resolve().then(...)`), no en paralelo. Módulos ES ⇒ scopes aislados; el encadenado garantiza que un addon que depende de otro (los DLC se comunican con el base por `scriptevent`) se registre **después**. Cada `import()` lleva `.catch(...)` para registrar el fallo sin abortar el resto.
- Las versiones `@minecraft/*` se unifican tomando el máximo semver **y después alineando los majors** (ver abajo). Un sufijo de preestreno (`1.12.0-beta`) se conserva como texto: selecciona la API beta y convertirlo a `[1,12,0]` (o al `[1,12]` de dos elementos que producía el parser anterior) lo pedía como estable.
- Si tras alinear majors un addon sigue necesitando **otro major de `@minecraft/server`**, se reporta `SCRIPT_API_MAJOR_CONFLICT` (MEDIUM) con el addon y las versiones: un solo BP carga una sola API. La solución real (varios Behavior Packs, uno por grupo de compatibilidad) **no está implementada**; el informe lo dice en vez de fingir compatibilidad.
- Un fallo al enrutar scripts **detiene la exportación**: antes se tragaba la excepción y salía un `.mcaddon` sin scripts (bloques visibles, lógica muda).
- Riesgos que se *informan*, no se arreglan: varios addons suscritos al mismo evento, dynamic properties compartidas, mismo entry.

### La familia `@minecraft/*` es UNA sola API: no se armoniza módulo a módulo

Elegir el máximo de cada módulo por separado crea combinaciones que **no existen**. Caso real medido con el registro de contenido del juego:

```
version conflict for module [@minecraft/server]
 [2.6.0] requested by [BetterMC_Real]
 [1.3.0] requested by [@minecraft/server-ui-bindings]
         requested by [@minecraft/server-ui - 1.3.0]
```

`simple-gravestone` usa `system.beforeEvents.startup` y `blockComponentRegistry` (API **2.x**) y pedía `server 2.6.0`; `SimpleEconomy` pedía `server-ui 1.3.0`, cuyo binding interno exige `server 1.x`. La fusión tomó `2.6.0` + `1.3.0` y el motor respondió `failed to create context` → **ningún script se ejecutó** (ni la tumba de gravestone ni la UI del banco). Los bloques aparecían porque son datos del BP, sin lógica.

Regla: si `@minecraft/server` queda en un major `>= 2`, todo satélite `@minecraft/*` que esté en un major menor se eleva al primer release de ese major (`<major>.0.0`). `server-ui 2.0.0` y superiores existen y declaran `@minecraft/server` 2.x como peer, así que la combinación es consistente. Al revés no: **no** se baja `server` para satisfacer a un satélite, porque eso rompe a los addons que usan la API 2.x.

Si en el futuro un addon exige majors irreconciliables incluso tras alinear (p. ej. usa `blockComponentRegistry` y otro depende de una API eliminada en 2.0), la salida arquitectónica es **empaquetar varios Behavior Packs en el mismo `.mcaddon`**, uno por grupo de compatibilidad, en vez de forzar una única versión.

### Vectores de animación: `[x, y, z]` o el bloque se descarta

Bedrock exige **exactamente 3** componentes en `position`, `rotation`, `scale`, `pivot`, `origin` y `size`. Si un merge deja 4, 5 o 6 elementos, el motor **descarta el bloque entero** y el hueso se queda en su pivote original (síntoma típico: un HUD que aparece a ras de suelo y superpuesto).

`JsonDeepMerger.concatArrays` une arrays por identidad para las listas de identidades (`conditions`, `tags`, `bones`), pero **no** para vectores: si ambos lados miden 3, gana la prioridad y se sustituye. La regla anterior conservaba todos los números y deduplicaba los strings, así que `[0,"molang",0]` fusionado consigo mismo daba 5, `["a","b",0]` daba 4 y `[0,0,0]` daba 6. `Fase0BisectAll3Test` fija el invariante sobre el pack real (0 vectores ≠ 3).

### Normalización de esquemas (`SchemaNormalizer`)

`SchemaNormalizer.inspect(json, addonId)` es **solo lectura**: informa del `format_version` que declara el archivo, de los componentes de bloque del esquema antiguo encontrados y de las variables Molang propias referenciadas. `normalize(...)` aplica **una única** transformación verificada: los componentes de bloque que Mojang renombró al introducir el esquema moderno (1.19.80+), y solo cuando el propio archivo ya declara ese esquema — por debajo, el esquema antiguo es el válido y no se toca nada.

Los nombres **y la estructura destino** están copiados de `bedrock-samples`, no deducidos. Los antiguos eran escalares y los modernos son objetos, así que un renombrado sin reestructurar el valor produciría un componente inválido:

| Antiguo (escalar) | Moderno (objeto) | Evidencia en samples |
|---|---|---|
| `minecraft:destroy_time: 1.8` | `minecraft:destructible_by_mining: {seconds_to_destroy: 1.8}` | moderno en 124/125 bloques |
| `minecraft:explosion_resistance: 3.0` | `minecraft:destructible_by_explosion: {explosion_resistance: 3.0}` | moderno en 124/125 bloques |
| `minecraft:block_light_absorption: 15` | `minecraft:light_dampening: 15` | moderno en 6/125 bloques |
| `minecraft:block_light_emission` | — sin traducir | 0 apariciones de ambos nombres: sin evidencia |

Lo que **no** hace, y por qué está en la regla 16 de `AGENTS.md`: no renombra componentes de entidad o ítem, no sube `format_version` y no reescribe variables Molang. La primera versión sí lo hacía y era destructiva (`collision_box` → `collider` habría dejado 126 de 130 entidades sin colisión).

⚠️ `detectMaxFormatVersion` devuelve lo que declare el JSON que recibe. El pipeline lo alimenta con `manifest.json`, cuyo `format_version` es la versión del **formato de manifiesto** (2 o 3), no la del esquema de contenido. `normalize` ignora ese valor cuando no tiene forma de versión de contenido (`1.x.y`), para no gatear decisiones con un número que no significa lo que parece.

**Estado de conexión (TICKET-CORE-010).** `normalize()` **no se llama desde ningún punto del pipeline**: su único invocador estaba en `mergePlayerEntity`, que solo procesa entidades cuando la única transformación verificada exige un bloque, así que era código muerto que aparentaba normalizar el jugador. Se retiró esa llamada. `normalize()` se conserva —con sus tests— como pieza verificada para cuando se conecte al camino de bloques; hasta entonces **el motor no reescribe el esquema de ningún addon**.

Lo que sí corre en cada fusión es `summarizeSchemaSpread(root)`: lee el texto de los `json` de `entities/`, `items/` y `blocks/` (con expresión regular, sin parsearlos) y el reporte muestra el reparto por addon. Ejemplo real con OriginsBE + ShulkerHead:

```
📐 Esquemas de contenido por addon:
   • OriginsBE v1.5.6 BP → 170 fichero(s), esquemas 1.10.0 a 1.26.40 (13 distintos: MEZCLA)
   • ShulkerHeadBeh     →   6 fichero(s), esquemas 1.10 a 1.13.0 (3 distintos: MEZCLA)
```

Sirve para detectar de un vistazo los addons escritos para esquemas viejos sin modificar un solo byte: Bedrock elige el esquema según el `format_version` de **cada archivo**, así que el contenido antiguo se interpreta con su propio esquema y sigue funcionando.

**La combinación que sí está rota.** `summarizeSchemaSpread` marca aparte los ficheros que declaran el esquema moderno (≥ 1.19.80) **y** usan componentes con nombre antiguo: ahí el esquema pedido no define esos nombres, así que el bloque no se carga. Se publica como conflicto `LEGACY_BLOCK_SCHEMA` (HIGH, o MEDIUM cuando se reparó) y se nombra el fichero exacto en el reporte. El caso contrario —nombres antiguos con esquema antiguo— es válido y no se marca.

**Reparación (TICKET-CORE-011).** `SchemaNormalizer.normalizeBlockFiles(mergedBpDir/blocks)` traduce esos componentes a `destructible_by_mining`, `destructible_by_explosion` y `light_dampening` antes de empaquetar, y lo deja anotado en el reporte. Está justificado con una medición **en el juego**, no con documentación: el addon `PF Legacy Schema Test` (generado por `GeneraAddonLegacyTest`) trae dos bloques con el mismo `format_version: 1.20.50`; solo aparece en el inventario creativo el que usa nombres modernos, mientras el de nombres antiguos no se carga. La reescritura se aplica **solo** a esa combinación y **solo** sobre el pack fusionado (no sobre las carpetas extraídas del usuario).

⚠️ La reparación tiene que ejecutarse **antes** de `createMcAddon`: si se hace al generar el reporte, el informe dice «traducido» pero el `.mcaddon` ya está escrito con el bloque roto (pasó, y lo delató comparar el paquete con el reporte).

### Grafo de dependencias con atribución (`DependencyDAG`, TICKET-CORE-012)

`DependencyDAG.build(sources)` modela la cadena completa —`minecraft:entity` → `client_entity` → render controller → geometría / textura / animación / partícula— y responde a la pregunta que ningún validador contestaba: **¿de quién es el eslabón roto?** Cada hueco lleva el addon que lo cita, así que el reporte distingue:

- «referencia rota» (culpaba al motor sin base) de
- «`ShulkerHeadBeh` cita X, que no aporta ningún addon» (accionable).

**Consolida, no añade un cuarto criterio.** Ya había tres recorridos de esta misma cadena (`EntityDependencyResolver`, `DependencyGraphResolver`, `MergeReferenceValidator`); AGENTS.md §6 avisa de que duplicar lógica produce «validadores con criterios divergentes», y eso ya costó miles de falsos positivos. Por eso el grafo **no parsea nada por su cuenta**: reutiliza `GeometryAnimRCIndex` para las definiciones y `VanillaAssets` para decidir si el hueco lo cubre el juego. Y a propósito **no registra conflictos nuevos**: los eslabones rotos ya los publican los validadores existentes, así que el DAG solo informa y atribuye.

Es de **solo lectura** (`SelfHealingGraphTest.elGrafoNoEscribeNiBorraFicheros` lo fija); reparar sigue siendo trabajo de `EntityDependencyResolver` y `PackForgeHealer`.

Qué añade al reporte, sobre el par real OriginsBE + ShulkerHead:

```
Grafo de dependencias: 164 entidad(es), 13 client_entity, 0 eslabón(es) roto(s)
⚪ 1 client_entity sin definición en BP (contenido muerto del origen, no lo rompe la fusión): jkslayer10:blue_shulker_head
⚪ 66 entidad(es) custom sin client_entity en el pack: no se dibujan por sí mismas (puede ser INTENCIONADO...)
```

⚠️ Las dos líneas ⚪ son **diagnóstico, no veredicto**. `client_entity` sin entidad en BP no aparece ni con el addon instalado solo (defecto del origen, no de la fusión). Y una entidad sin `client_entity` puede ser intencionada (marcadores, portadores de datos), así que se informa con esa salvedad y **no** como defecto.

**Política de stubs sintéticos.** Tras medir el problema real (73 geometrías placeholder generadas encima de modelos vanilla, que dejaron mobs sin render), la regla es: un stub solo se justifica cuando convierte «no se ve nada» en «se ve algo» **sin ocultar un diagnóstico**. Por eso quedan **excluidas** las texturas (el marcador de textura ausente que muestra Bedrock es información; sustituirlo por un PNG neutro deja al mob invisible) y las animaciones (una referencia colgante ya se **neutraliza** quitándola, que es más honesto que añadir un fichero dummy). Un render controller stub solo tendría sentido para entidades **custom** con el controlador realmente ausente, nunca para `minecraft:*`, donde la definición la pone el juego.

## 9. Capa Android relevante para el motor

- `MergeForegroundService` ejecuta `PackForgeOrchestrator` en primer plano; `MergeSession` expone `phase`, `percent`, `done/success`, `outputPath`, `reportPath`, `regenerateId` y el modo (`"editor"` exportación normal / `"library"` re-fusión con overlay).
- El estado “addons activos” de la UI y la lista que recibe el motor deben mantenerse **sincronizados** (hubo bugs de desincronía); toda exportación pasa `addonIds` en el **mismo orden** que `addonPaths`.
- `AddonStorageManager` mantiene los addons importados en almacenamiento interno (hash para duplicados, limpieza de huérfanos); `Addon.sourceFilePath` apunta a ese archivo.
- Lectura del `.mcaddon` desde Minecraft/otras apps: SAF; el resultado se comparte con `FileProvider`.

## 10. Rendimiento y memoria (reglas medidas)

| Regla | Motivo medido |
|---|---|
| `LIMITE_FUSIONES_PARALELAS = availableProcessors().coerceIn(2, 4)` (también para entidades en paralelo) | 22 fusiones simultáneas llegaron a **482 MB de pico** en un móvil con 196 MB libres → swap (156 MB) → fusión “colgada” horas. En PC sobra RAM y terminaban en 7 s. |
| `DirIndexCache` en vez de `walkTopDown()` | O(addons × fases) → O(addons); 3–10× en packs de 5000+ archivos. |
| Coste dominante = **apertura de archivos** (no CPU) | Copia por bytes con buffer y separación de `BOTH` **sin copia**. |
| Progreso con throttle ~250 ms | 20.000 emisiones por archivo colapsaban la UI. |
| Nada de `withContext(Main)` por archivo | Idem. |
| `IdentifierRemapper` paraleliza lectura con `coerceIn(2, 8)` | Lectura independiente, limitada por latencia de apertura (antivirus incluido). |
| Logs verbosos tras `logFile { }` (apagado por defecto) | Cero coste cuando está off. |

## 11. Lecciones de bugs reales → invariantes (resumen de `DIAGNOSTICO_FUSION_BETTER_MC.md`)

1. **94 % del “reporte de conflictos” era ruido**: se registraba conflicto por cada escalar repetido aunque fuese idéntico → compara valores (`canonicalOf`).
2. **Catálogo vanilla vacío** ⇒ 9 de 11 “ids rotos” eran vanilla (`animation.common.look_at_target`, `controller.render.item_sprite`…) → `VanillaAssets` único + `BedrockRefs`.
3. **El Healer fabricaba geometrías 8×8×8** sobre ids vanilla (armaduras, flechas, aldeano, zombi) y duplicaba ids reales → placeholder solo si no existe en ningún sitio; retirar placeholders obsoletos a `_packforge/`.
4. **Autocopia destructiva**: `copyTo(overwrite=true)` con origen = destino borraba la geometría; `fastCopy` truncaba el JSON → guarda por `canonicalFile`.
5. **Comentarios internos en JSON** (`// …`) hacían fallar `org.json`, el decifrador lo daba por roto y se renombraba a `.bak` fuera del `.mcaddon` (10 archivos reales perdidos) → `LenientJson` tolera comentarios en cualquier posición.
6. **`resolvePackRoot` devolvía el primer `manifest.json`** (el BP en vez del RP en `core-craft`) → selección por `modules[].type`.
7. **`identifierIndex = null`** construía un índice vacío ⇒ 438 “modelos faltantes” falsos → construir siempre el índice real.
8. **Un punto en el nombre de archivo no es una extensión** (`foo.bar` ≠ ext) y `dest.exists()` no ve el archivo con su extensión → `SafeResolveTextureExtension`.
9. **Materiales**: la clave de un material no es su nombre referenciable; hay que leer `materials/*.json` **y** `*.material` (413 materiales del juego, no 68).
10. **Un addon con varios BP** perdía datos; **topes de reporte disfrazados de cifra** (“41”) engañaban → los topes se reportan como topes.
11. **Mensajes de bloqueo con 25/100 aunque el paquete fuera válido** → la puerta de exportación se basa en conflictos reales, no en ruido.

## 12. Mapa de tests (`app/src/test/java/com/packforge/…`)

| Familia | Tests representativos |
|---|---|
| Pipeline de extremo a extremo | `MergePipelineEndToEndTest`, `MergePipelineEdgeCasesTest`, `RealFusionReportTest`, `RealPackP0AuditTest`, `AbsoluteCompatibilityAuditTest` |
| Fusión JSON/archivos | `JsonDeepMergerTest`, `AggregateFilesMergeTest`, `FlipbookTexturesMergeTest`, `SoundAndParticleMergeTest`, `BedrockUiMergerTest`, `PlayerEntityHybridMergeTest` |
| Identidad/colisiones | `IdentifierRemapperTest`, `DeclarationFileRouterTest`, `AddonIdentifierIndexTest`, `ConflictEngineCompatibilityTest`, `ConflictAutoResolutionTest`, `ResolutionApplierTest`, `AddonOrderResolverTest`, `ConflictSignalTest`, `ParticleDeduplicatorTest`, `CustomComponentRewriterTest`, `ResourcePathRegistryMutationTest`, `FolderAliasRegistryTest` |
| Referencias/dependencias | `DependencyGraphResolverTest`, `MergeReferenceValidatorTest`, `VanillaCatalogAndRefsTest`, `SafeResolveTextureExtensionTest`, `ValidateMergeTextureExtensionTest`, `ObfuscatedEntityResolutionTest` |
| Regresiones “Fase 5” | `Fase5*Test` (geometrías vanilla/legacy, materiales, integridad, huérfanas, autocopia destructiva, comentarios internos…) y `Diagnostico*Test` (pérdida de geometrías/modelos, parseo con comentarios, compuerta de compatibilidad) |
| Decifrador / ofuscación | `BedrockDecifratorTest`, `DecifratorRecoveryTest`, `CorecraftDebugTest` |
| Scripts | `ScriptFlowAnalyzerTest`, `BedrockCompatibilityAnalyzerTest` |
| Manifiestos / ZIP / modelo | `ManifestGeneratorTest`, `SubpackRegistryTest`, `AddonOwnerRegistryTest`, `DeclarativeFamiliesMergeTest`, `ZipDescriptorTest`, `AddonExtractorSecurityTest` (`core/extraction`), `AddonSafeListsTest`, `DatabaseMigrationTest`, `MinecraftTextSanitizerTest` |
| Rendimiento | `PerfRemapperEscaneoParaleloTest`, `RendimientoSeparacionSinCopiaTest`, `DiagnosticoUnaPasadaTest`, `NombreEntradaIlegalTest` |

Convenciones: los tests de motor usan `println` (no `android.util.Log`, que está stubbeado) para medir tiempos; los casos reales entran como **fixtures**, no como condiciones en el código del motor.

### 12.1 Medir antes de optimizar (TICKET-CORE-013)

El desglose del reporte separa **tramos contiguos**: «Fase 3 (críticos)», «Empaquetado» y «resto tras Generación de reporte». Ese último es el **diagnóstico y cierre**, y se llamaba «empaquetado/cierre», un nombre que invitaba a optimizar el ZIP equivocado.

Casos medidos que guiaron el Pilar 5:

| Escenario | Total | Fase 3D | Fase 3 (críticos) | Reporte y cierre | Empaquetado |
|---|---|---|---|---|---|
| Móvil, Origins + OTG (674 JSON), **13/11** | 719 s | **439 s (61 %)** | 121 s | 19 s | 2,9 s |
| Móvil, 2 addons RP (295 JSON), **20/11** | 26,9 s | 0,3 s | **16,1 s (60 %)** | 3,5 s | 0,7 s |

Entre una y otra, filtrar `GeometryAnimRCIndex` por directorio y usar la vía rápida de `org.json` sacó la Fase 3D de la ecuación: el cuello cambió de sitio. **Nunca optimices el Nivel 1 de un plan sin mirar antes esta tabla**: en el segundo caso descomprimir los ZIP costó 0,70 s, así que una caché de extracción habría ahorrado un 2,6 % como máximo.

Como la Fase 3 lanza 22 fusiones en paralelo, el total de la fase no dice **cuál** atacar. Por eso cada fusión registra su tiempo (`ejecutarMerge`, `ConcurrentHashMap`) y el reporte lo publica completo y ordenado en la sección **`0c. FUSIONES CRÍTICAS`**. Sin ese desglose solo llegaba a `println` → logcat, nunca al informe.

## 13. Observaciones y deuda técnica detectadas al documentar

Hallazgos de lectura del código (verifícalos antes de actuar; pueden haberse resuelto después):

1. **`MergePipelineGuard.REQUIRED_MERGES` corregido**: se alineó la lista con las fusiones reales del orquestador (`PackForgeOrchestrator.kt`), añadiendo `mergeTickJson` y retirando las 4 declaradas que se fusionan vía deep-merge genérico. `findMissingMerges()` ya no genera alertas falsas en `fusion_report.txt`.
2. **APIs de script de distinta mayor (parcialmente resuelto en 3.7)**: `ScriptRouterEngine.parseSemver`/`compareSemver` sí distinguen estable vs preestreno y `ManifestGenerator.normalizeVersion` ya **no** convierte `1.12.0-beta` en un array (rompía la versión). Lo que queda: el motor sigue tomando el máximo y alineando majors; si dos addons necesitan majors irreconciliables solo se **reporta** (`SCRIPT_API_MAJOR_CONFLICT`) porque exportar varios BPs no está implementado.
3. **`BedrockCriticalFilesMerger.calculateCoverage`** usa `totalEstimado = 50` (placeholder en el código) → el porcentaje de cobertura de texturas no es fiable como métrica.
4. **Lógica duplicada**: `PackForgeValidator` tiene sus propios `mergeLangFiles`/`mergeSoundsJson` privados además de los de `BedrockCriticalFilesMerger`. Riesgo de divergencia (ya hubo validadores con criterios distintos).
5. **Lint** deshabilitado en release por un crash K2 con `PackForgeHealer.kt` (archivo de 1250 líneas; candidato a dividirse).
6. **Archivos grandes** (`PackForgeOrchestrator` ≈ 2800 líneas, `BedrockCriticalFilesMerger` ≈ 2000): al editarlos, hazlo con cambios pequeños y tests, y prefiere extraer funciones a objetos propios antes que seguir creciendo.
7. **`ModpackExporter`** conserva el flujo legacy; solo `isMinecraftInstalled`, versión y apertura siguen en uso.
8. **`isSecurePath` de `AddonExtractor` es léxica (TICKET-CORE-014)** y así debe seguir: comparar rutas canónicas en Windows lanzaba `IOException` para nombres legales con comodines (`b*c.json`) y los trataba como «Zip Slip», abortando el addon entero. Las rutas se deciden sobre el **texto** (`..`, absoluta, `C:`, control), no sobre la API de archivos. Cuando toques `sanitizeEntryName`, no añadas recortes por longitud (colisionan archivos y pierden contenido en silencio).
9. **Tokens `_pf<hex>` de desambiguación: derivar del CONTENIDO, no de la ruta (TICKET-CORE-014 y 3.11).** El sufijo que se añade a partículas (`ParticleDeduplicator.tokenEstableDe`), recetas, sonidos y partículas de `BedrockCriticalFilesMerger.tokenEstableDe` venía de `dir.absolutePath.hashCode()` o `dir.name.hashCode()`. Como el directorio extraído se llama `extracted_<epochMs>_<…>`, el token cambiaba con cada exportación y los identificadores que ya viven en los mundos (`custom_dust_pfXXXX`) dejaban de resolverse al reinstalar. Ahora se deriva del SHA-256 del contenido del directorio (determinista en bytes entre exportaciones del mismo zip) y se desambigua por el **orden** de la lista cuando dos addons comparten contenido (`ParticleDeduplicator`). **3.11** extendió la misma regla a `IdentifierRemapper.tokenFor`: los ids renombrados usan `AddonOwnerRegistry.stableSeed` (UUID de los manifiestos; si no hay manifiesto legible, el nombre visible sin estampa), y el orquestador registra **todas** las raíces de pack (BP puro, RP puro, BOTH, MULTI, rescate), no solo las BOTH. La CLAVE del propietario puede seguir siendo posicional (`addon_3`) para que el ganador de una colisión sea determinista: la clave ordena, la semilla da el token. Regla: cualquier token de desambiguación nuevo debe ser estable entre exportaciones del mismo addon y único dentro de la fusión.
10. **El manifiesto RP del pipeline no usa `buildMergedRpManifest` (parcialmente resuelto en 3.6).** El orquestador lo construye con `generateRpManifest` (UUID nuevo) y le aplica `applyOriginalRpSettings` (`min_engine_version` máximo con suelo `1.21.0`, unión de `capabilities` y `subpacks`). Queda como deuda: `buildMergedRpManifest` sigue siendo el builder "completo" y solo lo usan tests; conviene unificar ambos caminos (o retirar el no usado) para que no diverjan. `pack_scope` no se hereda a propósito.

## 14. Recetas

**Añadir una fusión dedicada nueva**
1. Impleméntala en `BedrockCriticalFilesMerger` (o un objeto propio) con firma `(rpDirs/bpDirs, destDir)`; sin `walkTopDown` (usa `DirIndexCache`).
2. Añade la ruta a `CRITICAL_DIRS` / `_RP` / `_BP` para excluirla del deep-merge genérico.
3. Regístrala en la lista `rpMerges`/`bpMerges` del orquestador (entra por `ejecutarMerge`) **y** en `MergePipelineGuard.REQUIRED_MERGES` con el **mismo nombre**.
4. Registra conflictos reales en `ConflictRegistry` (no por valores idénticos) y hallazgos en `FusionReportBuilder`.
5. Test: caso con 2–3 addons que colisionan + caso sin colisión (no debe generar ruido).

**Añadir un detector de conflictos**
1. `ConflictEngine.detect…(active: List<Addon>)` + añadir sus ids a `Addon` si hace falta (`withSafeLists()`!) y a `AddonIdentifierIndex`.
2. Nuevo `ConflictType` si procede; define severidad (¿bloquea? → `CRITICAL`).
3. Migración de Room si cambias el JSON persistido del `Addon` (Gson no respeta defaults).

**Tocar referencias entre archivos**
1. Clasifica con `BedrockRefs`; resuelve vanilla con `VanillaAssets`.
2. Sustituye valores con `JsonValueRewriter`; no escribas caminadores propios.
3. Valida el cierre con `MergeReferenceValidator`/`PackForgeValidator` y añade el caso a un test `Fase5*`.

## 15. Checklist para PRs (agentes y humanos)

- [ ] ¿General y agnóstico del addon? (sin nombres de packs concretos en el motor)
- [ ] ¿Reportado al `ConflictRegistry`/`FusionReportBuilder` todo lo que se omite, renombra o repara?
- [ ] ¿Sin `catch` silencioso, sin autocopia, sin `walkTopDown` en bucles, sin trabajo en Main?
- [ ] ¿Respeta vanilla (`VanillaAssets`) y no renombra `minecraft:*`?
- [ ] ¿Tests de regresión añadidos y suite verde?
- [ ] ¿Documentación (`AGENTS.md`, este archivo, la referencia Bedrock) actualizada?
