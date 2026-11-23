# Add-ons de Minecraft Bedrock — Referencia técnica aplicada a PackForge

> Documento pilar de conocimiento de dominio. Explica **cómo funcionan los addons de Bedrock** (RP, BP, manifests, JSON, geometrías, scripts, carga y prioridad) y, en cada tema, **qué hace PackForge hoy** al fusionarlos (bloques `🔧 En PackForge`).
> Base: Bedrock 1.21.x. Los formatos cambian entre versiones: ante dudas críticas contrasta con la documentación oficial (`learn.microsoft.com/minecraft/creator`), `bedrock.dev` y el repositorio `Mojang/bedrock-samples`.
> Complementa a [`PACKFORGE_ENGINE.md`](PACKFORGE_ENGINE.md) (arquitectura del motor) y [`../../AGENTS.md`](../../AGENTS.md) (reglas de oro).
> **Etiquetas de certeza:** lo marcado *(verificar)* es comportamiento de Bedrock que conviene comprobar en el juego/versión concreta antes de automatizarlo.

---

## 1. Visión general

Un **add-on** es un conjunto de packs que modifican el juego sin tocar su código nativo.

| Pack | Lado | Contiene |
|---|---|---|
| **Resource Pack (RP)** | Cliente | Cómo se **ve y suena**: texturas, modelos, animaciones, partículas, sonidos, UI, textos |
| **Behavior Pack (BP)** | Servidor / lógica | Qué **existe y cómo se comporta**: entidades, bloques, ítems, recetas, loot, spawn, funciones, estructuras, scripts |

Se conectan por **identificadores de texto idénticos** (el `identifier` de una entidad en el BP = el de su definición cliente en el RP) y, opcionalmente, por una dependencia por UUID en el manifest. Un addon típico es 1 RP + 1 BP; el BP puede incluir un módulo de **scripts** (Script API, JavaScript).

> 🔧 **En PackForge:** un archivo importado puede ser solo BP, solo RP, **BOTH** (ambos) o **MULTI** (varios BP/RP en un mismo archivo). `AddonExtractor.classify` lo detecta y el motor trabaja siempre con *listas* de carpetas BP y RP, fusionándolas en **un** BP y **un** RP.

## 2. Distribución e instalación

| Extensión | Qué es |
|---|---|
| `.mcpack` | Zip de un solo pack, `manifest.json` en la raíz |
| `.mcaddon` | Zip con varios packs: cada carpeta de primer nivel es un pack con su manifest |
| `.mcworld` | Mundo (puede traer packs embebidos) |
| `.mctemplate` | Plantilla de mundo |

Carpetas en `com.mojang`: `development_resource_packs` / `development_behavior_packs` (lectura directa, para desarrollo), `resource_packs` / `behavior_packs` (importados), `minecraftWorlds/<mundo>/` con `world_resource_packs.json` y `world_behavior_packs.json` (listas de `pack_id` + `version` activos) y, si el mundo embebe packs, `resource_packs/` y `behavior_packs/` propias. `global_resource_packs.json` activa RPs globales.

> 🔧 **En PackForge:** importa `.mcaddon`, `.mcpack` y `.zip`; exporta **un solo `.mcaddon`** (RP + BP). El extractor protege contra *zip slip* (`isSecurePath`) y el empaquetado final evita el *data descriptor* de ZIP (compatibilidad con el importador de Bedrock), excluye basura (`.git`, `.vscode`) y se audita (`auditFinalMcAddon`). `.mcworld`/`.mctemplate` no son el objetivo del producto.

## 3. manifest.json

```json
{
  "format_version": 2,
  "header": { "name": "Mi Addon BP", "description": "…", "uuid": "UUID-1",
              "version": [1, 0, 0], "min_engine_version": [1, 21, 0] },
  "modules": [
    { "type": "data",   "uuid": "UUID-2", "version": [1, 0, 0] },
    { "type": "script", "language": "javascript", "uuid": "UUID-3",
      "version": [1, 0, 0], "entry": "scripts/main.js" }
  ],
  "dependencies": [
    { "uuid": "UUID-HEADER-DEL-RP", "version": [1, 0, 0] },
    { "module_name": "@minecraft/server", "version": "1.16.0" }
  ]
}
```

| Campo | Función | Notas |
|---|---|---|
| `format_version` | Esquema del manifest | Usar 2 |
| `header.uuid` / `version` | Identidad del pack / `[mayor, menor, parche]` | UUID v4, único; los mundos guardan la versión requerida |
| `header.min_engine_version` | Versión mínima del juego | Al fusionar: **máximo** de todos |
| `header.pack_scope`, `base_game_version` | Alcance (`world`/`global`/`any`, solo RP) y base vanilla | Opcionales |
| `modules[].type` | `resources` (RP), `data` (BP), `script`, `client_data`, `interface`, `world_template`, `skin_pack` | Un pack puede tener varios |
| `modules[].uuid` | Identidad del módulo | **Distinto** del uuid del header y de otros módulos |
| `dependencies[]` | `uuid`+`version` de otro pack **(uuid del header)**, o `module_name`+`version` (cadena) de módulos de script | BP→RP activa el RP automáticamente |
| `capabilities` | Experimentos/permisos (`script_eval`, `pbr`, `raytraced`, `editorExtension`…) | Al fusionar: unión |
| `subpacks[]` | `folder_name`, `name`, `memory_tier` | Variantes (p. ej. resolución) en RP. La carpeta debe existir en la raíz del pack. Bedrock aplica **una** variante según el tier del dispositivo |
| `metadata` | `authors`, `license`, `url`, `generated_with` | Informativo |

