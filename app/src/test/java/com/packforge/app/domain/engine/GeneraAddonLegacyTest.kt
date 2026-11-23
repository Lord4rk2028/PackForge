package com.packforge.app.domain.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ════════════════════════════════════════════════════════════════════════
 * GENERADOR DEL ADDON DE PRUEBA (TICKET-CORE-010)
 * ════════════════════════════════════════════════════════════════════════
 *
 * Crea un `.mcaddon` mínimo con DOS bloques que solo se diferencian en cómo
 * nombran sus componentes, para averiguar EN EL JUEGO si la traducción del
 * esquema antiguo es necesaria o no:
 *
 *   · `pf:legacy_test` — `format_version` moderno (1.20.50) pero componentes
 *     con nombre ANTIGUO: `destroy_time`, `explosion_resistance`,
 *     `block_light_absorption`.  Hipótesis: NO se carga.
 *
 *   · `pf:modern_test` — el CONTROL, mismo `format_version` y mismos valores,
 *     con los nombres modernos (`destructible_by_mining`,
 *     `destructible_by_explosion`, `light_dampening`). Debe cargarse.
 *
 * Si el control aparece en el inventario creativo y el legacy no, la
 * traducción está justificada y se puede conectar con base. Si aparecen los
 * dos (o ninguno), el componente se queda como diagnóstico y NO se reescribe
 * nada.
 *
 * El ZIP se escribe con entradas STORED de tamaño y CRC declarados: es lo
 * único que evita el *data descriptor*, que el lector estricto de Bedrock
 * rechaza (ver `PackForgeOrchestrator.createMcAddon`).
 */
class GeneraAddonLegacyTest {

    /** Sube desde el working dir hasta la raíz del proyecto. */
    private fun projectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, ".research").isDirectory } ?: cwd
    }

    @Test
    fun generaElAddonDePrueba() {
        val root = projectRoot()
        val outDir = File(root, ".research/schema_legacy_test").apply { mkdirs() }

        // Textura: se reutiliza un PNG real de un addon del repo para no
        // inventar un binario.
        val gravestone = File(root, ".research/addons_3/simple-gravestone 1.1.0.mcaddon")
        assumeTrue("falta el addon con la textura: ${gravestone.path}", gravestone.isFile)
        val png: ByteArray = ZipFile(gravestone).use { z ->
            val e = z.entries().toList().firstOrNull {
                !it.isDirectory && it.name.matches(Regex(".*textures/.*/gravestone\\.png"))
            }
            assumeTrue("no hay gravestone.png en el addon de origen", e != null)
            z.getInputStream(e).use { it.readBytes() }
        }
        assumeTrue("la textura extraída está vacía", png.isNotEmpty())

        val uuid = { java.util.UUID.randomUUID().toString() }
        val bp = uuid(); val bpMod = uuid(); val rp = uuid(); val rpMod = uuid()

        val bpManifest = """
            {"format_version":2,"header":{"name":"PF Legacy Schema Test BP",
            "description":"Bloque moderno vs bloque con nombres antiguos",
            "uuid":"$bp","version":[1,0,0],"min_engine_version":[1,21,50]},
            "modules":[{"type":"data","uuid":"$bpMod","version":[1,0,0]}],
            "dependencies":[{"uuid":"$rp","version":[1,0,0]}]}
        """.trimIndent()

        val rpManifest = """
            {"format_version":2,"header":{"name":"PF Legacy Schema Test RP",
            "description":"Textura del bloque de prueba",
            "uuid":"$rp","version":[1,0,0],"min_engine_version":[1,21,50]},
            "modules":[{"type":"resources","uuid":"$rpMod","version":[1,0,0]}]}
        """.trimIndent()

        val legacyBlock = """
            {"format_version":"1.20.50","minecraft:block":{
            "description":{"identifier":"pf:legacy_test","menu_category":{"category":"construction"}},
            "components":{
            "minecraft:material_instances":{"*":{"texture":"pf_legacy_test","render_method":"opaque"}},
            "minecraft:destroy_time":1.0,
            "minecraft:explosion_resistance":3.0,
            "minecraft:block_light_absorption":15}}}
        """.trimIndent()

        val modernBlock = """
            {"format_version":"1.20.50","minecraft:block":{
            "description":{"identifier":"pf:modern_test","menu_category":{"category":"construction"}},
            "components":{
            "minecraft:material_instances":{"*":{"texture":"pf_legacy_test","render_method":"opaque"}},
            "minecraft:destructible_by_mining":{"seconds_to_destroy":1.0},
            "minecraft:destructible_by_explosion":{"explosion_resistance":3.0},
            "minecraft:light_dampening":15}}}
        """.trimIndent()

        val terrainTexture = """
            {"resource_pack_name":"pf_legacy_test","texture_name":"atlas.terrain",
            "padding":8,"num_mip_levels":4,
            "texture_data":{"pf_legacy_test":{"textures":"textures/blocks/pf_legacy_test"}}}
        """.trimIndent()

        val out = File(outDir, "PF Legacy Schema Test.mcaddon")
        out.delete()

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(path: String, texto: String) = putBytes(zip, path, texto.toByteArray(Charsets.UTF_8))
            put("PF_LegacyBP/manifest.json", bpManifest)
            put("PF_LegacyBP/blocks/pf_legacy_test.json", legacyBlock)
            put("PF_LegacyBP/blocks/pf_modern_test.json", modernBlock)
            put("PF_LegacyRP/manifest.json", rpManifest)
            put("PF_LegacyRP/textures/terrain_texture.json", terrainTexture)
            putBytes(zip, "PF_LegacyRP/textures/blocks/pf_legacy_test.png", png)
        }

        println("GEN | addon generado: ${out.absolutePath} (${out.length()} bytes)")
        ZipFile(out).use { z ->
            z.entries().toList().forEach { println("GEN |   ${it.size}  ${it.name}") }
        }
    }

    /** Entrada STORED con tamaño y CRC declarados: sin data descriptor. */
    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }.value
        val entry = ZipEntry(path).apply {
            method = ZipEntry.STORED
            setSize(bytes.size.toLong())
            setCrc(crc)
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }
}