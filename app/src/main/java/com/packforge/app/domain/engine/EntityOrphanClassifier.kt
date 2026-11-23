package com.packforge.app.domain.engine

import java.io.File

/**
 * ⭐ FASE 5 — ¿Una `client_entity` sin definición en BP es un DEFECTO o es
 * contenido muerto que el addon de origen ya traía así?
 *
 * El validador las sumaba todas en "entidades incompletas", y ese contador por
 * eso nunca podía llegar a 0. En el Better MC real quedaban 5, todas
 * `cc:*_market_villager`. Medido sobre `core-craft_v1.2.8.mcaddon` (9.405
 * entradas, escaneadas también `.js`, `.mcfunction`, `.mcstructure` y todo
 * binario): cada uno de esos 5 identificadores aparece en **un solo archivo de
 * todo el addon**, su propio archivo de la carpeta `entity`. Ni `spawn_rules`, ni `scripts`, ni
 * `structures`, ni un `/summon`, ni un `spawn_egg` los invoca — y 271 de las 271
 * `client_entity` restantes sí tienen su `minecraft:entity`.
 *
 * Conclusión: son huérfanas del ORIGEN. No aparecen nunca en el juego, ni
 * siquiera con el addon original instalado tal cual. La fusión no rompe nada y
 * no hay nada que reparar sin inventar un mob que el autor no escribió.
 *
 * ── Por qué NO hay un catálogo de entidades vanilla ──────────────────────
 *
 * La tentación es mantener una lista de "entidades que da el juego" y usarla
 * para no marcar falsos positivos. Es una trampa: cada actualización de
 * Minecraft añade mobs (armadillo, breeze, bogged, camel, sniffer, creaking,
 * happy_ghast…) y la lista habría que actualizarla para siempre. Este mismo
 * archivo ya tiene escrito ese epitafio (`VANILLA_GEOMETRY_PREFIXES`: "SIEMPRE
 * se quedan cortas… produjo ~1.000 errores falsos"), y `VanillaAssets` tuvo que
 * ganar un catálogo remoto para dejar de mantener prefijos a mano.
 *
 * Aquí no hace falta: "¿esto será un mob de verdad?" se responde mirando si el
 * propio pack lo usa. Es una propiedad del pack, no del juego, así que es
 * estable frente a cualquier versión y cualquier addon.
 *
 * ── Las tres situaciones que distingue ───────────────────────────────────
 *
 * | definición en BP | alguien la nombra | significado                        |
 * |------------------|-------------------|------------------------------------|
 * | sí               | —                 | correcta                           |
 * | no               | sí                | 🔴 el mob no aparecerá: defecto real |
 * | no               | no                | ⚪ huérfana del origen: se ignora    |
 */
internal object EntityOrphanClassifier {

    /**
     * Extensiones donde un identificador de entidad puede aparecer como texto.
     *
     * Se excluyen imágenes y sonidos a propósito: ahí un identificador sólo
     * "aparece" por casualidad entre bytes comprimidos, y eso convertiría una
     * huérfana en un falso defecto.
     */
    private val REF_EXTENSIONS = listOf(
        ".json", ".js", ".mcfunction", ".material", ".txt", ".lang", ".mcstructure"
    )

    /** Bloque de lectura. */
    private const val CHUNK = 64 * 1024

    /**
     * Tope por archivo para no recorrer un volcado disfrazado de `.txt`.
     *
     * El escaneo es en streaming, así que el tamaño no afecta a la memoria: este
     * límite sólo evita gastar tiempo en contenido que no es del addon.
     */
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024

    /** Carpetas internas del pipeline cuyos contenidos no son del autor. */
    private val IGNORED_DIRS = setOf(".shadow", "_packforge", ".bak", ".git")

    /**
     * ⭐ TICKET-CORE-004 — aguja de búsqueda: el identificador tal cual.
     *
     * Se probó a exigir comillas alrededor (`"minecraft:bee"`), pero el
     * contrato del escáner es más amplio: también localiza el id pegado a otro
     * texto (lo fija `Fase5HuerfanasOrigenTest`, "se detecta aunque caiga entre
     * dos bloques"), y un script puede construirlo por concatenación. La
     * exclusión de falsos positivos vive en el LLAMADOR, que es quien decide
     * qué entidades son vanillas y por tanto no necesitan definición en el BP
     * (`PackForgeValidator`, ver TICKET-CORE-004 en `entity`).
     */
    private fun agujaDe(id: String): ByteArray = id.toByteArray(Charsets.UTF_8)

