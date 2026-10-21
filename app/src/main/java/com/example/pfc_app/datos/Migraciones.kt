package com.example.pfc_app.datos

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.pfc_app.Registro

/**
 * Migraciones de la base local. Desde la versión 2 (la primera con Room) ninguna es
 * destructiva: las atenciones guardadas en el teléfono se conservan.
 */
object Migraciones {
    /**
     * 2 → 3: respaldo en la nube. La atención guarda si ya se respaldó, cuándo, y el uid de la
     * cuenta con que se guardó, porque la base es del teléfono y puede usarla más de una cuenta.
     * Las atenciones existentes quedan sin respaldar y sin cuenta (uid_respaldo NULL).
     */
    val MIGRACION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE atencion ADD COLUMN respaldada INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE atencion ADD COLUMN respaldada_en TEXT")
            db.execSQL("ALTER TABLE atencion ADD COLUMN uid_respaldo TEXT")
            Registro.i(Registro.DATOS, "Migración 2 → 3: atencion con respaldada, respaldada_en y uid_respaldo")
        }
    }

    val TODAS: Array<Migration> = arrayOf(MIGRACION_2_3)
}