Puntos delicados: `version` es **arreglo de 3 enteros** en header/módulos pero **cadena** (`"1.16.0"`, `"2.0.0"`, o con `-beta`) en dependencias de módulos de script; un UUID repetido entre packs instalados hace que uno no cargue o pise al otro; `modules[].type` es lo que distingue BP (`data`) de RP (`resources`), **no** el orden ni el nombre.

> 🔧 **En PackForge** (`ManifestGenerator`, `AddonExtractor.resolvePackRoot`):
> - Raíz de pack elegida **por `modules[].type`** (un bug devolvía el BP en vez del RP al tomar “el primer manifest”).
> - Manifiesto fusionado: UUIDs v4 **nuevos** (header y módulos, todos distintos); `min_engine_version` = máximo (nunca `[1,0,0]`); BP depende del **UUID del header del RP fusionado**; módulo `script` con `entry` si hay scripts; dependencias `@minecraft/*` unificadas; `capabilities` experimentales añadidas cuando hacen falta; UTF-8 **sin BOM**; autor/descripción también en el RP (el juego muestra una ficha por pack).
> - Limitación: la versión de `@minecraft/*` se resuelve por **máximo numérico** (`compareSemver`): no distingue estable/beta ni compatibilidad entre mayores (1.x vs 2.x). Ver `PACKFORGE_ENGINE.md §13`.
> - El conflicto `MANIFEST_UUID` se detecta en `ConflictEngine.detectManifestUuidConflicts`.

## 4. Estructura del Resource Pack (RP)

| Ruta | Contenido | Fusión en PackForge |
|---|---|---|
| `manifest.json`, `pack_icon.png` | Identidad / icono | Generado nuevo; icono = personalizado o el de algún addon (`applyCustomIcon`) |
| `entity/` | Definiciones **cliente** de entidades (`minecraft:client_entity`) | `mergeEntityDefinitions` + resolvers de dependencias |
| `attachables/` | Apariencia de ítems equipados/en mano | `mergeAttachables` + grafo |
| `models/entity/`, `models/blocks/` | Geometrías `.geo.json` | `mergeGeometryFiles` (moderno **y** legacy) |
| `animations/`, `animation_controllers/` | Animaciones y máquinas de estado | `mergeAnimations` |
| `render_controllers/` | Geometría + textura + material | `mergeRenderControllers` |
| `materials/` | `.material` / `.json` | Copia + `mergeMaterialInstances`; el catálogo de materiales vanilla evita falsos faltantes |
| `particles/` | `.particle.json` | Renombrado en origen (`ParticleDeduplicator`) |
| `fogs/`, `structures/`(RP) | Niebla / binarios | `FolderAliasRegistry` (alias `pf_<hash6>`) |
| `sounds/` + `sound_definitions.json`, `music_definitions.json`, `sounds.json` | Audio y mapeos | `mergeSoundDefinitions` (renombra claves), `mergeSoundsJson` |
| `texts/*.lang`, `languages.json`, `language_names.json` | Textos | `mergeLangFiles` |
| `textures/**` | `.png`/`.tga` | `ResourcePathRegistry` (dedupe por MD5 / alias `_pf<hex6>`) |
| `textures/terrain_texture.json`, `item_texture.json` | Clave corta → ruta (bloques/ítems) | `mergeTerrainTexture`, `mergeItemTexture` |
| `textures/flipbook_textures.json`, `texture_list.json` | Texturas animadas / precarga | `mergeFlipbookTextures`, `mergeTexturesList` |
| `*.texture_set.json` | Conjuntos PBR | Copia (con alias de binarios) |
| `blocks.json` | Sonido/texturas legadas de bloques | `mergeBlocksJson` |
| `biomes_client.json`, `font/` | Visual de bioma / glifos | `mergeBiomesClient`, `mergeFontGlyphMaps` |
| `item_catalog/`, `cameras/`, `dimensions/`, `worldgen/feature_rules/` | Catálogo de ítems, presets de cámara, dimensiones, reglas de features | `mergeDeclarativeFamilies` (enrutado por `identifier`; `camera_preset` lo declara en la raíz) |
| `trading/` | Tablas de comercio (sin `identifier`, se referencian por ruta) | `mergeTradingTables` (fusión por ruta + reporte de colisión) |
| `ui/` | JSON UI (`_ui_defs.json`, `_global_variables.json`, pantallas) | `BedrockUiMerger` |
| `subpacks/`, `cameras/`, `contents.json` | Variantes / cámaras / índice de packs cifrados | Subpacks: `SubpackRegistry` (+ `header.subpacks`, ver más abajo). Cámaras: copia (sin verificar). `contents.json` no se debe editar |

> 🔧 **En PackForge:** estas rutas están en `CRITICAL_DIRS_RP` del orquestador: **no** pasan por el deep-merge genérico (tienen fusión dedicada). Binarios con la **misma ruta y contenido distinto** reciben alias `nombre_pf<hex6>.ext`; con contenido idéntico se deduplican. Las rutas **sin extensión** de `terrain_texture`/`item_texture`/entidades también se reescriben (`ResourcePathRegistry` guarda forma con y sin extensión). Un punto en el nombre (`foo.bar`) **no** es una extensión.

## 5. Estructura del Behavior Pack (BP)

