package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests de [SchemaNormalizer].
 *
 * La mitad son REGRESIONES: fijan que no vuelvan los renombrados destructivos
 * que tenía la primera versión. Cada uno cita la evidencia que lo motivó,
 * medida sobre `bedrock-samples` (contenido oficial de Mojang).
 */
class SchemaNormalizerTest {

    private fun normalize(json: JSONObject, target: List<Int> = listOf(1, 21, 50)) =
        SchemaNormalizer.normalize(json, "test_addon", target)

    // ── Regresiones: renombrados destructivos que NO deben volver ────────────

    /**
     * `minecraft:collision_box` es un componente válido y masivamente usado:
     * aparece en 126 de 130 entidades oficiales. `minecraft:collider`, su
     * supuesto reemplazo, aparece en 0. Renombrarlo deja a la entidad sin caja
     * de colisión.
     */
    @Test
    fun collisionBoxNoSeRenombra() {
        val json = JSONObject().apply {
            put("format_version", "1.20.50")
            put("minecraft:entity", JSONObject().apply {
                put("description", JSONObject().put("identifier", "custom:mob"))
                put("components", JSONObject().apply {
                    put("minecraft:collision_box", JSONObject().apply {
                        put("width", 0.6)
                        put("height", 1.8)
                    })
                })
            })
        }

        val components = normalize(json).normalizedJson
            .getJSONObject("minecraft:entity").getJSONObject("components")

        assertTrue("collision_box debe conservarse", components.has("minecraft:collision_box"))
        assertFalse("collider no existe en contenido oficial", components.has("minecraft:collider"))
    }

    /**
     * `minecraft:movement` aparece en 93 de 130 entidades oficiales;
     * `minecraft:movement.general` en 0 (en Bedrock `movement.*` es espacio de
     * consultas Molang, no un componente).
     */
    @Test
    fun movementNoSeRenombra() {
        val json = JSONObject().apply {
            put("format_version", "1.20.50")
            put("minecraft:entity", JSONObject().apply {
                put("description", JSONObject().put("identifier", "custom:mob"))
                put("components", JSONObject().apply {
                    put("minecraft:movement", JSONObject().put("value", 0.25))
                })
            })
        }

        val components = normalize(json).normalizedJson
            .getJSONObject("minecraft:entity").getJSONObject("components")

        assertTrue("movement debe conservarse", components.has("minecraft:movement"))
        assertFalse(
            "movement.general no es un componente de entidad",
            components.has("minecraft:movement.general")
        )
    }

    /**
     * `minecraft:damage` (7 de 82 ítems oficiales) declara la DURABILIDAD del
     * ítem; `minecraft:weapon` (0 apariciones oficiales) describe eventos al
     * golpear. No son equivalentes: el renombrado borraba el componente real y
     * añadía campos inventados.
     */
    @Test
    fun damageNoSeConvierteEnWeapon() {
        val json = JSONObject().apply {
            put("format_version", "1.20.50")
            put("minecraft:item", JSONObject().apply {
                put("description", JSONObject().put("identifier", "custom:sword"))
                put("components", JSONObject().apply {
                    put("minecraft:damage", 250)
                })
            })
        }

        val components = normalize(json).normalizedJson
            .getJSONObject("minecraft:item").getJSONObject("components")

        assertTrue("damage (durabilidad) debe conservarse", components.has("minecraft:damage"))
        assertEquals(250, components.getInt("minecraft:damage"))
        assertFalse("weapon no existe en ítems oficiales", components.has("minecraft:weapon"))
    }

    /**
     * `format_version` SELECCIONA el esquema; subirla no convierte el contenido,
     * solo lo reinterpreta bajo un esquema para el que no se escribió.
     */
    @Test
    fun formatVersionNoSeReescribe() {
        val json = JSONObject().apply {
            put("format_version", "1.16.0")
            put("minecraft:entity", JSONObject().apply {
                put("description", JSONObject().put("identifier", "custom:mob"))
                put("components", JSONObject())
            })
        }

        val ver = normalize(json).normalizedJson.getString("format_version")
        assertEquals("1.16.0", ver)
    }

