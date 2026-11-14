# Diagnóstico · ¿Por qué el reporte de fusión de **Better MC** tiene 18.379 “conflictos” y cientos de errores?

*Investigación sobre PackForge (`C:\Users\HP\AndroidStudioProjects\PackForge`) usando el reporte real
`C:\Users\HP\Downloads\Better MC_fusion_report.txt`, el pack fusionado `Better MC.mcaddon` y los addons fuente
(`core-craft_v1.2.8`, `The-Sift-WE-0.9` BP/RP, `BP_RP_MTA_26.3`).*

---

## 1. Resumen ejecutivo

**El reporte no mide lo que parece medir.** De los **18.379 “conflictos de fusión”**, **17.313 (94,2%)** son
una línea repetida sin ruta de archivo (`• MEDIUM @ / Colisión de clave 'X': el valor se sobrescribe.`) que el
motor emite por **cada clave escalar que aparece en dos versiones del mismo archivo, incluso cuando el valor es
idéntico**. Los **163 conflictos “HIGH”** son en su mayoría **falsos positivos** (147 de 163), y los errores
`❌ [ZIP]` se reducen a **11 identificadores distintos**: **9 de ellos son assets VANILLA** que el juego resuelve
solo (p. ej. `animation.common.look_at_target`, `controller.render.item_sprite`), y solo 2 pertenecen al addon.

Al mismo tiempo, el reporte **no reporta el daño real** que sí está en el `.mcaddon`:

| Daño real verificado en `Better MC.mcaddon` | Cantidad | Efecto en juego |
|---|---|---|
| Placeholders de geometría que **sobrescriben identificadores VANILLA** (`geometry.humanoid.armor.*`, `geometry.player.armor.*`, `geometry.arrow`, `geometry.item_sprite`, `geometry.default`, `geometry.villager.v1.8`, `geometry.zombie.v1.8`) | **13** | **Toda la armadura**, las flechas, los proyectiles, el aldeano y el zombi v1.8 pasan a renderizarse como caja 8×8×8 |
| Placeholders que **duplican un identificador que ya existe** en el propio pack (el modelo real también está ahí) | **148** | Definición duplicada del mismo `identifier` ⇒ el juego elige una de forma no determinista: el mob puede salir como caja aunque tenga modelo |
| Archivos canónicos **eliminados** del paquete (renombrados a `.bak` y luego excluidos del ZIP) | **780** (según el reporte) | Recetas, bloques, loot tables, etc. que el juego ya no ve. Verificado: `blocks/celestite/` conserva *large/medium bud* y pierde `small_celestite_bud.json`; `recipes/emerald/` desaparece entero |
| Identificadores **definidos más de una vez** (67 render controllers, 1 animation controller, 1 animación, 149 geometrías) | **218** | “Último en cargar gana”, en silencio. El motor renombra el **archivo** (`..._pf478725.json`) pero **no el identificador de dentro** |
| Texturas de ítem realmente ausentes (`copper_*`, `magma_armor_*`, `sapphire_melon`, `ruby_melon`, `thor_scythe`…) | **16** | Ítems con textura rota (esto sí lo reporta, pero inflado de 16 a 100 + “445 modelos”) |
| Archivo de UI con comentarios `//` (JSON no estricto) | **1** | El reporte afirma “✅ Todos los JSON son sintácticamente válidos”: **es falso** |

**Conclusión:** el problema no es que el modpack esté roto por los addons, sino que **el motor de fusión y su
validador** (a) no conocen la línea base vanilla, (b) no distinguen “referencia” de “valor de dato”, (c) cuentan
como conflicto cualquier clave repetida, (d) **fabrican y eliminan contenido** y (e) generan un reporte que
oculta los 4 problemas reales entre 18.000 líneas de ruido.

---

## 2. Cómo se investigó (evidencia reproducible)

| Artefacto | Contenido |
|---|---|
| `.research/bettermc/OUTPUT_BetterMC_mcaddon/` | El `.mcaddon` del reporte, extraído (19.637 archivos: `BP_PackForge` 7.598 + `RP_PackForge` 12.037 + `PackForge.ID`) |
| `.research/bettermc/SRC_corecraft/`, `SRC_sift_bp/`, `SRC_sift_rp/`, `SRC_mta/` | Addons fuente extraídos |
| `.research/output_deep.py` → `output_deep.txt` | Cierre de referencias del pack fusionado (geometrías/animaciones/RC/texturas) |
| `.research/output_dupes.py` → `output_dupes.txt` | Identificadores duplicados y archivos sospechosos |
| `.research/final_checks.py` → `final_checks.txt` | Placeholders que pisan ids vanilla, texturas realmente ausentes, JSON inválidos |
| `.research/report_stats.py` / `report_conflicts.py` | Estadística completa del `.txt` (18.379 entradas clasificadas) |
| `.research/corecraft_deep.py` / `zip_decoys.py` / `pf_suffix.py` | Contenido real del addon cerrado, detectores de señuelos, colisiones `_pf` |
| `vanilla_all_keys_complete.txt` (raíz del repo) | Catálogo vanilla real (2.218 ids de `bedrock-samples/resource_pack`) |

Prueba de que el `.mcaddon` analizado **es** la salida de esa fusión: contiene `PackForge.ID`, los archivos
`ui/hud_screen_pf1.json` / `_ui_defs_pf1.json` y exactamente **163** `*.placeholder.geo.json`.

---

## 3. Taxonomía: qué es cada bloque del reporte

| Bloque del reporte | Nº | Veredicto | Causa |
|---|---|---|---|
| `MEDIUM @ (sin ruta) · Colisión de clave 'X': el valor se sobrescribe.` | 17.313 | **Ruido** | `JsonDeepMerger` registra conflicto por cada escalar presente en dos versiones del mismo archivo, **sin comparar valores**; la ruta sale de un `ThreadLocal` que nadie inicializa (`JsonDeepMerger.kt:152,160`; `setMergeContext` solo se llama desde `mergePackType`, `PackForgeOrchestrator.kt:1580`, que **no tiene llamadores** → ruta siempre `""`) |
| `HIGH @ item_texture.json · Referencia '["textures/..."]' sin origen` | 100 | 16 reales / **84 falsos** | Se serializa un **array JSON** como si fuera un id y se compara con un registro que **no lleva el prefijo `textures/`**. Verificado: `textures/items/amethyst_helmet.png` y `textures/cc/item/tr__24fc_68ca.png` **existen** en el pack |
| `HIGH @ entity/ · Referencia rota tras fusión: animación/RC/geometría …` | 30 | **Falsos** | `MergeReferenceValidator.isVanillaOrEngine` (`MergeReferenceValidator.kt:316-317`) solo filtra `minecraft:` y `Material.`; no consulta `VanillaAssets` |
| `HIGH @ Better MC.mcaddon · ❌ [ZIP] …` | 30 | **Falsos** | Duplicado de la validación ZIP; los 11 ids se reducen a 9 vanilla + 2 del addon |
| `HIGH @ scripts/main.js` (avisos de `world.*.itemUseOn`) | 2 | **Informativos mal clasificados** | Un `info` de `ScriptFlowAnalyzer` entra al registro de conflictos con severidad HIGH |
| `HIGH @ models/entity/amethyst_helmet - Copie.json · CLAVES_CIFRADAS` | 1 | **Falso** | Es una **geometría en formato legacy** (raíz `"geometry.customitems_modarmor1"`), que no está en `CANONICAL_KEYS` (`MergeReportGenerator.kt:59-74`). Además el archivo es una copia de Windows sin referencias externas |
| `LOW @ loot_tables/cc/**`, `ui/*.json`, `textures/**/ui/*.json` | ~860 | **Falsos** | `CANONICAL_KEYS` no incluye `pools` (loot tables), `namespace` / `ui_defs` (UI), `nineslice_size` / `base_size` (texture sets) ⇒ “cifrado por sustitución: el juego ignorará este archivo” |
| `LOW @ textures/flipbook_textures.json`, `ui/hud_screen.json` | 3 | Reales pero menores | Colisión legítima de definiciones; se conserva la de un addon |
| `❌ [ZIP] … ausente` (sección 6) | 243 líneas → **11 ids** | 9 vanilla / 2 del addon | `VanillaAssets` incompleto (ver §4.1) |
| `Texturas faltantes: 16 · Modelos faltantes: 445 · Entidades incompletas: 161` | — | Números inflados | Cuenta referencias vanilla, expresiones Molang y **objetos JSON** como nombres de archivo |
| `SIN CONTRAPARTE EN CLARO (780)` + `pasados a .bak` | 780 | **Daño real, mal explicado** | Los `.bak` **no van en el `.mcaddon`**: `addFolderToZip` excluye `ext == "bak"` (`PackForgeOrchestrator.kt:2384-2387`). El usuario no recibe ni el original ni la copia, y el reporte afirma que “Bedrock dejará de parsearlos” como si fuera una mitigación |
| `JSONs procesados: 0` | — | **Síntoma grave** | La fase de fusión JSON (`mergePackType`) es **código muerto**: la fusión real copia bytes (`fastMergeAllSources`). El contador nunca sube |
| `18379 pendientes, 0 resueltos` | — | Estructural | `resolved` siempre `false`; el único mutador es la UI; el registro se borra al inicio de cada fusión (`PackForgeOrchestrator.kt:293-294`) |
| Contadores del semáforo (132 + 17.353 + 3 = 17.488 ≠ 18.379) | — | **Inconsistencia** | El resumen se calcula **antes** de añadir los últimos conflictos |

---

## 4. Causas raíz

### 4.1 El catálogo vanilla del motor está vacío de lo que importa (y el archivo bueno no se carga)

`VanillaAssets.kt` es la única fuente de “esto es vanilla, no lo reportes”. Le faltan exactamente los prefijos
que disparan casi todos los `❌ [ZIP]`:

| Identificador reportado como ausente | ¿Vanilla? | Falta en `VanillaAssets.kt` |
|---|---|---|
| `animation.common.look_at_target` (211 apariciones) | Sí | prefijo `animation.common.` (listas 149-195) |
| `animation.actor.billboard` (8) | Sí | `animation.actor.` |
| `animation.arrow.move` (1) | Sí | `animation.arrow.` |
| `controller.animation.iron_golem.move` / `.arm_movement` (6) | Sí | `controller.animation.iron_golem.` |
| `controller.render.item_sprite` (8) | Sí | entrada exacta (existe `controller.render.item`, no la de *sprite*) |
| `textures/entity/boat/chest_boat_oak` (4) | Sí | prefijo `textures/entity/boat/` |
| `textures/entity/arrows` (1) | Sí | `textures/entity/arrows` |
| `textures/particle/soul` | Sí | prefijo `textures/particle/` |

Agravantes verificados:

* Existe **`vanilla_all_keys_complete.txt`** en la raíz del repo (2.218 ids de `bedrock-samples/resource_pack`)
  que **sí contiene** `animation.common.look_at_target`, `animation.actor.billboard`,
  `controller.render.item_sprite`, `controller.animation.iron_golem.move/arm_movement`, `geometry.item_sprite`…
  **pero el motor no lo carga** (no hay `assets/`, no hay lector local).
* La única “actualización” del catálogo descarga
  `vanilla_identifiers_complete.txt` (`VanillaAssets.kt:313`), que es una lista de **1998 ids de bloques/ítems**
  (`minecraft:acacia_boat`…). No aporta ni una animación, ni un render controller, ni una textura; y en
  `mergeRemoteCatalog` (`:373-415`) esas entradas caen por descarte en `VANILLA_SOUND_PREFIXES` ⇒ **el catálogo se
  contamina con basura**.
* Hay **tres catálogos divergentes**: `VanillaAssets.kt`, `VanillaResources` dentro de
  `DependencyGraphResolver.kt:96-192` (que sí tiene `controller.render.item_sprite`) y
  `MergeReferenceValidator.isVanillaOrEngine` (`:316-317`), que no usa ninguno.
* `isVanillaAnimation` **nunca lee** `VANILLA_ANIMATIONS` (declarado vacío, `:143`; escrito solo por el catálogo
  remoto, `:397`) ⇒ esa rama es código muerto.

**Demostración empírica** (cierre de referencias del pack fusionado): de 699 definiciones de cliente,
**785 referencias de animación (53 ids distintos) y 65 de render controller (4 ids) no se resuelven dentro del
pack — y las 57 distintas están TODAS en el catálogo vanilla**. Es decir: **0 referencias de animación/RC rotas
de verdad**; las 41 “referencias rotas” del reporte son vanilla.

### 4.2 El Healer fabrica geometrías 8×8×8 — y no comprueba si ya existen