| Ruta | Contenido | Fusión en PackForge |
|---|---|---|
| `manifest.json`, `pack_icon.png` | Identidad | Generado nuevo |
| `entities/` | Entidades **servidor** (`minecraft:entity`) | Deep-merge genérico (+ `IdentifierRemapper` para ids propios duplicados); `player.entity.json` con `mergePlayerEntity` |
| `blocks/`, `items/` | Bloques e ítems personalizados | Deep-merge genérico + `IdentifierRemapper` + `mergeMaterialInstances` |
| `recipes/` | Recetas | `mergeRecipes` |
| `loot_tables/` | Tablas de botín (se referencian por **ruta**) | `mergeLootTables` |
| `spawn_rules/`, `trading/`, `dialogue/` | Aparición, comercio, diálogos | Genérico + remapper (`minecraft:spawn_rules` es clave declarante) |
| `functions/` + `tick.json` | `.mcfunction`; funciones por tick | `mergeTickJson` (unión de `values`); `/structure load` reescrito si se aliasa la estructura |
| `structures/` | `.mcstructure` (NBT binario, little-endian) | `FolderAliasRegistry` |
| `features/`, `feature_rules/`, `biomes/`, `worldgen/` | Generación del mundo | Genérico + remapper (`minecraft:feature(+_rules)`, `minecraft:biome` declarantes) |
| `animations/`, `animation_controllers/` (BP) | Lógica de animación servidor | Genérico |
| `item_catalog/` | Inventario creativo | Genérico *(verificar solapes de grupos)* |
| `scripts/` | Script API | **No** se copia genérico: `mergeScripts` (ver §9) |
| `texts/` | Textos BP | `.lang` también unido |

> 🔧 **En PackForge:** `CRITICAL_DIRS_BP` = `loot_tables/`, `recipes/`, `player.entity.json`, `functions/tick.json`, `functions/ticks.json`. El resto de JSON del BP se fusiona en memoria con `JsonDeepMerger` (prioridad por orden de addons, último gana en escalares; objetos recursivos; arrays por unión estructural).

## 6. Identificadores, namespaces y referencias cruzadas

Convención: `namespace:nombre` en minúsculas (letras, números, `_`). `minecraft` está **reservado**: usarlo equivale a sobrescribir vanilla.

Dos familias de ids:

- **Persistentes** (se guardan en mundos): `identifier` de entidades, bloques e ítems, claves de *dynamic properties*, tags, objetivos de scoreboard, nombres de estructura. **Renombrarlos rompe mundos existentes.**
- **De recursos** (no se guardan): geometrías, animaciones, controladores, render controllers, partículas, sonidos, texturas, claves de atlas. Se pueden renombrar si se reescriben **todas** las referencias.

Mapa de referencias que hay que mantener coherente:

| Origen | Referencia | Destino |
|---|---|---|
| RP `entity/*.json` | `description.identifier` | = identifier de la entidad BP |
| RP `entity/*.json` | `geometry` (alias → `geometry.xxx`; string **u objeto** `{"default": …}`) | `identifier` dentro de un `.geo.json` |
| RP `entity/*.json` | `textures` (alias → ruta **sin extensión**) | `textures/…png/.tga` |
| RP `entity/*.json` | `animations`, `render_controllers` (strings **u objetos condicionales**), `materials` | animaciones, controladores, `.material` |
| RP `entity/*.json` | `particle_effects`, `sound_effects` | `particles/`, `sound_definitions.json` |
| RP `attachables/*.json` | `description.identifier` | = identifier del ítem BP |
| BP bloque | clave en `material_instances` | `terrain_texture.json` → `textures/blocks/…` |
| BP ítem | `minecraft:icon` (clave corta) | `item_texture.json` → `textures/items/…` |
| BP entidad/bloque | rutas de loot / trade | `loot_tables/…`, `trading/…` |
| BP funciones | `function ruta`, `/structure load nombre` | `functions/…`, `structures/…` |
| BP scripts | cadenas con ids, tags, estructuras | cualquier id anterior |
| `.lang` | `item.ns:id.name`, `tile.ns:id.name`, `entity.ns:id.name` | ids de ítem/bloque/entidad |
| `sound_definitions.json` | `sounds/ruta` sin extensión | `.ogg`/`.wav` |

Hay ids **globales entre todos los RPs activos**: `geometry.xxx`, `animation.xxx`, `controller.animation.xxx`, `controller.render.xxx`, y las claves de `terrain_texture`/`item_texture`. Dos packs con el mismo id colisionan aunque estén en archivos distintos.

> 🔧 **En PackForge:**
> - **Persistentes**: `IdentifierRemapper` renombra el id del **addon perdedor** a `pf_<hash>:nombre` y reescribe sus referencias (JSON + `.lang`); nunca toca `minecraft:*` y nunca toca al ganador. Consecuencia: un mundo que ya usaba el id del perdedor lo pierde/transforma → hay que dejarlo reportado.
> - **De recursos**: `ParticleDeduplicator` (partículas), `ResourcePathRegistry`/`FolderAliasRegistry` (binarios), `EntityDependencyResolver` (geometrías/animaciones/render controllers sombreados → hermano `_pf<hash6>`, **decidido por hash de contenido**).
> - **Tokens estables**: el sufijo `_pf<hex>` que desambigua ids/recursos se deriva de **contenido**: geometrías/animaciones/RC por hash de contenido, partículas por contenido del addon, e ids renombrados por los **UUID de los manifiestos** del addon (`AddonOwnerRegistry.stableSeed`). Nunca de la ruta extraída (`extracted_<epochMs>_…`): el id renombrado vive en los mundos y debe seguir resolviéndose al reinstalar.
> - **Distinguir referencia de dato** es crítico: `BedrockRefs` decide si un string es id/ruta real, **alias propio de la entidad** (`Geometry.default`, `Texture.default`, `Material.default`), **expresión Molang** (`q.is_baby ? Geometry.baby : Geometry.default`) o clave de objeto condicional (`{"controller.render.x": "q.variant > 0"}`). Stringificar estructuras generó 131 falsos “no existe”.
> - **Vanilla**: `VanillaAssets` + `assets/vanilla_all_keys_complete.txt` (prefijo de segmento exacto). Un addon nunca empaqueta lo vanilla; referenciarlo no es error.