    /**
     * Una variable Molang se DEFINE en el entity JSON y se LEE en las
     * animaciones, que son otros archivos. Reescribirla en uno solo desincroniza
     * la lectura: la animación se queda en su posición por defecto, en silencio.
     */
    @Test
    fun variablesMolangNoSePrefijan() {
        val expr = "query.is_sneaking ? v.custom_mana : variable.other_mana"
        val json = JSONObject().apply {
            put("format_version", "1.20.50")
            put("minecraft:entity", JSONObject().apply {
                put("description", JSONObject().apply {
                    put("identifier", "custom:entity")
                    put("scripts", JSONObject().put("animate", JSONArray(listOf(expr))))
                })
                put("components", JSONObject())
            })
        }

        val animate = normalize(json).normalizedJson
            .getJSONObject("minecraft:entity").getJSONObject("description")
            .getJSONObject("scripts").getJSONArray("animate")

        assertEquals("la expresión debe quedar intacta", expr, animate.getString(0))
        assertFalse(animate.getString(0).contains("pf_test_addon"))
    }

    // ── La única traducción verificada: componentes de bloque ────────────────

    /**
     * Los nombres modernos aparecen en 124 de 125 bloques oficiales y los
     * antiguos en 0. La estructura destino está copiada del contenido oficial:
     * el antiguo era un escalar y el moderno es un objeto, así que un simple
     * renombrado produciría un componente inválido.
     */
    @Test
    fun componentesDeBloqueAntiguosSeTraducenConEstructuraCorrecta() {
        val json = JSONObject().apply {
            put("format_version", "1.20.50")
            put("minecraft:block", JSONObject().apply {
                put("description", JSONObject().put("identifier", "custom:block"))
                put("components", JSONObject().apply {
                    put("minecraft:destroy_time", 1.8)
                    put("minecraft:explosion_resistance", 3.0)
                    put("minecraft:block_light_absorption", 15)
                })
            })
        }

        val result = normalize(json)
        val components = result.normalizedJson
            .getJSONObject("minecraft:block").getJSONObject("components")

        assertEquals(
            1.8,
            components.getJSONObject("minecraft:destructible_by_mining")
                .getDouble("seconds_to_destroy"),
            0.001
        )
        assertEquals(
            3.0,
            components.getJSONObject("minecraft:destructible_by_explosion")
                .getDouble("explosion_resistance"),
            0.001
        )
        assertEquals(15, components.getInt("minecraft:light_dampening"))

        assertFalse(components.has("minecraft:destroy_time"))
        assertFalse(components.has("minecraft:explosion_resistance"))
        assertFalse(components.has("minecraft:block_light_absorption"))
        assertEquals("debe reportar los 3 cambios", 3, result.changes.size)
    }

    /**
     * Por debajo de 1.19.80 el esquema antiguo es el VÁLIDO: el propio
     * `format_version` lo dice. Traducir ahí sería arreglar algo que no está roto.
     */
    @Test
    fun bloquesDelEsquemaAntiguoNoSeTocan() {
        val json = JSONObject().apply {
            put("format_version", "1.16.0")
            put("minecraft:block", JSONObject().apply {
                put("description", JSONObject().put("identifier", "custom:block"))
                put("components", JSONObject().apply {
                    put("minecraft:destroy_time", 1.8)
                })
            })
        }

        val result = normalize(json)
        val components = result.normalizedJson
            .getJSONObject("minecraft:block").getJSONObject("components")

        assertTrue(components.has("minecraft:destroy_time"))
        assertTrue("no debe haber cambios", result.changes.isEmpty())
    }

    /**
     * `block_light_emission` NO se traduce: ni ese nombre ni
     * `minecraft:light_emission` aparecen en los samples oficiales, así que no
     * hay evidencia de su reemplazo. Se reporta, no se reescribe.
     */
    @Test
    fun blockLightEmissionNoSeTraduceSinEvidencia() {
        val json = JSONObject().apply {
            put("format_version", "1.20.50")
            put("minecraft:block", JSONObject().apply {
                put("description", JSONObject().put("identifier", "custom:block"))
                put("components", JSONObject().apply {
                    put("minecraft:block_light_emission", 1.0)
                })
            })
        }

        val components = normalize(json).normalizedJson
            .getJSONObject("minecraft:block").getJSONObject("components")

        assertTrue(components.has("minecraft:block_light_emission"))
        assertFalse(components.has("minecraft:light_emission"))
    }

    /**
     * El pipeline calcula el objetivo a partir de los `manifest.json`, cuyo
     * `format_version` es 2 o 3 (versión del formato de manifiesto, no del
     * esquema). Ese número no debe gatear la traducción.
     */
    @Test
    fun objetivoDeManifiestoNoGateaLaTraduccion() {
        val json = JSONObject().apply {
            put("format_version", "1.20.50")
            put("minecraft:block", JSONObject().apply {
                put("description", JSONObject().put("identifier", "custom:block"))
                put("components", JSONObject().apply { put("minecraft:destroy_time", 2.0) })
            })
        }

        val result = normalize(json, target = listOf(2))
        val components = result.normalizedJson
            .getJSONObject("minecraft:block").getJSONObject("components")

        assertTrue(
            "un objetivo [2] no puede impedir la traducción",
            components.has("minecraft:destructible_by_mining")
        )
    }