`PackForgeHealer.repair()` (`PackForgeHealer.kt:133-180`):

* El inventario de modelos se hace **por NOMBRE DE ARCHIVO** dentro de `models/` del pack fusionado
  (`:740-751`). El addon cerrado **CoreCraft guarda sus modelos con nombres ofuscados**
  (`models/entity/2jm1s73o.json`, `models/blocks/pqbf68ao.json`): el inventario nunca encuentra
  `models/entity/geometry.cc.villager_baby.json`, declara el modelo “faltante” y **genera un placeholder**.
* El marcador de posición (`:631-646`) declara **el identificador real**:
  `{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.cc.villager_baby",
  "texture_width":16,"texture_height":16,…},"bones":[{"name":"root","cubes":[{"origin":[-4,0,-4],
  "size":[8,8,8],"uv":[0,0]}]}]}]}`
* `repair()` **no consulta `VanillaAssets`** (solo `heal()`/`validateCandidate` lo hacen, `:484` y `:496`) ⇒
  genera placeholders para `geometry.humanoid.armor.helmet`, `geometry.item_sprite`, `geometry.arrow`,
  `geometry.default`, `geometry.player.armor.*`, `geometry.villager.v1.8`, `geometry.zombie.v1.8`.

**Medición en el `.mcaddon`:** 163 placeholders → **148 duplican un `identifier` ya definido** por el modelo real
presente en el pack; **13 pisan identificadores VANILLA**. Caso extremo:
`RP_PackForge/models/entity/geometry.arrow.placeholder.geo.json` define `geometry.arrow` ⇒ **todas las flechas
del juego** pueden pasar a caja. Igual con `geometry.humanoid.armor.*` ⇒ **toda la armadura**.
Nótese que los placeholders **no sustituyen** el archivo roto: se **añaden** como un archivo extra con el mismo id.

Además: `RepairResult.autoRepairedReferences` nunca se asigna ⇒ el “0 referencia(s) auto-reparada(s)” del
reporte es estructural, no un resultado; y las “11 texturas copiadas” se copian a
`textures/entity/<stem>.png` **hardcodeado** (`:116-118`), así que una referencia a `textures/blocks/dirt` acaba
en `textures/entity/dirt.png` (la referencia sigue rota y cuenta como reparada).

### 4.3 El decifrador no descifra: borra

* No hay ningún decodificador de sustitución en `BedrockDecifrator.kt`; solo detecta **anagramas** de claves y,
  ante la duda, “neutraliza”: renombra el archivo roto a `.bak` y **el exportador excluye `*.bak`**
  (`PackForgeOrchestrator.kt:2384-2387`) y además borra el directorio temporal (`:1321`).
  ⇒ **En el `.mcaddon` no queda ni el original ni la copia.** Verificado: 0 entradas `.bak` en los 19.636
  archivos del ZIP; `BP_PackForge/blocks/celestite/` conserva `large_celestite_bud.json` y
  `medium_celestite_bud.json` pero **no** `small_celestite_bud.json`; `recipes/emerald/` no existe.
* El emparejamiento claro↔roto **nunca funciona en subcarpetas**: `acceptedRootsFor` compara el path completo del
  padre (`:231-251`) y en BP solo acepta `entities`, `blocks`, `items`, `recipes` exactos ⇒
  `blocks/celestite/…` y `recipes/emerald/…` salen por `continue` (`:441`) sin intentarlo. De ahí
  “restaurados: **0**”.
* El decifrador solo mira el **pack ya fusionado**, nunca los `.mcaddon` originales, aunque el motor ya tiene la
  maquinaria correcta (índice por **identifier**): `GeometryAnimRCIndex` +
  `EntityDependencyResolver.resolve` recuperaron **83 geometrías y 54 render controllers** por contenido.
  ⇒ El reporte **no puede** afirmar “es irrecuperable”: hay que distinguir “no existe copia” de “no se intentó”.

### 4.4 El conflicto es un `else` sin comparación de valores

`JsonDeepMerger.deepMerge` (`:138-171`): en la rama `else` (valores primitivos) se registra `MergeConflict`
**siempre que la clave ya exista**, sin comprobar si el valor es el mismo. De ahí:

* 17.313 entradas cuyo mensaje habla de “se sobrescribe” cuando en la mayoría de los casos el valor es idéntico.
* Ruta vacía (`filePath = currentFilePath.get() ?: ""`, `:152`) porque quien escribe el contexto
  (`setMergeContext`) está dentro de `mergePackType`, **código muerto**. Los 5 `deepMerge` que sí se usan
  (desde `BedrockCriticalFilesMerger`) no pasan contexto.
* Severidad literal `MEDIUM` (`:156`), sin criterio por tipo de archivo ni por impacto.
* La rama de prioridad es inalcanzable (`:139-140`: ambos índices `Int.MAX_VALUE`) ⇒ cuando los valores **sí**
  difieren, **gana el último addon que itera** (orden de directorio), no la prioridad del usuario, y queda
  registrado con ruta vacía: **inauditable**.
* El texto se imprime 1 vez por conflicto con 2 líneas cada uno ⇒ 34.000 líneas de `.txt`. No hay dedup,
  agrupación ni tope (el patrón correcto ya existe en `MergeReportGenerator.buildIntegritySummary`, `:529-565`).

### 4.5 Duplicar un identificador no es “conflicto” para el motor

Cuando dos addons traen el mismo **path** (`render_controllers/myvvhhuc.json`), el motor renombra el archivo a
`myvvhhuc_pf478725.json` (`_pf<hex>`) pero **no renombra los identificadores de dentro**. Medido:
**67 render controllers, 1 animation controller y 1 animación** quedan definidos dos veces, y
`myvvhhuc.json` vs `myvvhhuc_pf478725.json` son el **mismo contenido** (uno minificado, otro con formato) ⇒
definición duplicada inútil; en `controller.animation.cc.casting` y `animation.cc.common.eye` los dos archivos
son **distintos** ⇒ conflicto real resuelto por azar. Esto es exactamente lo que el motor *debería* reportar como
HIGH y no reporta.

### 4.6 Validadores que stringifican datos y no filtran Molang

* `PackForgeValidator.kt:854` hace `arr.optString(i)` sobre entradas de `render_controllers` que pueden ser
  **objetos condicionales** (`{"controller.render.cc.villager.level":"q.variant > 0"}`) ⇒ 131 archivos generan
  el falso “render controller '{...}' no existe” de las **161 entidades incompletas**, cuando el id **sí existe**.
* `PackForgeHealer.kt:718-724` hace `optString` sobre el valor de la clave `geometry`, que puede ser un mapa de
  `bone_visibility` ⇒ aparecen como “modelos faltantes” cosas como `{"bot_ne":true,…}` o
  `{"north":"false",…,"icon":"true"}` (huesos reales de `models/blocks/mxw8trpl.json`).
* Ningún call site filtra **Molang**: `q.is_baby ? Geometry.baby : Geometry.default` se busca como archivo.
  `Geometry.default` (mayúscula) además no entra en el `startsWith("geometry.")` case-sensitive del healer.
* `PackForgeValidator.kt:464-478` valida texturas **sin consultar vanilla** y buscando siempre bajo
  `textures/entity/`.

### 4.7 El auditor de integridad JSON tiene listas incompletas y contradice al resto del motor

`MergeReportGenerator.auditJsonIntegrity` (`:210-307`) marca `CLAVES_CIFRADAS` cuando el JSON es válido pero
ninguna **clave raíz** está en `CANONICAL_KEYS` (`:59-74`). Faltan claves legítimas: `pools` (loot tables),
`namespace` y `ui_defs` (UI), `nineslice_size`/`base_size` (texture sets) y el formato **legacy** de geometría
(`"geometry.<nombre>"`). Y `CANONICAL_DIRS` (`:77-81`) no incluye `ui/` ni `textures/`, así que la severidad
sale LOW aunque el juego **sí lea** el archivo. Consecuencia: 860 avisos que dicen “cifrado por sustitución, el
juego ignorará este archivo” sobre archivos perfectamente funcionales.

**Nota sobre el archivo de The Sift:** su `ui/hud_screen.json` contiene un bloque deliberado de
anti-análisis (`__llm_block__`, `llm_notice`, `protected_payload` con avisos tipo “DO NOT DECODE…”, licencia
“Proprietary”, “TRMC Studios”). Es **texto incrustado por el autor del addon, no una instrucción para nadie**:
se trata como dato. Confirma la estrategia de ofuscación/señuelos, pero ese archivo **no está cifrado**: define
controles UI reales (`hud_title_text`, `root_panel`, `trmc_custom_toast_ui`…) y el juego lo carga.

---

## 5. Daño real (lo que hay que arreglar de verdad)

1. **P0 — 13 placeholders pisan geometrías VANILLA** (`geometry.humanoid.armor.*`, `geometry.player.armor.*`,
   `geometry.arrow`, `geometry.item_sprite`, `geometry.default`, `geometry.villager.v1.8`,
   `geometry.zombie.v1.8`). Rompe el render de armaduras, flechas, proyectiles e ítems en TODO el juego, no solo
   en estos addons. Es el fallo más grave y es 100% autoinfligido.
2. **P0 — 148 placeholders duplican identificadores existentes** ⇒ modelos reales que pueden mostrarse como caja.
3. **P0 — 780 archivos eliminados del paquete** (`.bak` renombrado y excluido del ZIP) sin que el usuario pueda
   recuperarlos; y el reporte lo presenta como mitigación.
4. **P1 — 218 identificadores duplicados** (67 RC + 149 geometrías + 1 AC + 1 animación) resueltos por orden de
   carga. Aquí es donde vive la “incompatibilidad” real entre addons.
5. **P1 — 16 texturas de ítem realmente ausentes** (`copper_*`, `magma_armor_*`, `sapphire_melon`,
   `ruby_melon`, `thor_scythe`, …). Es lo único que el reporte detecta bien, pero lo infla a 100 + 445.
6. **P1 — `textures/cc/hc/particles/surface_fog`**: bug del addon; el asset real es
   `textures/cc/part/sur_fog_0cf5.png` (está en `textures_list.json`). El healer no lo encuentra porque copia a
   una carpeta fija.
7. **P2 — UI con `//`** y la afirmación “todos los JSON son válidos”.
8. **P2 — contadores del reporte inconsistentes** (17.488 ≠ 18.379; “JSONs procesados: 0”; 445 modelos vs 0
   reales; 0 auto-reparadas estructural).

---

## 6. Plan de solución definitiva (para que CUALQUIER addon sea 100% compatible)

### Fase 0 — Una sola fuente de verdad de “qué existe”
* Cargar **`vanilla_all_keys_complete.txt`** (ya está en el repo) en `VanillaAssets` como catálogo local, y
  ampliarlo con los prefijos faltantes (`animation.common.`, `animation.actor.`, `animation.arrow.`,
  `animation.humanoid.`, `controller.animation.iron_golem.`, `controller.render.item_sprite`,
  `textures/entity/boat/`, `textures/entity/arrows`, `textures/particle/`, `textures/entity/<mob>` legacy…).
* Corregir la URL remota (hoy apunta al archivo de bloques/ítems) y **eliminar los 3 catálogos divergentes**
  (`VanillaAssets`, `VanillaResources`, `MergeReferenceValidator.isVanillaOrEngine` ⇒ uno solo).
* Hacer que `isVanillaAnimation` consulte también `VANILLA_ANIMATIONS`.
* **Criterio de aceptación:** una referencia vanilla nunca genera error, en ningún validador.

### Fase 1 — Un normalizador único de referencias (antes de emitir cualquier error)
* Descartar valores que no son ids: Molang (`?`, `q.`, `v.`, `math.`, `variable.`, `Geometry.`), objetos,
  arrays, booleanos. Prohibir `optString` para leer ids.
* Extraer los ids de `render_controllers` de las **claves** de los objetos (como ya hace bien
  `EntityDependencyResolver.readRenderControllerIds`), no de su texto.
* Resolver geometrías **por identificador de contenido** (`GeometryAnimRCIndex`), nunca por nombre de archivo.
* **Criterio de aceptación:** 0 falsos positivos en el pack de prueba; los 445 “modelos faltantes” → 0.

### Fase 2 — Healer no destructivo
* No generar **jamás** un placeholder cuyo `identifier` ya exista (ni propio ni vanilla); si se genera, usar un
  id nuevo y registrar la entidad afectada.
* Consultar `VanillaAssets` en `repair()` y `autoRepairReferences()`.
* Copiar al **destino real** de la referencia, no a `textures/entity/`.
* **Criterio de aceptación:** 0 placeholders duplicados, 0 ids vanilla sobrescritos.