## 7. JSON clave: estructura y complejidad

### 7.1 Entidad cliente (RP)
`format_version` 1.10.0 (o 1.8.0) → `minecraft:client_entity` → `description`: `identifier`, `materials`, `textures`, `geometry`, `animations`, `scripts` (variables Molang, `animate`), `render_controllers`, `spawn_egg`, `sound_effects`, `particle_effects`, `enable_attachables`, `min_engine_version`. Todo se declara con **alias** (`default`, `walk`…) que usan los controladores.

> 🔧 Si dos addons declaran **el mismo** `identifier` con `format_version` distinta, el fichero fusionado conserva la del último (prioridad de fusión) y el contenido del otro se reinterpreta bajo ese esquema; el motor lo reporta (`FORMAT_VERSION_MISMATCH`) y **no** sube ni reescribe la versión (regla 16). Antes la mezcla era invisible.
> 🔧 Se clasifica por **contenido** (raíz `minecraft:client_entity`), no por nombre de archivo: hay addons con nombres ofuscados (`.fT.json`). Una `client_entity` sin `minecraft:entity` en el BP puede ser **huérfana del origen** (contenido muerto): `EntityOrphanClassifier` lo decide mirando si el propio pack la usa, sin catálogo de mobs vanilla.

### 7.2 Entidad servidor (BP)
`minecraft:entity` → `description` (`identifier`, `is_spawnable`, `is_summonable`, `runtime_identifier`, `properties`, `scripts`, `animations`) + `component_groups` + `components` + `events`.

- `component_groups`: bloques de componentes añadidos/quitados por eventos. `events`: `add`, `remove`, `set_property`, `trigger`, `sequence`, `randomize`, con filtros.
- `properties` (1.19.70+): `float`/`int`/`bool`/`enum` con `default`, `range`/`values`, `client_sync`; el RP las lee con `query.property('ns:prop')`.
- `runtime_identifier` hereda comportamiento nativo de otra entidad (cambiarlo cambia la IA).

Es el archivo **más difícil de fusionar**: mezcla datos (componentes) y lógica (eventos y filtros con orden relevante).

> 🔧 Dentro de `components`, `JsonDeepMerger` aplica `BedrockComponentMerger` (objetos recursivos, arrays sin duplicados, escalares: gana el entrante). Los contenedores raíz (`minecraft:entity`, `minecraft:item`, `minecraft:client_entity`) **no** pasan por el merger de componentes (un bug sobrescribía todo `components`). `JsonDeepMerger.mergeBedrockEvents` apila eventos en `sequence`. `player.entity.json` tiene fusión propia (`mergePlayerEntity`, por versión de formato, con conflictos numéricos registrados). Si dos addons modifican la misma entidad **vanilla** (`minecraft:cow`), se resuelve por componente, **no** por renombrado.

### 7.3 Bloques (BP)
`minecraft:block` → `description` (`identifier`, `menu_category`, `states`, `traits`) + `components` + `permutations` (`condition` Molang + componentes) + `events` (legado). Componentes frecuentes: `minecraft:geometry`, `material_instances`, `collision_box`, `selection_box`, `light_emission`, `destructible_by_mining`, `friction`, `flammable`, `loot`, `map_color`, `display_name`, `tick`, `placement_filter`, `custom_components` (enlaza con scripts). `states`: booleanos, enteros en rango o enums, con **límites** de estados/permutaciones. `material_instances` usa `*` como comodín y claves por cara.

> 🔧 `mergeMaterialInstances` (síncrono, al final; depende de `terrain_texture` + entidades + animaciones) revisa los `minecraft:material_instances` de los bloques del BP fusionado: si una textura referenciada no está mapeada en `terrain_texture.json`, **la añade y copia el PNG** desde los addons originales.

### 7.4 Ítems (BP)
`minecraft:item` (1.20.50+): `description` (`identifier`, `menu_category`) + `components` (`minecraft:icon`, `display_name`, `max_stack_size`, `durability`, `damage`, `food`, `wearable`, `cooldown`, `custom_components`…). El icono es clave corta de `item_texture.json`. El formato legado dejaba parte en el RP (`items/`).

### 7.5 Render controllers
`format_version` 1.8.0 → `controller.render.xxx`: `geometry`, `materials` (`[{ "*": "Material.default" }]`), `textures` (`Texture.default`), `arrays` (elegir geometría/textura/material con Molang), `part_visibility`, `color`, `overlay_color`, `is_hurt_color`. Los alias `Texture.xxx`/`Geometry.xxx`/`Material.xxx` deben existir en la entidad cliente.

> 🔧 Aviso conocido: `material.outline` en render controllers es un **accesor Molang**, no un material; los validadores deben filtrarlo (`BedrockRefs`).

### 7.6 Animaciones y controladores
- **Animación** `animation.x.y`: `loop`, `animation_length`, `bones` (position/rotation/scale por fotogramas o Molang), `timeline`, `sound_effects`, `particle_effects`, `override_previous_animation`, `anim_time_update`.
- **Controlador** `controller.animation.x.y`: `initial_state`, `states` (`animations`, `transitions` condicionadas por Molang, `blend_transition`, `on_entry`, `on_exit`…).
- Las variables Molang (`variable.xxx`/`v.xxx`) se comparten dentro de la entidad: dos controladores con el mismo nombre se **pisan**.