    // ── Inspección (solo lectura) ────────────────────────────────────────────

    @Test
    fun inspectReportaEsquemaComponentesAntiguosYVariables() {
        val json = JSONObject().apply {
            put("format_version", JSONArray(listOf(1, 20, 50)))
            put("minecraft:block", JSONObject().apply {
                put("description", JSONObject().put("identifier", "custom:block"))
                put("components", JSONObject().apply {
                    put("minecraft:block_light_absorption", 15)
                })
            })
        }

        val inspection = SchemaNormalizer.inspect(json, "addon_x")

        assertEquals("addon_x", inspection.addonId)
        assertEquals(listOf(1, 20, 50), inspection.formatVersion)
        assertTrue(inspection.legacyBlockComponents.contains("minecraft:block_light_absorption"))
    }

    @Test
    fun inspectRecogeVariablesMolangPropias() {
        val json = JSONObject().apply {
            put("format_version", "1.20.50")
            put("minecraft:entity", JSONObject().apply {
                put("description", JSONObject().apply {
                    put("identifier", "custom:entity")
                    put("scripts", JSONObject().put(
                        "animate",
                        JSONArray(listOf("variable.hpi_y + v.hpi_x - query.health"))
                    ))
                })
                put("components", JSONObject())
            })
        }

        val vars = SchemaNormalizer.inspect(json, "jhi").customMolangVariables

        assertTrue(vars.contains("variable.hpi_y"))
        assertTrue(vars.contains("v.hpi_x"))
        assertFalse("query.* no es variable propia", vars.any { it.startsWith("query.") })
    }

    // ── Utilidades conservadas ───────────────────────────────────────────────

    @Test
    fun detectMaxFormatVersionEligeLaMayor() {
        val json1 = JSONObject().apply { put("format_version", "1.16.0") }
        val json2 = JSONObject().apply { put("format_version", JSONArray(listOf(1, 21, 50))) }
        val json3 = JSONObject().apply { put("format_version", "1.20.30") }

        assertEquals(
            listOf(1, 21, 50),
            SchemaNormalizer.detectMaxFormatVersion(listOf(json1, json2, json3))
        )
    }

    @Test
    fun compareSemverOrdenaCorrectamente() {
        assertTrue(SchemaNormalizer.compareSemver("1.21.50", "1.20.0") > 0)
        assertTrue(SchemaNormalizer.compareSemver("1.16.0", "1.20.50") < 0)
        assertEquals(0, SchemaNormalizer.compareSemver("1.20.0", "1.20.0"))
    }

    // ── Resumen de esquemas para el reporte (TICKET-CORE-010) ────────────────

    @Test
    fun summarizeSchemaSpreadDetectaMezclaYComponentesAntiguos() {
        val root = Files.createTempDirectory("pf_spread_").toFile()
        File(root, "entities").mkdirs()
        File(root, "entities/a.json").writeText(
            """{"format_version":"1.12.0","minecraft:entity":{"description":{"identifier":"a:mob"}}}"""
        )
        File(root, "entities/b.json").writeText(
            """{"format_version":"1.26.0","minecraft:entity":{"description":{"identifier":"b:mob"}}}"""
        )
        File(root, "blocks").mkdirs()
        File(root, "blocks/c.json").writeText(
            """{"format_version":"1.20.50","minecraft:block":{"components":{"minecraft:destroy_time":1.0}}}"""
        )

        val spread = SchemaNormalizer.summarizeSchemaSpread(root, "addonX")

        assertNotNull(spread)
        assertEquals("addonX", spread!!.addonName)
        assertEquals(3, spread.totalFiles)
        assertTrue("debe detectar que mezcla esquemas", spread.isMixed)
        assertEquals(listOf("1.12.0", "1.20.50", "1.26.0"), spread.versions)
        assertTrue(
            "debe detectar el componente de bloque antiguo",
            spread.legacyBlockComponents.contains("minecraft:destroy_time")
        )
        assertTrue(spread.describe().contains("MEZCLA"))
    }