### Fase 3 — Decifrador que recupera en vez de borrar
* Emparejar claro↔roto **por identifier** (no por tokens de nombre) y buscar también en los `.mcaddon`
  originales (`findFileInAddons` / `GeometryAnimRCIndex`), no solo en el pack fusionado.
* `acceptedRootsFor` por **primer segmento** de carpeta (hoy bloquea `blocks/<sub>`, `recipes/<sub>`,
  `items/<sub>`, `entity/<sub>`).
* **Nunca borrar evidencia**: conservar `.bak`/rutas enterradas dentro del `.mcaddon` (p. ej. en
  `_packforge/`) o al menos no borrar el temporal antes de que el usuario pueda reintentar; y reportar la razón
  exacta de cada no-recuperación (“no hay copia” vs “no se intentó”).
* **Criterio de aceptación:** 0 archivos eliminados sin aviso; el reporte distingue ambas causas.

### Fase 4 — Conflictos de verdad
* Añadir el test de **igualdad de valores** antes de registrar conflicto (elimina el 94% del ruido) y adjuntar
  **ruta + ruta JSON (breadcrumb)**; pasar la ruta por parámetro (no `ThreadLocal`).
* Detectar y reportar como HIGH los **identificadores duplicados** (geometría/animación/anim-controller/
  render-controller/entidad) y **renombrar el identificador** cuando se deduplica un archivo por path
  (`_pf<hex>`), no solo el nombre del archivo.
* Resolver por prioridad de addon declarada por el usuario (hoy la rama de prioridad es inalcanzable).
* Severidad calculada (no literal MEDIUM) y **dedup + agrupación con tope** en el reporte.
* **Criterio de aceptación:** el reporte baja de 18.379 a <100 entradas, todas accionables y con ruta.

### Fase 5 — Integridad/reporte coherente
* `CANONICAL_KEYS`/`CANONICAL_DIRS`: añadir `pools`, `namespace`, `ui_defs`, `nineslice_size`, `base_size`,
  prefijo legacy `geometry.`, y `ui/` + `textures/**/ui/` como rutas leídas por el motor.
* Detectar copias de Windows (` - Copie`, ` - Copy`, `(1)`) y duplicados por hash.
* Contadores calculados al final (semáforo consistente), “modelos copiados” separado de “placeholders”, y
  eliminar el “0 auto-reparadas” estructural.
* Detectar JSON no estricto (comentarios `//`, BOM, `//bridge-file-version`) y decidir si se normaliza o se
  reporta como HIGH real.

---

## 7. Estado de la corrección — P0 implementado (Fases 0, 1 y 2)

Implementado y verificado con la suite completa (`.\run-tests.ps1`) **y** con una auditoría opt-in
sobre el `.mcaddon` real que produjo el reporte (`RealPackP0AuditTest`, variable `PF_REAL_PACK_DIR`).