> 🔧 `JsonDeepMerger` trata los arrays como **posicionales** al fusionar (`bones[]`, `arrays.textures[]`, `materials`): concatenar a ciegas desplaza índices y usa el hueso o textura equivocados, por eso usa unión por igualdad estructural. Animaciones/render controllers sombreados con contenido distinto → hermano `_pf<hash6>` (el binding es por id).

### 7.7 Partículas
`particle_effect` → `description` (`identifier`, `basic_render_parameters` con `material` y `texture`) + `curves` + `components` (`minecraft:emitter_rate_*`, `emitter_lifetime_*`, `emitter_shape_*`, `particle_lifetime_expression`, `particle_appearance_billboard`, `particle_motion_*`, `particle_appearance_tinting`…).

### 7.8 Sonidos
`sounds/sound_definitions.json` (`format_version` 1.14.0 → id → `category`, `sounds[]` con `name`, `volume`, `pitch`, `weight`, `stream`, `load_on_low_memory`, `min_distance`, `max_distance`); `sounds.json` (asigna eventos: `block_sounds`, `entity_sounds`, `individual_event_sounds`, `interactive_sounds`); `music_definitions.json`. Formato recomendado `.ogg`; rutas **sin extensión**.

### 7.9 Recetas, loot, spawn, comercio
Recetas: `minecraft:recipe_shaped`, `recipe_shapeless`, `recipe_furnace`, `recipe_brewing_mix`, `recipe_brewing_container`, `recipe_smithing_transform`, `recipe_smithing_trim` (con `description.identifier`, `tags`, ingredientes, `result`). Loot: `pools` → `rolls` → `entries` (`type`, `name`, `weight`, `functions`, `conditions`). Spawn: `minecraft:spawn_rules` (`description.identifier`, `population_control`, `conditions`). Comercio: `trading/*.json` por tiers, enlazado con `minecraft:trade_table`.

### 7.10 Funciones, estructuras, mundo
`.mcfunction`: un comando por línea sin `/`, comentarios `#`; `functions/tick.json` lista (`values`) funciones por tick. `.mcstructure`: NBT binario. `feature_rules` apuntan a un feature por identifier y definen el pase de colocación.

> 🔧 **Particularidades del JSON de Bedrock** (por qué existe `LenientJson`): comentarios `//` y `/* */` en cualquier posición; **claves duplicadas** (addons cerrados las usan como cebo y la **última** es la real); BOM UTF-8; homógrafos cirílicos en claves señuelo; números `1`/`1.0`. `org.json` estricto rechaza todo eso. Tampoco existe garantía de que “JSON sintácticamente válido” signifique “válido para Bedrock”: el reporte de integridad lo distingue (`MergeReportGenerator.auditJsonIntegrity`).

## 8. Geometrías (`.geo.json`)

**Formato legacy (1.8.0):** la geometría es **clave de primer nivel** (`geometry.nombre`) con `texturewidth`, `textureheight`, `visible_bounds_*` y `bones`; admite **herencia** `geometry.hijo:geometry.padre`.
**Formato moderno (1.12.0 → 1.21.x):** arreglo `minecraft:geometry`; **no** admite herencia con `:` (hay que aplanar).

```json
{
  "format_version": "1.12.0",
  "minecraft:geometry": [{
    "description": { "identifier": "geometry.ns_mob", "texture_width": 64, "texture_height": 64,
                     "visible_bounds_width": 2, "visible_bounds_height": 2.5,
                     "visible_bounds_offset": [0, 1.25, 0] },
    "bones": [
      { "name": "body", "pivot": [0, 12, 0],
        "cubes": [{ "origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 16] }] },
      { "name": "head", "parent": "body", "pivot": [0, 24, 0],
        "cubes": [{ "origin": [-4, 24, -4], "size": [8, 8, 8], "uv": [0, 0] }] }
    ]
  }]
}
```

| Elemento | Detalle | Riesgo al fusionar/convertir |
|---|---|---|
| `identifier` | Único **global** entre RPs (`geometry.xxx`) | Colisión silenciosa: gana uno (no determinista) |
| `texture_width/height` | Escala del UV | Reescalar o reempaquetar la textura rompe el UV |
| `bones[].name` | Único dentro de la geometría | Las animaciones apuntan a bones **por nombre** |
| `parent`, `pivot`, `rotation` | Jerarquía y transformaciones | Un parent inexistente invalida el modelo |
| `cubes[]` | `origin`, `size`, `uv`, `pivot`, `rotation`, `inflate`, `mirror` | `uv` = `[u,v]` (caja) o **por cara** (`north/south/east/west/up/down` con `uv`, `uv_size`, `material_instance`) |
| `locators` | Puntos de anclaje | Los usan attachables y partículas |
| `poly_mesh`, `texture_meshes` | Mallas arbitrarias | Más pesadas, formato exigente |
| `binding` | Molang para attachables | Requiere el bone de armadura/ítem |
| `visible_bounds_*` | Caja de visibilidad | Mal valor = el mob desaparece al mirar de lado |

> 🔧 **En PackForge:**
> - `mergeGeometryFiles` acepta **moderno y legacy** (`mergeGeoJson` fusiona ambos); antes ignoraba el legacy y 2 archivos no se escribían nunca.
> - **Regla de oro: nunca re-empaquetar texturas de entidades**; solo se cambia su ruta.
> - Geometría referenciada pero ausente → se busca por `identifier` en los RP origen y se copia (`EntityDependencyResolver`, `LazyDependencyResolver`). Solo si no existe en **ningún** addon, no es vanilla y el pack no la define, `PackForgeHealer.repair` crea `<id>.placeholder.geo.json` (caja 8×8×8, `format_version 1.12.0`) para que la entidad al menos aparezca. Un bug anterior fabricó placeholders encima de ids vanilla (armaduras, flechas, aldeano) y duplicó 148 ids reales.
> - Geometrías de **motor** (`geometry.item_sprite`, `geometry.arrow`, `geometry.humanoid.*`…) están en `VanillaAssets`: referenciarlas no es “modelo faltante”.
> - La herencia legacy (`:`) *(verificar)*: si el padre vive en otro pack, la fusión debe preservar el padre o aplanar; no hay aplanado automático documentado en el motor.

