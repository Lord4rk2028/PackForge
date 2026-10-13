import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Reproduce el fallo real de empaquetado: "Minecraft detecta el BP como ZIP
 * inválido". Verificado por forensia sobre el .mcaddon generado (Test 1):
 * las 130 entradas tenían el bit 3 (data descriptor) activo y el CRC/tamaño
 * en 0 dentro de la cabecera local.
 */
class ZipDescriptorTest {

    private fun tmp(name: String): File {
        val d = Files.createTempDirectory("pf_zip_$name").toFile()
        d.deleteOnExit()
        return d
    }

    private fun write(zos: ZipOutputStream, name: String, data: ByteArray, declareSize: Boolean) {
        // ⭐ CLAVE (verificado empíricamente): ZipOutputStream SOLO omite el
        // data descriptor cuando la entrada es STORED y lleva size+CRC
        // declarados. Con DEFLATED el bit 3 se activa siempre, aunque declares
        // size y CRC. Ese bit es lo que hacía que Bedrock rechazase el pack.
        if (declareSize) {
            val crc = CRC32().apply { update(data) }.value
            val e = ZipEntry(name).apply {
                method = ZipEntry.STORED
                setSize(data.size.toLong())
                setCrc(crc)
            }
            zos.putNextEntry(e)
            zos.write(data)
        } else {
            zos.putNextEntry(ZipEntry(name))
            zos.write(data)
        }
        zos.closeEntry()
    }

    /** Bit 3 (0x0008) de la cabecera local: indica "data descriptor". */
    private fun hasDataDescriptorBit(bytes: ByteArray, entryName: String): Boolean {
        val nameBytes = entryName.toByteArray()
        var i = 0
        while (i < bytes.size - 30) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0x04.toByte()
            ) {
                val nameLen = (bytes[i + 26].toInt() and 0xFF) or ((bytes[i + 27].toInt() and 0xFF) shl 8)
                val current = String(bytes, i + 30, nameLen)
                if (current == entryName) {
                    val flags = (bytes[i + 6].toInt() and 0xFF) or ((bytes[i + 7].toInt() and 0xFF) shl 8)
                    return (flags and 0x0008) != 0
                }
                i += 30 + nameLen
            } else {
                i++
            }
        }
        error("No se encontró la entrada $entryName")
    }

    @Test
    fun sinDeclararSize_activaElDataDescriptor() {
        val dir = tmp("sin")
        val out = File(dir, "a.mcaddon")
        ZipOutputStream(BufferedOutputStream(out.outputStream(), 65536)).use { zos ->
            write(zos, "BP_PackForge/manifest.json", "{}".toByteArray(), declareSize = false)
        }
        assertTrue(
            "Escritura en streaming: Bedrock rechaza este ZIP",
            hasDataDescriptorBit(out.readBytes(), "BP_PackForge/manifest.json")
        )
    }

    @Test
    fun declarandoSizeYcrc_seEliminaElDataDescriptor() {
        val dir = tmp("con")
        val out = File(dir, "a.mcaddon")
        val payload = """{"format_version":2}""".toByteArray()
        ZipOutputStream(BufferedOutputStream(out.outputStream(), 65536)).use { zos ->
            write(zos, "BP_PackForge/manifest.json", payload, declareSize = true)
        }
        val bytes = out.readBytes()
        assertTrue(
            "Con size+CRC declarados NO debe haber data descriptor",
            !hasDataDescriptorBit(bytes, "BP_PackForge/manifest.json")
        )
    }

    /** Diagnóstico: qué combinación consigue realmente quitar el bit 3. */
    @Test
    fun diagnosticarCombinaciones() {
        val dir = tmp("diag")
        val payload = """{"format_version":2}""".toByteArray()
        val crc = CRC32().apply { update(payload) }.value

        // A) setSize + setCrc sobre entrada DEFLATED
        val a = File(dir, "a.mcaddon")
        ZipOutputStream(BufferedOutputStream(a.outputStream(), 65536)).use { zos ->
            val e = ZipEntry("BP_PackForge/manifest.json").apply {
                setSize(payload.size.toLong()); setCrc(crc)
            }
            zos.putNextEntry(e); zos.write(payload); zos.closeEntry()
        }
        println("A) DEFLATED size+crc -> bit3=" + hasDataDescriptorBit(a.readBytes(), "BP_PackForge/manifest.json"))

        // B) method = STORED con size+crc
        val b = File(dir, "b.mcaddon")
        ZipOutputStream(BufferedOutputStream(b.outputStream(), 65536)).use { zos ->
            val e = ZipEntry("BP_PackForge/manifest.json").apply {
                method = ZipEntry.STORED
                setSize(payload.size.toLong()); setCrc(crc)
            }
            zos.putNextEntry(e); zos.write(payload); zos.closeEntry()
        }
        println("B) STORED size+crc -> bit3=" + hasDataDescriptorBit(b.readBytes(), "BP_PackForge/manifest.json"))
        ZipFile(b).use { z ->
            val en = z.getEntry("BP_PackForge/manifest.json")
            println("B) legible, size=" + en.size + " crc=" + en.crc)
        }
    }
}