    @Test
    fun summarizeSchemaSpreadNoCuentaManifiestos() {
        val root = Files.createTempDirectory("pf_spread_mf_").toFile()
        File(root, "entities").mkdirs()
        File(root, "entities/a.json").writeText(
            """{"format_version":"1.13.0","minecraft:entity":{"description":{"identifier":"a:mob"}}}"""
        )
        File(root, "manifest.json").writeText("""{"format_version":2}""")

        val spread = SchemaNormalizer.summarizeSchemaSpread(root, "sin_manifest")

        assertNotNull(spread)
        assertEquals("el manifiesto no es contenido", 1, spread!!.totalFiles)
        assertEquals(listOf("1.13.0"), spread.versions)
        assertFalse(spread.isMixed)
    }

    @Test
    fun summarizeSchemaSpreadDevuelveNullSinContenido() {
        val root = Files.createTempDirectory("pf_spread_vacio_").toFile()
        root.mkdirs()
        assertNull(SchemaNormalizer.summarizeSchemaSpread(root, "vacio"))
    }

    /**
     * La combinación que SÍ está rota: el bloque declara el esquema moderno y
     * usa nombres de componente que ese esquema ya no define. Debe reportarse.
     */
    @Test
    fun detectaBloqueConEsquemaModernoYComponentesAntiguos() {
        val root = Files.createTempDirectory("pf_roto_").toFile()
        File(root, "blocks").mkdirs()
        File(root, "blocks/roto.json").writeText(
            """{"format_version":"1.20.50","minecraft:block":{"description":{"identifier":"pf:roto"},
               "components":{"minecraft:destroy_time":1.0}}}"""
        )

        val spread = SchemaNormalizer.summarizeSchemaSpread(root, "addon_roto")

        assertNotNull(spread)
        assertTrue("debe marcar el bloque inconsistente", spread!!.hasInconsistentBlocks)
        assertEquals(listOf("blocks/roto.json"), spread.inconsistentBlocks)
        assertTrue(spread.describe().contains("combinación inválida"))
    }

    /**
     * El mismo componente en un archivo con esquema ANTIGUO es CORRECTO: ahí el
     * esquema declarado sí define `destroy_time`. No debe marcarse.
     */
    @Test
    fun noMarcaComponentesAntiguosSiElEsquemaTambienEsAntiguo() {
        val root = Files.createTempDirectory("pf_viejo_ok_").toFile()
        File(root, "blocks").mkdirs()
        File(root, "blocks/viejo.json").writeText(
            """{"format_version":"1.16.0","minecraft:block":{"description":{"identifier":"pf:viejo"},
               "components":{"minecraft:destroy_time":1.0}}}"""
        )

        val spread = SchemaNormalizer.summarizeSchemaSpread(root, "addon_viejo")

        assertNotNull(spread)
        assertFalse("esquema antiguo + nombres antiguos es válido", spread!!.hasInconsistentBlocks)
        assertTrue(spread.legacyBlockComponents.contains("minecraft:destroy_time"))
    }

    // ── Reparación en el pack fusionado (TICKET-CORE-011) ────────────────────

    /**
     * Traduce el bloque roto y deja intacto el que ya era correcto. Es la
     * reparación que se aplica al `.mcaddon` (verificado en juego: sin ella el
     * bloque no se carga).
     */
    @Test
    fun normalizeBlockFilesTraduceSoloElBloqueIncompatible() {
        val root = Files.createTempDirectory("pf_repair_").toFile()
        File(root, "roto.json").writeText(
            """{"format_version":"1.20.50","minecraft:block":{"description":{"identifier":"pf:roto"},
               "components":{"minecraft:destroy_time":1.8}}}"""
        )
        File(root, "viejo.json").writeText(
            """{"format_version":"1.16.0","minecraft:block":{"description":{"identifier":"pf:viejo"},
               "components":{"minecraft:destroy_time":1.8}}}"""
        )

        val cambios = SchemaNormalizer.normalizeBlockFiles(root, "test")

        assertEquals("solo el bloque incompatible debe cambiar", 1, cambios.size)
        assertTrue(cambios.first().startsWith("roto.json"))

        val reparado = JSONObject(File(root, "roto.json").readText())
            .getJSONObject("minecraft:block").getJSONObject("components")
        assertTrue(reparado.has("minecraft:destructible_by_mining"))
        assertFalse(reparado.has("minecraft:destroy_time"))
        assertEquals(
            1.8,
            reparado.getJSONObject("minecraft:destructible_by_mining").getDouble("seconds_to_destroy"),
            0.001
        )

        val intacto = JSONObject(File(root, "viejo.json").readText())
        assertEquals(
            "el esquema antiguo no se reescribe",
            "1.16.0",
            JSONObject(File(root, "viejo.json").readText()).getString("format_version")
        )
        assertTrue(
            intacto.getJSONObject("minecraft:block").getJSONObject("components")
                .has("minecraft:destroy_time")
        )
    }
}