## 9. Script API

- El módulo `script` declara `language: javascript` y **un único `entry`** (p. ej. `scripts/main.js`); se permiten `import` entre archivos del mismo pack.
- Dependencias típicas: `@minecraft/server`, `server-ui`, `server-net`, `server-admin`, `server-gametest`. Versiones estables (`1.x.x`, `2.x.x`) o `-beta` (requieren el experimento **Beta APIs**).
- TypeScript hay que compilarlo/empaquetarlo antes de entregar el BP.
- Cada BP con scripts corre en su **propio contexto**: no comparten variables. Al fusionar en un único BP, todo comparte el contexto de ejecución del pack.
- *Dynamic properties* y datos persistentes están asociados al pack que los creó; cambiar el UUID del BP puede dejarlas inaccesibles *(verificar)*.
- `scriptevent` usa `namespace:id`; dos packs con el mismo id se disparan mutuamente.
- Un `custom_components` de bloque/ítem solo funciona si el script lo registra con `registerCustomComponent` con el **mismo nombre**; registrar el mismo nombre dos veces es **error fatal** en runtime.

> 🔧 **En PackForge** (`mergeScripts`, `ScriptRegistry`, `ScriptFlowAnalyzer`, `ScriptCollisionAnalyzer`, `CustomComponentRewriter`, `BedrockCompatibilityAnalyzer`):
> - Cada BP se copia íntegro a `scripts/modules/<addon_id>/` (ruta relativa original preservada, para que sus `import "./x.js"` sigan resolviéndose) y el `main.js` generado los carga **encadenados**, no en paralelo → **scopes aislados** por módulo ES (no hay “Variable already defined” entre addons) y orden de registro determinista.
> - La raíz `scripts/` queda reservada al bootstrapper: las copias planas de los `.js` de cada addon no se empaquetan (dos addons con `scripts/index.js` se pisaban y podían sobrescribir el `main.js`).
> - Se bloquea la exportación si: el `entry` no existe/está vacío, hay **ciclos** de imports entre addons, o no se pueden copiar los scripts. Se **avisa** de: imports relativos rotos, varios packs con el mismo entry, dynamic properties compartidas, suscripciones masivas al mismo evento (orden de ejecución no determinista), custom component duplicado.
> - `registerCustomComponent('x')` duplicado → el **perdedor** pasa a `'x_pf<hash4>'` (literal JS + JSON de `blocks/items/entities` de ese addon). No se toca más lógica JS.
> - Limitaciones: mezcla de mayores/beta de `@minecraft/server` (ver §3) y estado compartido en tiempo de ejecución no se pueden “arreglar” automáticamente.

## 10. Carga, prioridad y duplicados

- En la lista de packs activos del mundo, un RP situado **más arriba** tiene prioridad sobre los inferiores *(verificar el orden real en tu versión: lectura del JSON vs interfaz)*.
- **Archivos** con la misma ruta: gana el de mayor prioridad (el otro se ignora, no se mezcla). **Definiciones** con el mismo identifier: gana una sola; **no hay fusión profunda automática**.
- Se **combinan por clave** entre packs: `terrain_texture.json`, `item_texture.json`, `flipbook_textures.json`, `blocks.json`, `sound_definitions.json`, `sounds.json`, `music_definitions.json`, `.lang`, `_ui_defs.json`.
- La UI vanilla se modifica con `modifications` (`insert_back`, `insert_front`, `remove`, `replace`, `move_*`).
- Dos BPs que sobrescriben el mismo id vanilla (`minecraft:player`, `minecraft:zombie`): el último cargado **reemplaza**, no suma.

> 🔧 **En PackForge:** la fusión **copia en orden y el último sobrescribe**; la prioridad que el usuario fija en el Studio (y los ganadores elegidos en Conflictos) se traduce a orden (`AddonOrderResolver`) y a copias quirúrgicas finales (`ResolutionApplier`). Como PackForge entrega **un solo** RP y un solo BP, el “juego de prioridades entre packs” desaparece dentro del modpack: lo que decide es la lógica de fusión, no el orden de carga del juego.

## 11. Complejidades al fusionar varios add-ons — y estado en PackForge

Leyenda: ✅ implementado · ⚠️ parcial / con limitaciones · ❌ no soportado · ❓ no verificado al escribir este documento