    /**
     * Resuelve VARIAS entidades en **una sola pasada** por el BP.
     *
     * Abrir el árbol una vez por candidata multiplica por N el coste de E/S, y
     * en Android/Windows ese coste lo domina el antivirus por apertura, no los
     * bytes leídos. Aquí cada archivo se abre una vez y se comprueban todas las
     * agujas pendientes sobre el mismo contenido; en cuanto una aparece, se
     * retira de la lista (no hay que buscarla más).
     *
     * @return sólo las entidades citadas, `identificador → ruta relativa`.
     *         Las que no aparezcan son huérfanas del origen.
     */
    fun findReferences(bpDir: File?, identifiers: Collection<String>): Map<String, String> {
        if (bpDir == null || identifiers.isEmpty()) return emptyMap()
        if (!bpDir.isDirectory) return emptyMap()

        val pendientes = LinkedHashMap<String, ByteArray>()
        for (id in identifiers) {
            if (id.isNotBlank()) pendientes[id] = agujaDe(id)
        }
        if (pendientes.isEmpty()) return emptyMap()

        val hallados = HashMap<String, String>()
        val root = bpDir.absoluteFile
        val maxLen = pendientes.values.maxOf { it.size }

        // UN búfer para TODOS los archivos, reutilizado en cada bloque.
        //
        // La primera versión de esto leía cada archivo entero con `readBytes()`
        // para probar las 5 agujas en memoria: 6.400 archivos × hasta 4 MB son
        // decenas de GB de basura para el GC, y la fusión pasó de 15 a más de 40
        // minutos con el proceso leyendo a 0,3 MB/s y la CPU parada. El búfer
        // único con solape no asigna nada por archivo.
        val buf = ByteArray(CHUNK + maxLen)
        try {
            for (f in root.walkTopDown().onEnter { dir -> dir.name !in IGNORED_DIRS }) {
                if (pendientes.isEmpty()) break
                if (!f.isFile) continue
                if (f.length() !in 1..MAX_FILE_BYTES) continue
                if (REF_EXTENSIONS.none { f.name.endsWith(it, ignoreCase = true) }) continue

                escanear(
                    file = f,
                    rel = f.relativeTo(root).invariantSeparatorsPath,
                    buf = buf,
                    maxLen = maxLen,
                    pendientes = pendientes,
                    hallados = hallados
                )
            }
        } catch (e: Exception) {
            // No bloquear la fusión: se devuelve lo hallado hasta el momento.
        }
        return hallados
    }

    /**
     * Busca todas las agujas [pendientes] dentro de [file] y las mueve a
     * [hallados] en cuanto aparecen.
     *
     * Un solo `FileInputStream` por archivo: eso es lo que evita pagar el filtro
     * del antivirus una vez por candidata (5 aperturas × 6.400 archivos eran 11
     * minutos de la fusión). El solape de `maxLen - 1` bytes entre bloques hace
     * que una aguja se detecte aunque caiga partida en dos lecturas.
     */
    private fun escanear(
        file: File,
        rel: String,
        buf: ByteArray,
        maxLen: Int,
        pendientes: MutableMap<String, ByteArray>,
        hallados: MutableMap<String, String>
    ) {
        try {
            java.io.FileInputStream(file).use { input ->
                var carry = 0
                while (pendientes.isNotEmpty()) {
                    val read = input.read(buf, carry, CHUNK)
                    if (read <= 0) break
                    val len = carry + read

                    val it = pendientes.entries.iterator()
                    while (it.hasNext()) {
                        val aguja = it.next()
                        if (indexOfBytes(buf, len, aguja.value) >= 0) {
                            hallados[aguja.key] = rel
                            it.remove()
                        }
                    }
                    if (pendientes.isEmpty()) return

                    // Arrastra el final del bloque al principio del siguiente.
                    carry = minOf(maxLen - 1, len)
                    if (carry > 0) System.arraycopy(buf, len - carry, buf, 0, carry)
                }
            }
        } catch (e: Exception) {
            // Archivo ilegible o bloqueado: se ignora, como hacía el recorrido anterior.
        }
    }

    /**
     * Atajo de [findReferences] para una sola entidad.
     *
     * @return ruta relativa a [bpDir] (con `/`) del primer archivo que la cita,
     *         o `null` si ninguno lo hace → huérfana del origen.
     */
    fun findReference(bpDir: File?, identifier: String?): String? {
        if (bpDir == null || identifier.isNullOrBlank()) return null
        if (!bpDir.isDirectory) return null
        return findReferences(bpDir, listOf(identifier))[identifier]
    }

    /**
     * Búsqueda directa: la aguja son ~25 bytes, no compensa nada más elaborado.
     *
     * [hayLen] acota la zona válida de [hay] evitando copiar el búfer para
     * recortarlo en cada bloque.
     */
    private fun indexOfBytes(hay: ByteArray, hayLen: Int, needle: ByteArray): Int {
        if (hayLen < needle.size) return -1
        var i = 0
        while (i <= hayLen - needle.size) {
            var j = 0
            while (j < needle.size && hay[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }
}
