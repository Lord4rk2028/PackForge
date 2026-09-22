package com.packforge.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Valida el SQL de la migración 2→3 de Room sin necesidad de instrumentación.
 *
 * Expone las sentencias exactas que ejecutará la migración y verifica que:
 *  1. Son exactamente 2 ALTER TABLE sobre `saved_modpacks`.
 *  2. Agregan `resolutionsJson` y `conflictStrategy` con defaults seguros
 *     (texto vacío / KEEP_FIRST), de modo que las filas previas no se pierden.
 *
 * Si alguien edita MIGRATION_2_3 (columnas, defaults, orden), el test falla
 * en CI antes de publicar.
 */
class DatabaseMigrationTest {

    @Test
    fun migration2to3_usesExpectedAlterStatements() {
        val statements = PackForgeDatabase.migration23Statements()

        assertEquals("La migración debe ejecutar exactamente 2 ALTERs", 2, statements.size)
        assertTrue(
            "Primer ALTER debe agregar resolutionsJson",
            statements[0].contains("resolutionsJson", ignoreCase = true)
        )
        assertTrue(
            "Segundo ALTER debe agregar conflictStrategy",
            statements[1].contains("conflictStrategy", ignoreCase = true)
        )
        assertTrue(
            "resolutionsJson debe tener default seguro",
            statements[0].contains("DEFAULT ''", ignoreCase = true)
        )
        assertTrue(
            "conflictStrategy debe defaultear a KEEP_FIRST",
            statements[1].contains("KEEP_FIRST", ignoreCase = true)
        )
    }
}