| Complejidad | Qué ocurre en Bedrock | Estado en PackForge |
|---|---|---|
| Manifest nuevo coherente (UUIDs, versión, dependencias, `min_engine_version`, capabilities) | Un UUID repetido o una dependencia mal enlazada hace que el pack no cargue | ✅ `ManifestGenerator`; RP y BP negocian `min_engine_version` máximo y unen `capabilities` (`applyOriginalRpSettings`) |
| `format_version` distinta en la MISMA definición | El esquema elegido interpreta el contenido del otro addon (subirla no lo convierte) | ✅ `FormatVersionNegotiator` conserva la del último addon y reporta `FORMAT_VERSION_MISMATCH` (MEDIUM); nunca reescribe la versión |
| Dependencia por UUID hacia un pack que no va en el `.mcaddon` | Bedrock NO inicializa el pack: nada carga | ✅ se elimina la dependencia y se reporta (`EXTERNAL_DEPENDENCY_DROPPED`); las de `module_name` se conservan |
| Dos addons necesitan majors distintos de `@minecraft/server` | Un BP solo carga una API: los scripts del otro pueden morir | ⚠️ se reporta `SCRIPT_API_MAJOR_CONFLICT`; multi-BP (varios BP en un `.mcaddon`) **no implementado** |
| Variable global de UI repetida con otro valor | La pantalla del perdedor usa el valor ajeno | ✅ renombrado `$var_pf<hash6>` para el perdedor + reescritura de `"$var"` solo en su UI |
| Colisión de **ids persistentes** (entidad/bloque/ítem) | Uno pisa al otro; renombrar rompe mundos existentes | ✅ renombrado del perdedor `pf_<hash>`; ⚠️ afecta a mundos que usaban ese id |
| Overrides de **vanilla** (`minecraft:*`) por varios addons | El último reemplaza | ⚠️ fusión por componente; `player.entity.json` dedicado; eventos/filtros no siempre fusionables mecánicamente |
| Colisión de **recursos** (misma ruta, contenido distinto) | Gana uno | ✅ dedupe MD5 / alias `_pf<hex6>` + reescritura de referencias por fuente |
| Ids globales de recursos (geometría/animación/render controller) | Colisión silenciosa | ✅ `EntityDependencyResolver` (alias por hash) + `DuplicateIdentifierAuditor` (red de seguridad) |
| Catálogos que Bedrock **combina por clave** | Claves repetidas siguen prioridad | ✅ mergers dedicados (terrain/item/blocks/flipbook/sound*/lang/ui…) |
| Reescritura de referencias tras renombrar | Un id olvidado = entidad invisible/ítem sin textura | ✅ `JsonValueRewriter` única fuente; ⚠️ ids construidos o escritos en strings de scripts solo se detectan por texto |
| Molang y variables compartidas | `variable.x` con significados distintos se corrompen | ❓ no hay renombrado automático de variables Molang entre addons |
| Geometrías: UV atados al tamaño de textura | Reempaquetar rompe el modelo | ✅ no se reempaquetan texturas de entidad |
| Geometrías legacy con herencia `:` | Padre en otro pack | ❓ |
| Packs **cifrados** (Marketplace, `contents.json` con claves) | Ilegibles | ❌ no fusionables; ❓ confirmar mensaje de rechazo claro |
| Addons “closed-source” (JSON real oculto, claves mezcladas) | Parece vacío/roto | ⚠️ `BedrockDecifrator`: recupera lo recuperable, **no adivina** claves |
| Scripts: versiones de `@minecraft/*` incompatibles | Error de carga | ⚠️ máximo numérico; sin chequeo de mayores/beta |
| Scripts: estado compartido (dynamic properties, eventos) | Comportamiento contradictorio | ⚠️ solo advertencia |
| UI JSON (HUD, pantallas) | Un addon pisa los widgets de otro | ✅ `BedrockUiMerger` (`modifications`/`controls`) |
| Subpacks | Variantes de RP; si el manifiesto fusionado no las declara, el juego nunca carga esas carpetas | ✅ `SubpackRegistry` planifica carpetas (renombra colisiones con `_pf<hash6>`) y `ManifestGenerator` las conserva en `header.subpacks`; colisiones de `memory_tier` y entradas sin `folder_name` se reportan |
| Mayúsculas/minúsculas en rutas (Android/Linux distinguen; Windows no) | Funciona en PC, falla en móvil | ❓ revisar al reescribir rutas |
| Rendimiento y RAM en móvil | Swap y cuelgues | ✅ límite de paralelismo, `DirIndexCache`, separación sin copia |
| Verificación antes de exportar | Errores solo visibles al abrir el mundo | ✅ Healer + Validator + `MergeReferenceValidator` + gate + semáforo |

### Estrategia por tipo de archivo (referencia rápida)

| Archivo | Estrategia | Riesgo típico |
|---|---|---|
| `manifest.json` | Generar nuevo | UUIDs, dependencias |
| `texts/*.lang` | Unir por clave | Claves duplicadas: gana la última |
| `terrain_texture.json`, `item_texture.json` | Mezclar `texture_data` por clave | Claves repetidas; espacios invisibles en claves (bloques “?”) |
| `flipbook_textures.json`, `texture_list.json` | Concatenar / unión | Referencias a `atlas_tile` |
| `blocks.json` | Mezclar por id | Ids vanilla sobrescritos |
| `sound_definitions.json`, `sounds.json`, `music_definitions.json` | Mezclar por id | Rutas de audio renombradas |
| `ui/_ui_defs.json` / `_global_variables.json` / pantallas | Unión / detectar colisión de valor / combinar `modifications` | Mismo `$var` con valores distintos |

> 🔧 **Sobre la clave raíz de un fichero de `ui/`:** aquí la raíz **es** el mapa de elementos del `namespace`, no `format_version` ni `minecraft:*`. Un archivo puede ser un override de un elemento sin `namespace` (`{"blast_furnace_screen":{"$is_blast_furnace_screen":true}}`) o una ruta de modificación (`{"beacon_panel/root_panel":{"modifications":[{"array_name":"controls","operation":"insert_front",…}]}}`). `MergeReportGenerator` los acepta como válidos **solo bajo una carpeta `ui/`** y **solo si todas** las claves tienen forma de elemento (TICKET-CORE-013): antes los marcaba como «cifrado por sustitución» y afirmaba que el juego los ignoraría, un falso positivo medido en **77** ficheros de `Desse-UI`.
| `functions/tick.json` | Unión de `values` | Orden de ejecución |
| `entities/*.json` (BP) | Por identifier; deep-merge solo en overrides vanilla | Eventos y filtros |
| `animations`, `animation_controllers`, `render_controllers`, `particles`, `geo.json` | Copiar; resolver colisión de ids | Referencias cruzadas |
| `png`, `tga`, `ogg`, `mcstructure` | Copiar; colisión → dedupe o alias | Reescribir **todas** las referencias |
| `contents.json` | Descartar y regenerar | Packs cifrados |