### Fase 0 — catálogo vanilla único
| Cambio | Archivo |
|---|---|
| El catálogo real de Mojang (`vanilla_all_keys_complete.txt`: 638 animaciones, 184 `controller.animation.*`, 214 `controller.render.*`, 225 geometrías) se **empaqueta** y se carga una vez (`assets/` + classpath para tests) | `app/src/main/assets/…`, `app/build.gradle.kts` (sourceSets), [`VanillaAssets.kt`](app/src/main/java/com/packforge/app/domain/engine/VanillaAssets.kt) `loadBundledCatalog` / `loadCatalogText` |
| Se cargan al arrancar la app y al empezar cada fusión | [`PackForgeApplication.kt`](app/src/main/java/com/packforge/app/PackForgeApplication.kt), [`PackForgeOrchestrator.kt:306`](app/src/main/java/com/packforge/app/domain/engine/PackForgeOrchestrator.kt#L306) |
| Prefijos que faltaban: `animation.common.`, `animation.actor.`, `animation.arrow.`, `controller.animation.iron_golem.`, `controller.render.item_sprite`, `textures/entity/boat/`, `textures/entity/arrows`, `textures/particle/`, familias de armadura/escudo/cofre/letreros/proyectiles | `VanillaAssets.kt` |
| `isVanillaAnimation` ahora consulta también `VANILLA_ANIMATIONS` (antes rama muerta) y la URL remota apunta al catálogo correcto; el clasificador remoto ya no vuelca bloques/ítems en los sonidos | `VanillaAssets.kt` |
| Los 3 catálogos divergentes se unifican: `VanillaResources` y `MergeReferenceValidator.isVanillaOrEngine` consultan primero `VanillaAssets` | [`DependencyGraphResolver.kt`](app/src/main/java/com/packforge/app/domain/engine/DependencyGraphResolver.kt), [`MergeReferenceValidator.kt`](app/src/main/java/com/packforge/app/domain/engine/MergeReferenceValidator.kt) |

### Fase 1 — normalizador único de referencias
Nuevo [`BedrockRefs.kt`](app/src/main/java/com/packforge/app/domain/engine/BedrockRefs.kt): decide qué es un id,
una ruta, un alias (`Geometry.default`, `Texture.default`) o Molang (`q.variant > 0`, `q.is_baby ? … : …`), y
extrae los render controllers de las **claves** de los objetos condicionales.
Aplicado en `EntityDependencyResolver` (fase `[ZIP]`), `PackForgeValidator` (integridad de entidades y
referencias), `PackForgeHealer`, `LazyDependencyResolver` y `MergeReferenceValidator`.
Se eliminaron además los `optString` sobre arrays: `item_texture.json` publicaba
`Referencia '["textures/…"]' sin origen` (100 conflictos HIGH, 84 falsos).

### Fase 2 — healer no destructivo
* Resuelve modelos por **identificador de contenido** (`collectDeclaredGeometryIds`), no por nombre de archivo.
* **Nunca** genera un placeholder si el `identifier` ya existe o si es **vanilla**.
* Copia las texturas a **la ruta referenciada** (antes siempre a `textures/entity/<stem>.png`).
* Filtra vanilla/alias antes de declarar “sin textura en ningún addon”.
* **Retira a `_packforge/stale_placeholders/`** (fuera del `.mcaddon`) los placeholders heredados que duplican un
  modelo real o pisan un id vanilla, para reparar también packs ya fusionados.

### Medición sobre el pack real (`Better MC.mcaddon`)

```
P0-AUDIT | geometrías declaradas antes: 343
P0-AUDIT | placeholders previos en el pack de entrada: 163
P0-AUDIT | repair() => placeholdersNuevos=0, obsoletosRetirados=163,
          faltanTexturas=33, faltanModelos=0
P0-AUDIT | placeholders tras la limpieza: 0 (antes 163)
P0-AUDIT | placeholders que aún pisan un id VANILLA: 0
```

Suite completa: **277 tests, 0 fallos** (`.\run-tests.ps1`).

| Métrica del reporte | Antes | Después (medido) |
|---|---|---|
| Placeholders 8×8×8 en el pack | 163 | **0** (y 0 nuevos si se vuelve a fusionar) |
| Ids vanilla sobrescritos (`geometry.arrow`, `geometry.humanoid.armor.*`…) | 13 | **0** |
| Placeholders que duplican un identificador existente | 148 | **0** (los 163 retirados a `_packforge/…bak`) |
| “Modelos faltantes” | 445 | **0** |
| `❌ [ZIP]` (243 líneas, 11 ids) | 243 | **0** esperado (test de extremo a extremo con los 5 ids vanilla) |
| “Referencias rotas tras fusión” / entidades incompletas (41/161) | 212 | **0** esperado |
| `item_texture.json` “sin origen” (100 HIGH) | 100 | **16** (las realmente ausentes; verificado con la auditoría independiente) |
| Texturas aún faltantes (healer) | 84 | **33** (sin addons de origen que copiar; en la app se copian) |

### Tests añadidos
`VanillaCatalogAndRefsTest` (12 casos: catálogo, ids que generaban los 243 errores, RC por claves, Molang/alias,
`bone_visibility`, placeholders duplicados/vanilla/legítimos, limpieza de obsoletos, destino real de texturas,
vanilla en el healer, arrays de `item_texture.json`, validación `[ZIP]` de extremo a extremo) y
`RealPackP0AuditTest` (opt-in sobre un pack real).

### Fase 3 — implementada y verificada (el decifrador recupera, no borra)

| Cambio | Archivo (línea) |
|---|---|
| `decrypt()` recibe los addons **ORIGINALES**; el orquestador le pasa `extractedDirs` | [`PackForgeOrchestrator.kt:968`](app/src/main/java/com/packforge/app/domain/engine/PackForgeOrchestrator.kt#L968) |
| `recoverFromSources()`: recupera por **misma ruta → mismo nombre → mismo identificador**; ante ambigüedad no adivina y lo reporta | [`BedrockDecifrator.kt:864-923`](app/src/main/java/com/packforge/app/domain/engine/BedrockDecifrator.kt#L864-L923) |
| `acceptedRootsFor()` clasifica por **primer segmento** (antes `blocks/<sub>`, `recipes/<sub>`, `items/<sub>`, `entity/<sub>` nunca emparejaban) | [`BedrockDecifrator.kt:252`](app/src/main/java/com/packforge/app/domain/engine/BedrockDecifrator.kt#L252) |
| `SourceIndex` / `buildIdIndex()`: índice de los addons originales (ruta, nombre, identificador) | [`BedrockDecifrator.kt:728-806`](app/src/main/java/com/packforge/app/domain/engine/BedrockDecifrator.kt#L728-L806) y [`:930`](app/src/main/java/com/packforge/app/domain/engine/BedrockDecifrator.kt#L930) |
| Lo que **no se intenta** ya no se neutraliza; cada archivo lleva su **motivo** | [`BedrockDecifrator.kt`](app/src/main/java/com/packforge/app/domain/engine/BedrockDecifrator.kt) (`skipped`, `skippedPaths`) |
| **Evidencia forense persistente**: los `.bak` se copian a `packforge_forense/RP|BP/…` + `motivos.tsv` junto al `.mcaddon` (fuera del paquete, sobrevive al borrado del temporal) | [`PackForgeOrchestrator.kt:2701-2736`](app/src/main/java/com/packforge/app/domain/engine/PackForgeOrchestrator.kt#L2701-L2736) |
| Reporte: sección `MOTIVO POR ARCHIVO NO RESUELTO` agrupada, distinguiendo «no se intentó» de «sin candidato» | [`MergeReportGenerator.kt:479-489`](app/src/main/java/com/packforge/app/domain/engine/MergeReportGenerator.kt#L479-L489) |
| `LenientJson` tolera la cabecera `//bridge-file-version` (antes esos JSON válidos se neutralizaban) | [`LenientJson.kt:57`](app/src/main/java/com/packforge/app/domain/engine/LenientJson.kt#L57) |

Tests: `DecifratorRecoveryTest` **8/8**, `BedrockDecifratorTest` **13/13** (comportamiento previo intacto),
`RealPackP0AuditTest` **1/1**. Medición sobre el pack real: **163 placeholders → 0**, 0 nuevos, 0 pisando
vanilla, **0 modelos faltantes**. Suite completa: **285 tests, 0 fallos**.

### Fase 4 — implementada y verificada (conflictos reales, no ruido)

Las 17.313 entradas del reporte eran **todas** sin ruta (`• MEDIUM @ `): venían de las 5 llamadas a
`deepMerge` que **no** fijaban contexto (entity, render_controllers, animations, attachables y
player.entity). Se contaba conflicto incluso cuando el valor era **idéntico**.

| Cambio | Archivo (línea) |
|---|---|
| `deepMerge` recibe `filePath` y `jsonPath`; el conflicto ya es auditable y lleva breadcrumb | [`JsonDeepMerger.kt:89-92`](app/src/main/java/com/packforge/app/domain/engine/JsonDeepMerger.kt#L89-L92) |
| **Igualdad de valores**: mismo valor ⇒ no se reporta (mata el 94% del ruido) | [`JsonDeepMerger.kt:166-170`](app/src/main/java/com/packforge/app/domain/engine/JsonDeepMerger.kt#L166-L170) |
| Misma regla en `KEY_COLLISION_AFTER_SANITIZE` (los addons cerrados duplican claves a propósito) | [`JsonDeepMerger.kt:330-352`](app/src/main/java/com/packforge/app/domain/engine/JsonDeepMerger.kt#L330-L352) |
| Severidad calculada: `identifier` → HIGH, versión → LOW (antes `MEDIUM` literal) | [`JsonDeepMerger.kt:252`](app/src/main/java/com/packforge/app/domain/engine/JsonDeepMerger.kt#L252) |
| Los 5 puntos de llamada pasan su ruta real | [`BedrockCriticalFilesMerger.kt`](app/src/main/java/com/packforge/app/domain/engine/BedrockCriticalFilesMerger.kt) |
| **Auditor de identificadores duplicados** (red de seguridad del remapper), HIGH con ambas rutas | [`DuplicateIdentifierAuditor.kt`](app/src/main/java/com/packforge/app/domain/engine/DuplicateIdentifierAuditor.kt) |
| Reporte **agrupado** (archivo + ruta + tipo + addons) con tope 80, y contador honesto total/distintos | [`FusionReport.kt:63-110`](app/src/main/java/com/packforge/app/domain/engine/FusionReport.kt#L63-L110) |

Tests: `ConflictSignalTest` **7/7**. Suite completa: **292 tests, 0 fallos**.
Medición sobre el reporte real: `sin ruta: 17313`, `con ruta: 1066` → las 17.313 quedan cubiertas por la
ruta + la igualdad de valores (el recuento final debe medirse en una fusión real).

### Fase 5 — implementada y verificada (integridad y reporte coherentes)

El reporte acusaba al addon de ofuscar ~860 archivos **válidos** y marcaba como inválidos agregados que
Bedrock lee sin problema. Con ese ruido, los fallos reales quedaban invisibles.

| Problema | Causa real | Cambio |
|---|---|---|
| ~860 avisos falsos de `CLAVES_CIFRADAS` | `CANONICAL_KEYS` era una lista cerrada: `pools` (loot), `namespace`/`nineslice_size` (UI) y cualquier `minecraft:*` no catalogado se tomaban por claves cifradas | [`MergeReportGenerator.kt`](app/src/main/java/com/packforge/app/domain/engine/MergeReportGenerator.kt): claves añadidas **y** nueva regla de prefijos `minecraft:`, `geometry.`, `animation.`, `controller.` (`CANONICAL_KEY_PREFIXES`) |
| `item_texture.json`/`terrain_texture.json` de CoreCraft reportados como JSON inválido | Llevan `// cooking` dentro; `JSONTokener` estricto los rechaza, pero Bedrock los acepta | `lenientForTokener()` en `hasCanonicalKey`, `isStructurallyValid`, `looksLikeJsonAtAll` y en los reintentos de `validateJsonSyntax` |
| Comentario inicial interpretado como "texto plano" | `looksLikeJsonAtAll` devolvía `false` ante cualquier `/*` o `//`, así que un JSON válido con cabecera se marcaba `ARCHIVO_NO_JSON` (HIGH si la ruta era canónica) | Se recorta el comentario antes de decidir; los literales se comprueban completos (`true`/`false`/`null`), no por su primera letra |
| Contadores que no cuadraban | Los hallazgos `INFO` se contaban **dos veces** (como "relevantes" y como "informativos") | `buildIntegritySummary`: cada hallazgo cae en una sola categoría y la suma coincide con el total |
| Reporte ilegible por volcar cientos de avisos | Listado plano | `MAX_INTEGRITY_DETAIL = 60`: los `HIGH` siempre completos, el resto acotado con "… y N más" |
| 17.313 de 18.379 conflictos eran ruido | `deepMerge` registraba conflicto aunque el valor entrante fuese **idéntico**; todo era `MEDIUM` y sin ruta | Igualdad por `canonicalOf` (no se reporta lo idéntico), severidad por tipo (`IDENTIFIER_OVERWRITE`→HIGH, `VERSION_OVERWRITE`→LOW) y `jsonPath` propagado en la recursión |
| `concatArrays` deduplicaba primitivos | `"frames":[0,0,1,1]` → `[0,1,2]`: la animación se reproducía al doble de velocidad, en silencio | La unión solo se aplica a objetos/arrays; los primitivos conservan posición y repetición |
| Claves saneadas que se pisaban en silencio | `"a"` y `"a\u200B"` colapsan en `"a"` | Gana la primera y se registra `KEY_COLLISION_AFTER_SANITIZE` (solo si el valor difiere) |
| `.mcaddon` con `bp/`+`rp/`: se perdía un pack | Se registraba el directorio de extracción en vez de la raíz real del pack, así que `ui/`, `player.entity.json`… se buscaban en el nivel equivocado | `AddonExtractor.resolvePackRoot()` al poblar `bpDirs`/`rpDirs` |
| Un ZIP inválido se daba por extraído | `ZipInputStream` no lanza excepción: simplemente no hay entradas, y se devolvía la ruta igualmente | `extractAddon` devuelve `null` si `entryCount == 0`, y el orquestador falla con motivo |
| El override del jugador se descartaba | `mergePlayerEntity` solo aceptaba `minecraft:entity`; las animaciones/geometría viven en `minecraft:client_entity` | Se aceptan ambas raíces |

**Veredicto de compatibilidad.** `CompatibilitySummary` existía pero **nunca se invocaba**: no había forma de
saber si el modpack salió bien sin leer miles de líneas. Ahora se construye al final de la fusión y viaja en
`MergeResult.compatibility` → `MergeSession` → pantalla de éxito y notificación (🟢/🟡/🔴).

**Tests de la fase:** [`Fase5IntegridadTest.kt`](app/src/test/java/com/packforge/app/domain/engine/Fase5IntegridadTest.kt)
(12 casos: prefijos canónicos, `pools`/`namespace`/`nineslice_size`, comentarios tolerados, JSON roto que
sigue detectándose, contadores y tope de detalle) y
[`CompatibilitySummaryTest.kt`](app/src/test/java/com/packforge/app/domain/engine/CompatibilitySummaryTest.kt).

**Fase 5 cerrada.** Con esto quedan implementadas las cinco fases (0 a 5).

---

## 7.b Segunda pasada de Fase 5 — pérdida de datos geométricos (la causa más grave)

Al medir el `.mcaddon` real contra los addons de origen apareció una pérdida que el reporte
**no** sabía explicar: 59 archivos de `models/entity` de CoreCraft no llegaban al paquete, y con
ellos **25 identificadores de geometría reales** (`geometry.cc.pharaoh`, `geometry.cc.soldier`,
`geometry.cc.meerkat`, `geometry.cc.deer`, `geometry.cc.villager_baby`…). El reporte los listaba
como *"no existe en models/"* — cuando en realidad **la propia app los acababa de borrar**.

### La cadena, medida paso a paso

Se instrumentó el pipeline con trazas (`PF-TRACE | modelos/entity …`) y se aisló cada eslabón:

| Etapa | Modelos de entidad | Veredicto |
|---|---|---|
| Extracción del `.mcaddon` | 120 / 120 | ✅ correcta |
| `mergeGeometryFiles` (aislado, 4 addons) | 135 / 135 | ✅ correcta |
| Tras Fase 3 (fusión de críticos) | 154 | ✅ |
| Tras decifrador | 154 | ✅ |
| Tras `healer.repair` | 155 | ✅ |
| **Tras el validador** | **83** | ❌ **−72 archivos** |
| Antes de empaquetar | 83 | ❌ el daño ya está hecho |

### Causa raíz: autocopia destructiva

En `PackForgeValidator`, al validar cada entidad:

```kotlin
val geoFile = identifierIndex.resolve(geometryId)          // ya vive en models/entity
val destFile = File(modelsEntityDir, "${geoFile.name}")    // ¡es el MISMO archivo!
geoFile.copyTo(destFile, overwrite = true)                 // se borra a sí mismo
```

`File.copyTo(overwrite = true)` de Kotlin **borra el destino antes de copiar**. Con origen == destino,
el archivo se elimina a sí mismo y lanza `NoSuchFileException`, que el `catch` del bucle **se tragaba**.
Resultado: cada entidad validada aniquilaba su propia geometría, en silencio.

El mismo patrón existía en `FileUtils.fastCopy`: abría `FileOutputStream(dest)` (que **trunca**) con
`src == dest`, dejando el JSON **vacío** en vez de borrado.

### Correcciones

| Archivo | Cambio |
|---|---|
| [`PackForgeValidator.kt`](app/src/main/java/com/packforge/app/domain/engine/PackForgeValidator.kt) | Si `destFile` y `geoFile` son el mismo archivo, no se copia: solo se marca como presente |
| [`FileUtils.kt`](app/src/main/java/com/packforge/app/util/FileUtils.kt) | `fastCopy` sale sin tocar nada cuando `src.canonicalFile == dest.canonicalFile` (protege a todos los llamadores) |
| [`PackForgeValidator.kt`](app/src/main/java/com/packforge/app/domain/engine/PackForgeValidator.kt) | `searchAndCopyFile` aplica la misma guarda |

### Otros bugs reales de la misma pasada

| Bug | Impacto | Cambio |
|---|---|---|
| `LenientJson` solo recortaba comentarios **al principio** | Un comentario interno (`// plane only rolls in the air`, línea 33 de `entity/2hwvcaut.json`) rompía el parseo → el decifrador lo daba por "roto" → lo renombraba a `.bak` → **excluido del `.mcaddon`**. Se perdieron 10 archivos reales, entre ellos `cc:siren`, `cc:hermit` y un `render_controllers` con **23 controladores** (`cc.common`, `cc.ignore_lighting`), que el reporte luego acusaba de faltar | `LenientJson.ws()` salta comentarios en **cualquier** posición, como hace Bedrock |
| `mergeGeometryFiles` ignoraba el formato **legacy** (`{"geometry.x":{…}}` como clave raíz) | 2 archivos de geometría nunca se escribían | Acepta moderno **y** legacy; `mergeGeoJson` fusiona ambos |
| `identifierIndex = null` (con el comentario "ya no se usa") | Construía un índice VACÍO → **438 "modelos faltantes" falsos** | Se construye el índice real con los directorios fusionados |
| `customMaterials` no leía los `.material` | Falsos "material no existe" | Lee `materials/*.json` **y** `materials/*.material` |
| `resolvePackRoot` devolvía el primer `manifest.json` | En `core-craft` devolvía el **Behavior Pack** en vez del Resource Pack | Selecciona por `modules[].type` (`data` / `resources`) |

### Tests de esta pasada

- [`Fase5AutocopiaDestructivaTest.kt`](app/src/test/java/com/packforge/app/domain/engine/Fase5AutocopiaDestructivaTest.kt)
  (5 casos): la autocopia no borra ni vacía el archivo; sigue copiando cuando origen ≠ destino; escenario
  completo del validador sobre un pack mínimo.
- [`Fase5ComentariosInternosTest.kt`](app/src/test/java/com/packforge/app/domain/engine/Fase5ComentariosInternosTest.kt)
  (6 casos): comentarios de línea y bloque internos, cabecera `//bridge-file-version`, JSON roto que sigue
  detectándose, y barras dentro de strings (`"textures/entity/x"`, `"https://…"`) que no deben confundirse
  con comentarios.
- [`Fase5LegacyGeometryTest.kt`](app/src/test/java/com/packforge/app/domain/engine/Fase5LegacyGeometryTest.kt)
  (8 casos), [`DiagnosticoPerdidaGeometriasTest.kt`](app/src/test/java/com/packforge/app/domain/engine/DiagnosticoPerdidaGeometriasTest.kt),
  [`DiagnosticoMergeGeometryMultiAddonTest.kt`](app/src/test/java/com/packforge/app/domain/engine/DiagnosticoMergeGeometryMultiAddonTest.kt),
  [`DiagnosticoPerdidaModelsEntityTest.kt`](app/src/test/java/com/packforge/app/domain/engine/DiagnosticoPerdidaModelsEntityTest.kt).

**Lección de diseño.** El reporte acusaba al pack de fallos que el propio pipeline provocaba. Un validador
que **escribe** en el pack que está validando puede destruir aquello que mide; toda escritura debe comprobar
primero si el destino ya es el origen.

---

## 7.c Tercera pasada — las 5 "entidades incompletas" que quedaban no eran defectos

Con los modelos ya en 0 y `no existe en el paquete` en 0, el reporte seguía marcando **5 entidades
incompletas**. Todas del mismo tipo:

```
cc:mining_market_villager    → sin definición en BP
cc:fishing_market_villager   → sin definición en BP
cc:adventure_market_villager → sin definición en BP
cc:cooking_market_villager   → sin definición en BP
cc:farming_market_villager   → sin definición en BP
```

La hipótesis natural era "son re-skins: el juego les da el comportamiento, hay que inyectarles la
definición del villager vanilla". Se comprobó y **es falsa**.

### La evidencia

1. El BP de CoreCraft define **279** entidades; el RP declara **271** `client_entity`. **271 de 271** tienen
   su `minecraft:entity`. Estas 5 son la única excepción, y no son re-skins de villager: son *villagers*
   con texturas de las 5 profesiones (`geometry.villager.v1.8`, material `villager`, escala `0.9375`).
2. Escaneo de **las 9.405 entradas** del `.mcaddon` (JSON, `.js`, `.mcfunction`, `.mcstructure` y todo
   binario), no sólo `.json`: cada uno de los 5 identificadores aparece en **un único archivo de todo el
   addon**, el suyo. El addon *sí* tiene `spawn_rules/`, `scripts/` y `structures/` — y **ninguno** los
   menciona. No hay spawn egg, ni `/summon`, ni regla de aparición, ni estructura que los coloque.
3. Conclusión: son **contenido muerto del origen**. No aparecen nunca en el juego, tampoco con el
   `.mcaddon` original instalado tal cual. La fusión no rompió nada y no hay nada que reparar.

### Por qué NO se implementó el "catálogo de entidades vanilla"

El catálogo era la respuesta obvia y es la equivocada:

- **Caduca sola.** Cada versión añade mobs (armadillo, breeze, bogged, camel, sniffer, creaking, el happy
  ghast…). Habría que actualizarlo para siempre, y entre actualización y actualización volverían los
  falsos positivos.
- **Ya falló antes en este mismo archivo.** `VanillaAssets` conserva el epitafio de las listas a mano:
  *"Las listas de prefijos de arriba son heurísticas escritas a mano y SIEMPRE se quedan cortas… eso
  produjo ~1.000 errores falsos"*. Añadir una sexta lista sería repetir el error con otro nombre.
- **Y era innecesario.** La pregunta "¿este mob existe de verdad?" no se responde mirando el juego, sino
  el pack: **¿alguien lo invoca?**.

### La regla que se implementó

La clasificación se **deriva** del propio pack, sin tablas:

| Definición en BP | Alguien la nombra | Veredicto |
|---|---|---|
| sí | — | correcta, no se reporta |
| no | **sí** (spawn rule, script, estructura, otra entidad) | 🔴 **defecto**: el mob no aparecerá |
| no | **no** | ⚪ **huérfana del origen**: aviso informativo, fuera del recuento |

[`EntityOrphanClassifier.kt`](app/src/main/java/com/packforge/app/domain/engine/EntityOrphanClassifier.kt)
responde "¿alguien la nombra?" leyendo el BP en **streaming por bloques con solape** (`needle.size - 1`),
así que el identificador se detecta aunque caiga entre dos lecturas, y sin cargar en memoria un
`.mcstructure` grande. Se ignoran imágenes y sonidos (ahí un identificador sólo "aparece" por casualidad
entre bytes comprimidos) y las carpetas internas del pipeline (`.shadow`, `_packforge`).

Es **estable frente a cualquier versión del juego y cualquier addon**: no enumera nada del juego, sólo
mira lo que el pack declara y lo que el pack usa.

### Bug de rendimiento encontrado al medirlo

La primera versión escaneaba el BP **una vez por huérfana**: 5 × 6.400 aperturas de archivo, y en Windows el antivirus inspecciona **cada apertura** (~20 ms), no cada byte → 11 minutos extra.
Una segunda versión intentó "optimizar" leyendo cada archivo en memoria y buscando varias agujas a la vez: eso generaba decenas de GB de basura para el GC y la fusión se estancó a 0,3 MB/s con la CPU parada. La versión final usa **un solo búfer reutilizado** para todo el árbol, en streaming por bloques con solape, y una apertura por archivo: recupera el tiempo de fusión.

### Medición final (`.research/fase5_out11`)

La fusión completa: 26.5 min (incluye 11m24s de compilado de tests — el bottleneck es la cadena de
compilación de Kotlin tras 5 archivos cambiados, **no** mi escáner). La fusión real con el escáner
incluido tarda lo mismo que la de `fase5_out10` sin escáner: el escáner cuesta minutos cero, porque el
BP se recorre una vez, con un solo búfer reutilizado y una apertura por archivo.

| Métrica | Estado inicial | Final |
|---|---|---|
| Rutas de origen perdidas | 59 | **0** |
| Modelos faltantes | 438 | **0** |
| Texturas faltantes | — | **0** |
| Entidades incompletas | 271 | **0** |
| `no existe en el paquete` | 3 → 266 | **0** |
| Huérfanas del origen (informativas) | — | **5** |
| Referencias rotas reales (ausentes en todo el pack) | 41 | **41** |

**Tests:** [`Fase5HuerfanasOrigenTest.kt`](app/src/test/java/com/packforge/app/domain/engine/Fase5HuerfanasOrigenTest.kt)
(9 casos: huérfana, spawn rule que la cita, script que la invoca, entidad definida, PNG con los bytes
casuales, carpeta `.shadow`, identificador partido entre dos bloques de lectura, lote de varias en una
pasada, BP ausente/vacío).

**Las 41 referencias rotas que quedan son reales y no se pueden arreglar fusionando**: apuntan a
`animation.cc.empty`, `animation.cc.sentinel.beam`, `controller.render.cc.*`… que no existen en **ninguno**
de los 4 paquetes. El addon de origen está incompleto; inventarlos sí cambiaría el comportamiento. El
reporte es correcto al señalarlos.

**Lección de diseño.** Antes de añadir una tabla de datos al motor, preguntar si la pregunta se puede
responder con lo que el pack ya dice. Un catálogo del juego caduca en cada versión; un derivado del pack
no caduca nunca, y además distingue "esto lo rompí yo" de "esto ya venía roto".

---

## 7.d Cuarta pasada — la app real midió más que mis tests

Con la app ya recompilada, el usuario generó el `.mcaddon` de verdad (10.778 JSON, 251 líneas de reporte).
Los arreglos anteriores están confirmados —`Modelos faltantes: 0`, `.bak` fuera, las 5 huérfanas como
aviso— pero la app destapó **4 falsos positivos nuevos** que solo aparecen con el pack completo.

### (a) La lista de materiales del motor se quedaba corta (68 de 135)

```
El material 'iron_golem' se referencia en 'entity/echo_golem2.entity.json' pero no existe
El material 'snowball'   se referencia en 'entity/vyj0x1wd.json' pero no existe
El material 'arrow'      se referencia en 'entity/rtg8163n.json' pero no existe
```

Los tres **sí son materiales de fábrica**. Se verificó contra el paquete oficial de Mojang
(`bedrock-samples-v1.26.50.4-full`): aparecen en las `client_entity` de las entidades vanilla
`entity/iron_golem.json`, `entity/snowball.json` y `entity/arrow.json`. **El resource pack vanilla no
tiene carpeta `materials/`**, así que cualquier material citado ahí es del motor y lo resuelve el juego.

| | Antes | Ahora |
|---|---|---|
| Materiales del motor catalogados | 67 (a mano) | **135 (extraídos de los 180 archivos de entidades de Mojang)** |
| Faltaban | 68 | — |

Es el **mismo error** que este archivo ya documenta dos veces para geometrías y animaciones: una lista
escrita a mano siempre se queda corta. Ahora se deriva de la fuente oficial y se puede re-extraer entera
en cualquier actualización.

### (b) La clave de un material no es su nombre referenciable

En `materials/entity.material` cada material se declara con herencia:

```json
{ "materials": {
    "glow:entity_alphablend":          { "+states": ["Blending"] },
    "cc_entity_enchant:entity_static": { "+defines": ["USE_UV_ANIM"] }
} }
```

…pero el `client_entity` lo referencia **solo por el nombre** (`"default": "glow"`). El validador
indexaba la clave completa y comparaba contra `glow`; como no coincidía, reportaba "material custom no
existe" aunque estuviera definido dos líneas más arriba. Salían decenas de `glow`, `cc_outline`,
`cc_entity_enchant` falsos. Ahora se indexan **la clave completa y su nombre** (antes de `:`).

### (c) Un punto en el nombre del archivo NO es una extensión

```
sin textura en ningún addon: textures/cc/custom_villager/levels/base_lvl.1
```

…cuando en el ZIP está `RP_PackForge/textures/cc/custom_villager/levels/base_lvl.1.png`, en **30
entidades**. La causa era doble, y las dos mitades asumían que lo que sigue al último punto es formato:

1. `collectTextureInventory` guardaba `…/base_lvl` (el `.1` se perdía).
2. `checkTextureReference` buscaba con el mismo recorte, así que no podía acertar ni con un inventario
   correcto.

Bedrock referencia las texturas **sin extensión** y el archivo la lleva. La única forma correcta de
emparejarlas es quitar/añadir las extensiones de imagen **reales** por sufijo, nunca adivinar por el
último punto. Afecta a cualquier nombre con puntos: `base_lvl.1..5`, `outfit.0`, `sword.icon`…

### (d) `dest.exists()` no ve el archivo con su extensión

En `EntityDependencyResolver`, una referencia sin extensión (`…/base_lvl.1`) se comprobaba con
`dest.exists()` sobre la ruta literal: como el archivo real es `…/base_lvl.1.png`, la comprobación fallaba
y la textura entraba en el bucle de "recuperar" —recopiándose encima o contándose como ausente— pese a
estar ya en el pack. Al copiar, además, se conserva la **extensión real del origen**.

### (e) LA CAUSA RAÍZ de los 41 materiales rotos: el archivo nunca se leía

Tras arreglar la herencia (b) y ampliar la lista del motor (a), el reporte real **seguía mostrando 41
materiales rotos**, y entre ellos `glow`, `cc_outline`, `cc_entity_enchant` y `outline_base_enchant`,
que **figuran literalmente en `RP_PackForge/materials/entity.material`**. Eso descartaba las dos
hipótesis anteriores como explicación única.

El motivo no era el nombre ni la herencia: **el archivo nunca se parseaba**. `readJson` usaba
`JSONObject(texto)`, que es JSON **estricto**, y el `entity.material` real de CoreCraft trae comentarios:

```json
"cc_test:charged_creeper": {
    // "depthFunc": "Always",
    // ^ Uncomment if you want the outline to be
    //   rendered behind the block.
}
```

Se comprobó empíricamente en el entorno de tests (`DiagnosticoParseoComentariosTest`):

| Contenido | `JSONObject` (estricto) | `LenientJson` |
|---|---|---|
| JSON limpio (control) | OK | OK |
| Comentario `//` de línea | **FALLA JSONException** | OK |
| Comentario `/* */` de bloque | **FALLA JSONException** | OK |
| Coma final | OK | OK |

Al fallar el parseo `readJson` devolvía `null` y **ninguno** de los 10 materiales del archivo se
indexaba: todos se reportaban ausentes de golpe. Es el mismo fallo ya documentado para
`item_texture.json` (que abre con `// cooking`), pero aquí golpeaba a los materiales.

**Por qué importa más allá de este pack:** un `null` silencioso al leer un archivo hace que **todo su
contenido** se reporte como perdido, y el mensaje que ve el usuario culpa al contenido en vez de al
lector. Arreglado usando `LenientJson` (que ya existía en el proyecto y acepta lo que Bedrock acepta).

### (f) El "41" era un tope de reporte disfrazado de cifra

`MAX_BROKEN_REPORTED = 40` y, al alcanzarlo, se añadía un sentinel `BrokenReference("…")`. El resumen
contaba `broken.size` → **40 reales + 1 sentinel = "ROTAS: 41"**, idéntico en todas las corridas. El
usuario leía ese 41 como el total del pack cuando en realidad podía haber muchos más sin listar.

Ahora el resumen informa `brokenCount` (sin el sentinel), cuántos **identificadores distintos** hay, y
avisa explícitamente "hay MÁS sin listar" cuando la lista se truncó.

### (g) `textures/models/armor/` es vanilla y faltaba del catálogo

`textures/models/armor/copper_1` y `copper_2` se reportaban ausentes, pero **están en el pack oficial**
(`bedrock-samples…/resource_pack/textures/models/armor/copper_1.png`). En el resource pack vanilla
`textures/models/` contiene **solo `armor/`** (78 archivos), así que marcar la carpeta es seguro. Añadida.

### (h) La conclusión equivocada: creí que eran contenido roto, y no lo eran

`entity_custom` (en `entity/9xlrdezq.json`, `cc:falling_block`) y `opaque_block` (en
`entity/gtbf5lc1.json`, `cc:end_vault`) no aparecían en los 22.254 archivos de Mojang ni en su
documentación oficial, así que concluí que eran **referencias rotas reales de CoreCraft**.

**Era incorrecto.** El paquete de samples de Mojang **no incluye la carpeta `materials/`**, así que su
ausencia ahí no probaba que no existieran. La ausencia de evidencia solo es concluyente si la fuente es
completa, y esa no lo era.

### (i) La lista definitiva: los 413 materiales del juego instalado

La fuente completa estaba **en el propio equipo**: el juego instalado trae los 14 archivos `.material`
del motor en `…/resource_packs/vanilla/materials`. De ahí salen **413 materiales**, incluidos los que
ninguna entidad vanilla usa y que por tanto son invisibles en los samples:

```json
"entity_custom:entity"
"opaque_block:entity_static"
"entity_alphatest_change_color_glint:entity_change_color"
```

Los tres existen, y la web que consultó el usuario también acertó al decirlo.

| Fuente | Materiales | ¿Completa? |
|---|---|---|
| Heurística escrita a mano | 67 | No — faltaban 346 |
| Entidades de `bedrock-samples` | 135 | No — son solo los *usados* |
| `attachables` de samples | +6 | No — sigue siendo "usados" |
| **`.material` del juego instalado** | **413** | **Sí — es la tabla del motor** |

### (j) Cuatro de mis propios tests contenían materiales inventados

Al sustituir la lista por la real, **dos tests empezaron a fallar** — y la causa no estaba en el código
sino en los tests: contenían **15 nombres que nunca existieron**. Los había escrito *por analogía* con
el formato de Mojang, no extraídos:

```
outline          outline_base        outline_base_glow    outline_combined
entity_translucent   entity_cutout    entity_cutout_no_cull
item_glint       item_sprite         water_still
beacon           enchanting_table    skull                terrain_cutout
selection_wireframe
```

Ninguno está en los 413 del juego. **Esos 15 nombres habrían hecho que la app declarara "vanilla" a
materiales inexistentes**, enmascarando referencias rotas reales — el riesgo exacto que justifica no
escribir catálogos a mano.

Y en la dirección contraria: puse `clouds` en la lista de inexistentes cuando **sí existe** (está en
`fancy.material`). Corregido. Queda un test (`los nombres plausibles pero inexistentes NO se dan por
vanilla`) como defensa contra volver a inventarlos.

**Lección de método (la más importante de la fase).** El error se repitió **cuatro veces** con la misma
forma: completar un catálogo con lo que *parece* que debería estar. Primero geometrías, luego
animaciones, luego materiales — y ahora, dentro de los propios tests. La regla que queda:

> Un catálogo de vanilla se **extrae** de una fuente completa, o **no se escribe**.
> Y si una fuente no incluye la tabla entera, su silencio no prueba nada.

### Tests de esta pasada

- [`Fase5MaterialesMotorTest.kt`](app/src/test/java/com/packforge/app/domain/engine/Fase5MaterialesMotorTest.kt)
  (6 casos): los 3 materiales exactos del reporte, familias del motor, un material con namespace que NO
  debe darse por vanilla (si no, uno propio roto pasaría sin aviso) y uno inventado que debe detectarse.
- [`Fase5MaterialHeredadoTest.kt`](app/src/test/java/com/packforge/app/domain/engine/Fase5MaterialHeredadoTest.kt)
  (4 casos): `nombre:padre` → referenciable por `nombre`, los 3 del pack real, un material ausente que
  sigue reportándose, y `materials/*.json` además de `*.material`.
- [`Fase5MaterialConComentariosTest.kt`](app/src/test/java/com/packforge/app/domain/engine/Fase5MaterialConComentariosTest.kt)
  (4 casos): comentarios `//`, `/* */` y comas finales; y que **un solo comentario no invalide las 3
  claves** del archivo. Es la causa raíz (e).
- [`Fase5TexturaConPuntoEnNombreTest.kt`](app/src/test/java/com/packforge/app/domain/engine/Fase5TexturaConPuntoEnNombreTest.kt)
  (3 casos): `base_lvl.1`, los 5 niveles reales de CoreCraft, y una textura genuinamente ausente.
- [`DiagnosticoParseoComentariosTest.kt`](app/src/test/java/com/packforge/app/domain/engine/DiagnosticoParseoComentariosTest.kt):
  diagnóstico que **imprime** la comparación estricto/laxo en vez de asumirla.

**Lección de método.** Los primeros arreglos se validaron con tests que yo mismo escribí, y por eso no
vieron estos fallos: mis tests usaban nombres "bien formados" (`steve.png`, `cc:outline`) y materiales
sin herencia. **Solo el `.mcaddon` real de 10.778 archivos expone los casos que uno no imagina** —
comentarios dentro de un `.material`, puntos en el nombre, listas del motor incompletas. Y al diagnosticar
hay que **probar la premisa**, no asumirla: la causa raíz (e) solo se aceptó después de que un test
imprimiera que `JSONObject` falla con `//`.

**Lección de reporte.** Una cifra truncada que se presenta como total es peor que un error: manda al
usuario a investigar 41 roturas cuando puede haber cientos. Todo tope debe ser **visible**.

### (k) REGRESIÓN introducida y corregida: 64 "render controller ausente" que no estaban antes

Al comparar el reporte de `out12+` contra el original del usuario aparecieron **64 avisos nuevos** de
`render controller 'controller.render.cc.common' ausente` / `ignore_lighting` que **el reporte original
NO tenía**. Los 95 render controllers de CoreCraft están definidos — verifiqué que el que los define es
`render_controllers/tznvrmdz.json` (11.565 bytes), el **único con comentarios** del pack.

Causa: la validación del ZIP final leía con `JSONObject` estricto; ese archivo fallaba, `runCatching`
devolvía `null`, y sus 64 RCs no entraban en el índice `animRcIds`. Es el mismo fallo de la causa raíz
(e) en un **segundo módulo** (`EntityDependencyResolver`): corregido en sus 4 lecturas con el helper
`readPackJson` (LenientJson). Test: `Fase5RenderControllerConComentariosTest` (ZIP real con RC con
comentarios, definido y referenciado → 0 avisos; y un RC genuinamente ausente → se detecta).

**Lección.** La regresión la introduje yo al endurecer esa validación: un lector estricto no solo reporta
de más — cuando se añade tarde, convierte en "nuevos fallos" cosas que siempre estuvieron bien.

### (l) `material.outline` en render controllers: accesor Molang, no material

Los 8 identificadores restantes de `out14` incluían `material 'material.outline'` y
`material 'material.glow'` en `render_controllers/7911eazu.json`. No son materiales: es el **accesor
Molang** al mapa `materials` de la propia entidad — el mismo alias que `Material.default` (que ya se
filtraba por mayúscula), escrito en minúscula.

Validado contra los samples v1.26.70 de Mojang: sus 174 render controllers vanilla usan **ambas
formas** — `Material.` (238 veces) y `material.` minúscula (11 veces), p. ej.
`shield.render_controllers.json`: `"materials": [{ "*": "variable.is_patterned ? (… material.pattern …)" }]`.
Corregido añadiendo `material.` a los prefijos Molang de `BedrockRefs` (con nota explícita de que
`geometry.` NO se añade: `geometry.humanoid` sí es un identificador real).

### Tests de esta pasada (continuación)

- [`Fase5RenderControllerConComentariosTest.kt`](app/src/test/java/com/packforge/app/domain/engine/Fase5RenderControllerConComentariosTest.kt)
  (2 casos): el caso de la regresión con un ZIP real, y el RC ausente que debe seguir detectándose.
- `Fase5MaterialHeredadoTest` ganó el caso del accesor `material.<slot>` (5 tests en total).

### (m) Tercera ubicación del mismo bug: el índice de candidatos (`GeometryAnimRCIndex`)

La fusión `out15` aún mostraba `Referencia rota tras fusión: render controller
'controller.render.cc.ignore_lighting' requerido por …` (agrupado ×30, preexistente desde el reporte
original). El índice de candidatos que alimenta la recuperación (`GeometryAnimRCIndex.indexFile`)
también parseaba con `JSONObject` estricto y **tragaba la excepción en silencio**: `tznvrmdz.json` (con
comentarios) nunca entraba en `renderControllerById`, así que sus 64 RCs no solo no se indexaban —
**tampoco se podían recuperar**. Corregido en sus dos lecturas y en `recoverById` (tercer módulo del
mismo fallo).

### (n) Medición final (`out16`)

| Métrica | Reporte del usuario | out12 | out13 | out14 | out15 | **out16** |
|---|---|---|---|---|---|---|
| Referencias ROTAS (totales) | 41 | 41 | 40 | 40 | 3 | **3** |
| Identificadores rotos distintos | — | — | 9 | 8 | 2 | **2** |
| Referencias irrecuperables | 717 | 70 | 70 | 70 | 81 | **6** |
| Conflictos HIGH | — | — | — | 62 | 35 | **11** |
| Avisos RC ausente (regresión) | 0 | 64 | 64 | 64 | 0 | **0** |
| Texturas faltantes / Modelos faltantes | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | **0/0** |
| `models/entity` en la salida | 154 | — | — | — | — | **157** |

Lo que queda, verificado contra los addons de origen, es **defecto genuino de CoreCraft**, no de la
fusión: `animation.cc.empty` y `animation.cc.sentinel.beam` se referencian pero **nunca se definen** en
el addon original (0 definiciones en sus 4 packs), y 4-6 texturas de partículas
(`textures/cc/hc/particles/*`, `textures/cc/misc/particles/ozon`) no existen en ningún addon. Con el
addon original a secas el juego tendría exactamente los mismos huecos.

---

## 9. RENDIMIENTO — por qué la fusión tarda ~20 minutos

La pregunta del usuario era legítima: **4 addons, 20 minutos**. Esto es lo medido, no lo supuesto.

### 9.1 El instrumento estaba roto (y por eso nadie lo había visto)

El orquestador **ya medía fases** (`markPhase`) y las pasaba al generador de reporte, pero la sección no aparecía nunca en el reporte. Al activarla se descubrieron **dos defectos de medición**:

1. **`markPhase` guarda tiempo ACUMULADO desde el inicio**, no la duración de la etapa. Impreso tal cual, los porcentajes sumaban >100% y ninguna cifra decía dónde se iba el tiempo.
2. **Cada marca se registra al ENTRAR en su etapa**, así que el tiempo transcurrido hasta ella pertenece a la etapa **anterior**. Etiquetar el tramo con la marca que lo cierra producía `Extracción de addons ... 0,08s` — imposible — mientras la extracción real (130 s) aparecía bajo "Clasificación".

Corregido en el reporte: se imprime la **duración** de cada tramo y se etiqueta con la etapa que lo **abre**. Es un recordatorio de que un instrumento sin verificar puede mentir con más confianza que no tener ninguno.

### 9.2 Desglose real (fusión de Better MC, 1060 s)

Se añadieron marcas de duración explícitas (`PF-PERF`) en los puntos donde la atribución anterior era ambigua:

| Tramo | Tiempo | % |
|---|---|---|
| **Clasificación + separación BP/RP** | **238 s** | **22%** |
| Decifrador closed-source | 148 s | 14% |
| Extracción de los 4 addons | 130 s | 12% |
| Resolución de dependencias de entidades | 93 s | 9% |
| Fase 1B (reescritura de recursos) | 90 s | 8% |
| Renamespacer (escaneo de identificadores) | 85 s | 8% |
| Validación de referencias | 52 s | 5% |
| Healer repair | 51 s | 5% |
| Generación de reporte + cierre | 92 s | 9% |
| Empaquetado .mcaddon | 44 s | 4% |
| Copia Fase 1 (a directorios fusionados) | 38 s | 4% |
| Detección de huecos / críticos / manifiestos / scripts | ~90 s | 9% |

### 9.3 La causa física: el coste por APERTURA de archivo

Medido con un banco de pruebas propio ([`PerfRemapperEscaneoParaleloTest`](app/src/test/java/com/packforge/app/domain/engine/PerfRemapperEscaneoParaleloTest.kt)):

| Situación | Coste por archivo |
|---|---|
| Archivo en caché (lectura en caliente) | **0,5 ms** |
| Archivo recién escrito (primera lectura) | **~11,6 ms** |

**~20x más caro abrir un archivo recién creado.** El antivirus de Windows escanea el archivo la primera vez que se accede a él, y cobra por **apertura**, no por byte. La fusión crea ~20.000 archivos y luego los recorre en varias pasadas, así que ese peaje se paga una vez por archivo y domina el total.

**Y la máquina tiene solo 2 núcleos.** Eso pone un techo duro: paralelizar puede dar como máximo ~2x, y en la práctica ~1,2x (medido en el escaneo del renamespacer).

### 9.4 Qué se optimizó

- **Escaneo de identificadores en paralelo** ([`IdentifierRemapper`](app/src/main/java/com/packforge/app/domain/engine/IdentifierRemapper.kt)):
  el bucle leía el texto completo de cada JSON del BP en serie. Ahora se reparte entre hilos, y el resultado se fusiona **en el orden original del índice** para que el "última declaración gana" siga siendo determinista (si cada hilo escribiera al mapa, el ganador dependería de qué hilo terminase antes).
- **Copia masiva en paralelo** ([`FileUtils.copyFilesParallel`](app/src/main/java/com/packforge/app/util/FileUtils.kt)):
  la separación BP/RP copiaba archivo por archivo en serie; son copias independientes. Se crean primero TODAS las carpetas destino en secuencia (evita la carrera de `mkdirs` concurrente sobre un padre compartido y no repite la llamada por archivo) y después se copia el contenido en paralelo.

**Resultado honesto:** el total pasó de 1368 s a **1060 s** (el mejor medido), pero entre corridas hay una varianza de ±12% (out15: 1167 s, out16: 1105 s, out17: 1368 s, out18: 1060 s). La ganancia real de estas dos optimizaciones está **dentro del ruido**; lo valioso de esta pasada es el **instrumento** y el **diagnóstico**, no el ahorro.

### 9.5 Dónde está el ahorro de verdad (siguiente paso)

Con 2 núcleos y un peaje de ~11,6 ms por apertura, **el tiempo no se gana paralelizando: se gana eliminando pasadas y copias redundantes.**

1. ✅ **HECHO — Eliminada la doble copia de la separación BP/RP.** Ver 9.6.
2. **Decifrador (148 s).** Escanea todos los JSON buscando contenido ofuscado. En un pack que ya se sabe limpio (`✅ No se detectó contenido closed-source`) ese escaneo completo podría limitarse o hacerse sobre el índice ya construido.
3. **Reutilizar lecturas entre validadores.** Healer, `MergeReferenceValidator`, `PackForgeValidator` y `EntityDependencyResolver` recorren y parsean el mismo conjunto de JSON por separado. Compartir el texto ya leído (o el JSON ya parseado) ahorraría varias pasadas completas.
4. **Bug algorítmico real detectado:** en el renamespacer, cada colisión dispara `rewriteIdentifiersInDir`, que recorre **todos** los archivos del addon. Con C colisiones y F archivos el coste es **O(C × F)**: en el banco de pruebas, 570 colisiones × 600 archivos = 342.000 lecturas y 488 s. Reagrupar la reescritura en **una pasada por directorio** (aplicando todos los renombres de golpe; `JsonValueRewriter.replaceValues` ya acepta un mapa) lo reduce a O(F). En Better MC no hay colisiones (0), así que no afecta a este pack — pero castiga a cualquier modpack con ids repetidos.

---

### 9.6 Punto 1 ejecutado: separar un addon `BOTH` sin copiarlo

**Qué se hizo.** Un `.mcaddon` que trae su Behavior Pack y su Resource Pack juntos se **copiaba** a `separated_bp_<estampa>` / `separated_rp_<estampa>`, y después la Fase 1 **volvía a copiar** esos mismos archivos a los directorios fusionados. La copia no aportaba nada:

- `bpSubfolder` / `rpSubfolder` **ya son raíces válidas de pack**: el clasificador las obtiene del `parentFile` del `manifest.json`, así que el manifest está en su raíz.
- La copia preservaba la estructura relativa tal cual → `separated_bp_<n>` era estructuralmente idéntico al origen.

Lo **único** que la estampa aportaba era agrupar BP y RP como el MISMO addon. Sin esa agrupación el renamespacer ve al BP y al RP como dos addons distintos y reaparece el fallo histórico que el propio código documenta: el addon "colisiona consigo mismo", se renombra el id en el BP pero el RP conserva el original, y el vínculo entidad↔textura se rompe (mobs invisibles o sin modelo).

**Cómo.** Nuevo [`AddonOwnerRegistry`](app/src/main/java/com/packforge/app/domain/engine/AddonOwnerRegistry.kt): el orquestador declara qué carpetas forman un mismo addon y el renamespacer lo consulta. Se conserva la lectura por nombre de `separated_*` como respaldo, así que ninguna ruta antigua cambia de comportamiento. Las dos funciones de separación quedan marcadas como **obsoletas** (nadie las llama) para que no se reactiven por error.

**Resultado medido** (fusión real de Better MC):

| | Antes (out18) | Después (out19) |
|---|---|---|
| **Clasificación + separación BP/RP** | **238,4 s** | **25,6 s** |
| Tiempo total del pipeline | 1060,5 s | **753,7 s** |
| Entradas del `.mcaddon` | 19.417 | 19.418 |
| `models/entity` en la salida | 157 | **157** |
| Referencias ROTAS | 3 | **3** |
| Referencias irrecuperables | 6 | **6** |
| Texturas / Modelos faltantes | 0 / 0 | **0 / 0** |

**−285 s (−27%)** en el total y **−213 s** solo en el tramo que se tocó, con **calidad idéntica**.

**Nota de honestidad sobre la comparación.** Entre `out18` y `out19` el repositorio recibió además cambios ajenos a esta optimización (lote del 10/11): el marcador `PackForge.ID` pasó de la raíz del `.mcaddon` a dentro de cada pack —arreglo real del error *"No es un archivo ZIP válido"* en PC, porque el lector estricto de Windows rechaza archivos sueltos en la raíz— y `mergeLangFiles` ganó limpieza de BOM, aliasing bidireccional de claves y fallback de idiomas. Se verificó que son **el único** origen de las diferencias de contenido (`+721` claves `.lang`, marcador movido) y que **no** vienen de este cambio. El tramo medido (`Clasificación + separación`) es el que se modificó, así que su caída de 238 s a 26 s sí es atribuible.

**Cómo se protegió el vínculo BP↔RP.** El test [`RendimientoSeparacionSinCopiaTest`](app/src/test/java/com/packforge/app/domain/engine/RendimientoSeparacionSinCopiaTest.kt) monta dos addons que declaran el MISMO id, cada uno con su BP y su RP, y comprueba que al renombrar el perdedor **se reescriben sus dos mitades** (BP *y* RP), que el ganador queda intacto, y que dos addons distintos siguen colisionando entre sí. Es exactamente el fallo que la copia evitaba, ahora fijado por test en vez de por una carpeta duplicada.

---

### 9.7 El tramo crítico era una caja negra (cuelgue reportado en móvil)

Con 2 addons pequeños (`Origins-BE` 2,3 MB + `OTG Backpack` 1,6 MB) una fusión se quedó **25 minutos en el 76 %**, «Fusionando archivos críticos (paralelo)».

**No se reproduce en PC**: con esos mismos addons la fusión completa tarda **77 s** y el bloque crítico **~5 s** (la fusión más lenta, `mergeGeometryFiles`, 5,1 s; las otras 20 por debajo de 2 s). Así que no es un cuelgue del código de fusión.

Pero había un problema real de **observabilidad**, y era mío:

- Las **21 fusiones críticas** se lanzaban en paralelo y **ninguna reportaba avance**. La barra se quedaba clavada en 76 % durante todo el bloque.
- Después corría `mergeMaterialInstances` **fuera** de ese bloque y tampoco reportaba: otro punto ciego en el mismo 76 %.
- En un móvil, minutos con la barra quieta son indistinguibles de un cuelgue.

Ahora cada fusión registra **`▶️ nombre`** al empezar y **`✅ nombre (Xs) n/21`** al terminar (por `println` y por log, porque en tests `android.util.Log` está stubbeado), el progreso avanza con el nombre de la fusión, y `mergeMaterialInstances` tiene su propia marca. Si algo se atasca, el log nombra el culpable.

También se arregló que el test de fusión real **se saltara en silencio**: el daemon de Gradle conserva el entorno con el que arrancó, así que `PF_FUSION_*` no llegaba al JVM de tests (`skipped=1`) y se medía el build equivocado. Ahora `RealFusionReportTest` lee `.research/fusion.properties` **con prioridad sobre el entorno**, y la exigencia de «3+ addons» solo aplica al modo A (antes una pareja de addons también se saltaba el test).

### 9.8 Pérdida de datos real descubierta al reproducir: addon con VARIOS Behavior Packs

Reproduciendo apareció algo más grave que el cuelgue. `Origins-BE.mcaddon` trae **tres** manifests:

| Carpeta | Contenido |
|---|---|
| `OriginsBE v1.5.6 BP` | **1.855 archivos** (1.534 `functions`, 171 `entities`, 89 `scripts`, 43 `structures`) |
| `OriginsBE v1.5.6 RP` | 392 archivos |
| `Origins Craftable Orb of Origin Bev` | **6 archivos** (manifest, icono, una receta) |

`classify` recorría los manifests y se quedaba con **el primero de cada tipo**:

```kotlin
is BEHAVIOR_PACK -> if (bpFolder == null) bpFolder = parent   // ← el resto se descarta
```

Como el primer BP encontrado era el del «Orb» (6 archivos), **el BP de 1.855 archivos se descartaba entero** — sin aviso: el modpack salía sin las entidades, funciones y scripts de Origins. Cualquier prueba de compatibilidad sobre ese pack estaba midiendo un pack mutilado.

Corregido: `AddonClassification.BOTH` lleva ahora `bpFolders`/`rpFolders` (listas) y el orquestador añade **todas**, registrándolas bajo el mismo propietario. `bpSubfolder`/`rpSubfolder` se conservan como «el primero» para no romper a quien consulte solo uno.

**Resultado medido con esos dos addons:**

| | Antes | Después |
|---|---|---|
| Entradas del BP en la salida | 289 | **1.883** |
| `functions/` | 0 | **1.212** |
| `entities/` | 5 | **167** |
| `scripts/` | 20 | **180** |
| `structures/` | 1 | **43** |

*(Quedan diferencias menores frente a los 1.534 `functions` del origen —1.212 en la salida— por colisiones de nombre entre addons; es un frente aparte, ya no una pérdida silenciosa de un pack completo.)*

---

### 9.9 Limpieza total del reporte (Origins-BE + OTG Backpack)

Estado de partida de ese par de addons: **228 líneas**, 53 líneas con `❌`, 40 referencias rotas, 123 avisos de conflicto, 3 texturas y 2 modelos faltantes, 4 entidades incompletas y 56 referencias irrecuperables.

Causas encontradas y corregidas (ninguna era un fallo de la fusión):

| Síntoma | Causa real | Arreglo |
|---|---|---|
| `❌ JSON inválido: entities/vanilla/sulfur_cube.json` | El archivo trae comentarios `//` (Bedrock los acepta); el preprocesado tolerante no los cubría en todas las posiciones | La comprobación usa ahora `LenientJson` |
| `texturas/entity/bat_v2`, `.../illager/evoker`, `.../wither_boss/wither_invulnerable` «no empaquetada» | Son texturas **vanilla**; faltaban del catálogo | Añadidas a `VANILLA_TEXTURE_PREFIXES` |
| `textures/entity/chicken` «sin origen» (con match absurdo al 50%) | `textures/entity/chicken.png` es un **archivo** vanilla, no una carpeta; el catálogo solo tenía `textures/entity/chicken/` | Añadida la entrada exacta |
| `geometry.llamaspit` / `geometry.wither_skull` «no existe en models/» + 2 placeholders | Son geometrías **vanilla** con esos nombres exactos (sin guion bajo); faltaban del catálogo | Añadidas a `VANILLA_GEOMETRIES` |
| `animation.llama_spit.*` / `animation.wither_skull.*` ausentes y sus render controllers | Vanilla; los prefijos `animation.llama.` / `animation.wither.` **no cubren** los nombres con guion bajo | Añadidos los prefijos y los 2 RC exactos |
| 53 `❌` de animaciones `5fs_br.*` / `controller.animation.*` | El `player.entity.json` del addon OTG referencia **24 animaciones que su propio pack no incluye** (verificado: no existen en ningún addon ni en vanilla) | **Neutralización**: se quitan las referencias colgantes y se informa en **una** línea |
| `🚫 .vscode/launch.json`, `.vscode/settings.json` «cifrado por sustitución» | Basura de desarrollo del autor; el ZIP la excluía pero se copiaba al directorio fusionado y el análisis la veía | Se filtra también al **copiar** |
| `sounds.json` (2 bytes) «cifrado por sustitución» | Es `{}`: un objeto vacío no tiene claves canónicas y se marcaba como cifrado | Los JSON vacíos se consideran válidos |
| `functions/tick.json`, `ui/_ui_defs.json` «cifrado» | Claves raíz `values` y `ui_defs` no estaban en la lista de claves canónicas | Añadidas (con `individual_event_sounds`, `block_sounds`, `interactive_sounds`) |
| 10 avisos de scripts como **HIGH** | Son consejos de coordinación («N addons escuchan el mismo evento»), y además **se duplicaban**: ya salían en la sección 3 del reporte | Se dejan solo como información en la sección 3; dejan de contarse como conflicto |

**Resultado final** con esos dos addons:

| Métrica | Antes | Después |
|---|---|---|
| Líneas del reporte | 228 | **81** |
| Líneas con `❌` | **53** | **0** |
| Referencias rotas | 40 | **0** |
| Referencias irrecuperables | 56 | **0** |
| Texturas / Modelos faltantes | 3 / 2 | **0 / 0** |
| Entidades incompletas | 4 | **0** |
| Hallazgos de integridad | 5 (3 avisos) | **0** (`✅ sin texto plano ni claves cifradas`) |
| Sección «ISSUES DETECTADOS» | 2 bloques | **no aparece** |
| Avisos de conflicto | 123 en 28 | **3** |

Los **3 avisos restantes no son defectos**, son notas de lo que hizo la fusión: `functions/tick.json` fusionado (4 funciones registradas), una colisión de clave `scale` sobrescrita, y el `minecraft:player` vanilla declarado en dos archivos (lo normal cuando un addon lo sobrescribe).

---

### 9.10 La app bloqueaba la exportación con 25/100 aunque el paquete fuera válido

Con el reporte ya limpio, la app **se negaba a exportar**: «Compatibilidad 25/100». El bloqueo no venía del reporte de fusión sino del **análisis previo** (`ConflictEngine.analyze` sobre los addons de origen) evaluado por `ExportCompatibilityGate`.

La nota es `100 − Σ penalizaciones` (CRITICAL 30, HIGH 15, MEDIUM 8, LOW 3). **25/100 ⇒ 75 puntos**, que es exactamente lo que suman 5 conflictos HIGH — y además el gate bloquea por regla propia a partir de 5 HIGH.

Se reprodujo construyendo los `Addon` desde los `.mcaddon` reales ([`DiagnosticoCompatibilidadGateTest`](app/src/test/java/com/packforge/app/domain/engine/DiagnosticoCompatibilidadGateTest.kt)). Aparecieron **dos defectos de fondo**:

1. **`autoResolve` no resolvía los empates de prioridad.** El criterio era «con empate no hay ganador objetivo, decide el usuario». Pero **todos los addons entran con prioridad 0**, así que *ningún* solapamiento se auto-resolvía y cada identificador o archivo compartido quedaba abierto sumando 15 puntos. Con 5 colisiones: 75 → 25/100 → exportación bloqueada.
   Sí hay criterio: el pipeline copia los addons **en orden y el último gana**, de forma determinista. Ahora el empate se resuelve con ese mismo orden (el que de verdad sobrevive en el paquete) y queda documentado en el propio conflicto.

2. **`scoreOfConflicts` penalizaba también lo ya resuelto.** Sumaba la severidad de *todos* los conflictos, incluidos los resueltos y los auto-resueltos. Ahora solo penalizan los que siguen **abiertos** (`UNRESOLVED`, no auto-resueltos, no descartados): un conflicto resuelto no es incompatibilidad.

Además se dejaron de marcar como **CRITICAL** dos solapamientos que la fusión resuelve por diseño: `scripts/` (se fusionan con `mergeScripts`) y `entities/player` (se conserva el de mayor prioridad y el resto se aparta). Un `scripts/main.js` compartido o un `player.json` común bastaban para bloquear el modpack entero.

**Antes / después (mismos dos addons):**

| | Antes | Ahora |
|---|---|---|
| Bloqueo de jugador (`minecraft:player`) | CRITICAL, no descartable | MEDIUM, auto-resuelto |
| Puntaje | **25/100** | **100/100** |
| Nivel | BLOCKED | **OPTIMAL** |
| `canExport` | **false** | **true** |

Tests actualizados a la semántica nueva (los dos que fijaban la conducta anterior): `tiedPriorityIsResolvedByFusionOrder` y `autoResolvedConflictsDoNotPenalizeTheScore`, más `unresolvedConflictsStillPenalizeTheScore` para garantizar que lo **no** resuelto sigue bajando la nota.

---

### 9.11 El móvil se quedaba colgado en el 78 % sin dejar rastro

Segundo reporte del usuario: la fusión en el móvil se quedaba **más de 30 minutos en el 78 %** (Fase 3D, resolución de dependencias de entidades). El problema no era solo la lentitud, sino que **no había forma de diagnosticarla**:

- La barra no vuelve a moverse hasta el 80 %, así que el 78 % era indistinguible de un cuelgue.
- El `fusion_report.txt` **solo se escribe al terminar**, y como nunca terminaba, el usuario no podía enviarlo. Sin reporte ni log, el diagnóstico era imposible.

En PC, con esos mismos addons, la fase tarda **7-10 s**. La diferencia (~200×) no es explicable por el hardware, así que hacía falta instrumentar para ver si **avanzaba o estaba atascada**.

Dos cambios:

1. **Latido de avance dentro de la fase.** Cada 25 entidades (y en la primera y la última) se registra `🔎 Fase 3D: n/total entidades` por log y por consola. Con eso, un `adb logcat` durante el cuelgue dice exactamente si progresa y en qué número se queda, sin necesidad de que la fusión termine.
   ```
   PF-PERF | ▶️ Fase 3D: 2 RP, entity/ en merged_rp
   PF-PERF | Fase 3D: 1/15 entidades
   PF-PERF | Fase 3D: 15/15 entidades
   PF-PERF | ✅ Fase 3D (7,5s)
   ```

2. **Red de seguridad: tiempo máximo por fase.** La Fase 3D se ejecuta ahora bajo `withTimeoutOrNull(240 s)`. Si expira, **la fusión continúa sin ella** y el reporte lo deja anotado:
   > ⏱️ La resolución de dependencias de entidades superó 240s y se omitió para no bloquear la exportación. El paquete es válido, pero pueden faltar reparaciones de modelos/texturas.

   Es preferible un paquete válido sin esa reparación que una exportación que **nunca termina**: garantiza que el usuario reciba siempre su `.mcaddon` y su reporte (y, de paso, que podamos diagnosticar).

Con el latido, la causa de fondo (por qué en Android tarda 200× con 15 entidades) queda al alcance: basta un `adb logcat -s PackForge_DepResolver:* PackForge_Export:*` mientras corre.

---

### 9.12 La causa real del "cuelgue" del móvil: thrashing de swap (medido con ADB)

El usuario reportó que la fusión se quedaba >30 min en el 78 % sin terminar. Con `adb` conectado al Xiaomi (`25062PC34G`, Android 16), se midió el proceso vivo:

```
top -H -p <pid>:  Threads: 84 total, 0 running, 84 sleeping
                 %CPU: 0.0 en todos los hilos
dumpsys meminfo: SwapPss Dirty = 156.372 KB   ← ¡156 MB de la app en swap!
                 VmHWM (pico RSS) ≈ 482 MB
free -m:         Mem: 7648 total, 7451 used, 196 free   ← casi sin RAM libre
df -h:           /data 161 GB libres (no es espacio)
```

**No era un deadlock ni un bucle infinito.** Con 0 % de CPU y todos los hilos dormidos, el proceso estaba **bloqueado esperando páginas de memoria**. El sistema tenía solo 196 MB libres de 7,6 GB; la app llegó a 482 MB de pico y el kernel **expulsó 156 MB a swap**. A partir de ahí, cada acceso a datos ya paginados es una lectura de disco → la fusión avanza pero a velocidad ínfima, sin logs y sin mover la barra. En PC sobra RAM, por eso la misma fase tarda ~8 s.

**Por qué la app consume tanta memoria en este paso:** las fusiones críticas se lanzaban **las 22 a la vez** (`async { … }` para todas), y cada una lee y parsea JSON en memoria. El consumo se multiplica por el número de tareas concurrentes.

**Arreglo: limitar la concurrencia (sin sacrificar contenido).** Se añadió un `Semaphore` con cupo `LIMITE_FUSIONES_PARALELAS = min(núcleos, 4)` aplicado a:

- Las 22 fusiones críticas ([`PackForgeOrchestrator`](app/src/main/java/com/packforge/app/domain/engine/PackForgeOrchestrator.kt)): ahora máximo 4 a la vez.
- La resolución de dependencias de entidades ([`EntityDependencyResolver`](app/src/main/java/com/packforge/app/domain/engine/EntityDependencyResolver.kt)): antes una corrutina por entidad; ahora máximo 4 a la vez, tomando el permiso **antes** de leer el archivo para que el texto/JSON de cada entidad solo exista durante su turno.

**Importante: NO se omiten fases.** Una primera idea fue saltarse la Fase 3D si excedía un tiempo, pero eso **perdería reparaciones de modelos/texturas**, justo lo contrario del objetivo. Se descartó y se eliminó el código de timeout. La solución correcta es bajar el consumo de memoria, no esquivar el trabajo.

**Medición tras el cambio (PC, mismos addons):** Fase 3D **7,9 s** (antes 7,5–10 s), total **124,9 s**. Es decir, **el límite de concurrencia no cuesta tiempo en PC**: el cuello en el móvil era la presión de RAM, no el paralelismo. En el teléfono debe eliminar el thrashing.

**Lección.** Un síntoma de "lentitud extrema" en Android puede ser *memoria*, no CPU ni I/O de archivos. Y la métrica que lo revela es `SwapPss` en `dumpsys meminfo`: cuando una app tiene cientos de MB en swap, está paginando en cada acceso. Antes de optimizar algoritmos, medir RAM/swap.

---

### 9.13 Verificación en el móvil con ADB: el cuelgue desapareció (y quedó un problema nuevo)

Con el build que limita la concurrencia por memoria, se lanzó la misma fusión (Origins + OTG) en el Xiaomi y se monitoreó por `adb`:

```
top -H:  %CPU 96.5 (antes 0), tiempo acumulando   ← trabaja, no está bloqueado
RAM libre durante la fusión: 338 MB (antes 196)   ← menos presión
Swap estable ~2,1 GB (antes subía sin parar)
```

El logcat en vivo mostró los latidos cruzando el punto exacto donde antes se colgaba:

```
✅ Fase 3D (438.7s) → Fase 3E → PASO 4d Decifrador (4s) → PASO 5 validador → PASO 5b healer …
```

Y el `fusion_report.txt` extraído del teléfono (`/sdcard/Download/Origins OTG_fusion_report.txt`) confirma que **terminó limpio**:

| Métrica | Valor |
|---|---|
| Tiempo total | **719 s (~12 min)** |
| Texturas / Modelos faltantes | **0 / 0** |
| Referencias reparadas | 26 |
| Conflictos | 3 (informativos) |
| ISSUES / ❌ | ninguno |

**El thrashing de swap estaba confirmado como causa del cuelgue**: al limitar la concurrencia, la fusión pasa de >30 min infinitos a terminar en ~12 min con resultado correcto.

**Queda un problema de rendimiento distinto y medible.** El desglose por fase en el móvil vs PC:

| Fase | Móvil | PC | Ratio |
|---|---|---|---|
| Extracción / clasificación / renamespacer / copia | 3-7 s cada una | similar | ~1× |
| **Fase 3D (15 entidades)** | **439 s** | ~8 s | **~55×** |
| **Fusión de archivos críticos (22)** | **121 s** | ~7 s | ~17× |
| **Healer integridad** | **94 s** | ~4 s | ~23× |

Que las fases I/O puras vayan a velocidad normal y estas tres tarden 20-55× apunta a una causa específica (no a "Android es lento"): muy probablemente **relectura masiva de archivos ya indexados en memoria**, o **GC/paginación residual**. Es el siguiente frente de optimización; el cuelgue funcional ya está resuelto.

---

## 10. Qué haría falta para blindar la compatibilidad (más allá de estos addons)

1. **Test de regresión con packs reales**: usar `Better MC` (este caso) + un pack con addon cerrado
   (CoreCraft) + uno con nombres ofuscados, y afirmar cifras exactas (0 falsos positivos, 0 placeholders
   duplicados, 0 archivos borrados).
2. **Suite de “verdad” vanilla**: descargar `bedrock-samples/resource_pack` y generar el catálogo de ids en
   tiempo de build, en vez de listas a mano.
3. **Gate de exportación**: no empaquetar si el cierre de referencias deja ids sin resolver que **no** están en
   el catálogo vanilla.
4. **Invariantes del `.mcaddon`**: (a) ningún identificador definido 2 veces, (b) ningún id vanilla
   sobrescrito, (c) todo archivo declarado `.bak`/neutralizado presente como `_packforge/*.bak`, (d) todo
   archivo eliminado listado en el reporte.