### Políticas de resolución (qué se puede automatizar)

1. **Prioridad** (primero/último gana; el resto se descarta **con aviso**).
2. **Renombrar con prefijo** (`pf_…`/`_pf…`): solo objetos de **recursos**, o ids persistentes del **perdedor** asumiendo el impacto en mundos.
3. **Fusión profunda**: solo overrides de vanilla; **no** se puede fusionar mecánicamente: arreglos de filtros (`all_of`/`any_of`), transiciones, comportamientos con la misma `priority`, valores incompatibles del mismo componente (dos `minecraft:health`), eventos con orden de efectos.
4. **Detener y preguntar al usuario**: ids persistentes con ambigüedad real.

## 12. Errores comunes y diagnóstico

| Síntoma | Causa probable | Qué revisar |
|---|---|---|
| El pack no aparece | Manifest inválido, UUID duplicado, `version` no es arreglo, carpeta anidada de más, JSON roto | `manifest.json`, estructura |
| Textura magenta/negra | Ruta mal escrita, mayúsculas, clave inexistente en `terrain_texture.json` | Rutas y claves |
| Entidad invisible | `geometry`/alias no coincide, render controller sin textura, BP sin RP, geometría ausente o duplicada | Entidad cliente + render controller + `.geo.json` |
| Entidad en pose T | Animaciones no declaradas en `scripts.animate` | Entidad cliente |
| Bloque sin textura / cubo estándar | `material_instances` sin `*`, clave corta incorrecta | Bloque + `terrain_texture.json` |
| Nombre `item.ns:id.name` | Falta clave `.lang` o RP inactivo | `texts/en_US.lang` |
| Scripts no corren | Dependencia inválida, API beta sin experimento, `entry` mal, error de sintaxis | Manifest, Registro de contenido |
| “Dependency not found” | RP no instalado o versión menor | `dependencies` |
| Funciona en Windows, falla en Android | Mayúsculas en rutas | Rutas |
| Un mundo pierde bloques/entidades | Se renombró un id persistente | Ids originales |
| Sonido no suena | Ruta con extensión o mal escrita | `sound_definitions.json` |
| Doble comportamiento | Dos packs tocan la misma entidad | Prioridad / fusión |

Herramienta clave dentro del juego: **Ajustes → Creador → Registro de contenido** (activar GUI y archivo) *(verificar la ruta del menú en tu versión)*.

> ⚠️ **Los marcadores de fallo son INFORMACIÓN, no ruido.** El damero magenta/negro (y el `?` de textura ausente) no es un crash: Bedrock está diciendo **qué** falta y **dónde**. Taparlo generando una textura neutra deja al mob **invisible**, que es más difícil de diagnosticar que el propio marcador. PackForge ya cometió ese error con la geometría —73 cajas 8×8×8 generadas encima de modelos vanilla, que dejaron mobs sin render— y por eso su política actual es: **no** se generan stubs de textura ni de animación; una referencia colgante se **quita** en vez de inventarse un fichero dummy. Ver `AGENTS.md` §5 regla 7.

## 13. Checklist de validación de un modpack fusionado

- UUIDs únicos (header y módulos); `version` y `modules[].version` = arreglos de 3 enteros.
- BP→RP apunta al UUID del **header** del RP; `min_engine_version` = máximo.
- Cada `identifier` cliente tiene su contraparte BP (o es huérfano de origen documentado).
- Cada geometría, animación, controlador, render controller, partícula y material referenciado existe **o es vanilla**.
- Cada textura referenciada existe con ruta exacta (sin extensión en JSON; con extensión en disco).
- Cada clave corta de bloque/ítem está en `terrain_texture.json`/`item_texture.json`; cada clave `.lang` tiene su id.
- `scripts/` tiene el `entry` y las dependencias de módulos son compatibles; cada `custom_component` está registrado por un script.
- Ningún identificador definido dos veces; ningún id vanilla sobrescrito por un placeholder.
- Todo archivo omitido/renombrado/reparado aparece en `fusion_report.txt`.
- Probado en un mundo nuevo y en uno existente con el Registro de contenido activo.

## 14. Notas Android (afectan al diseño del motor)

- Android 11+ restringe `Android/data`: usar SAF/`DocumentFile` o carpetas elegidas por el usuario; no asumir rutas absolutas.
- Entregar el resultado con `FileProvider` + `Intent ACTION_VIEW` hacia Minecraft (`com.mojang.minecraftpe`), que importa el `.mcaddon`.
- Procesar en streaming y con tope de paralelismo; no cargar packs completos en RAM.
- Las rutas de ZIP y de Android distinguen mayúsculas: normalizar para **comparar**, sin cambiar el texto original que Bedrock leerá.
- Considerar `memory_tier` de subpacks y texturas grandes al validar.

## 15. Fuentes y alcance

Este documento resume el estado general de los formatos hasta Bedrock 1.21.x y el comportamiento de PackForge `1.9.1`. Para versiones posteriores o esquemas exactos de componentes: documentación oficial de Microsoft (`learn.microsoft.com/minecraft/creator`), `bedrock.dev` y `Mojang/bedrock-samples`. Antes de automatizar una regla de fusión, **verifícala con un pack de prueba en la versión del juego objetivo** y añade el caso como test de regresión.
