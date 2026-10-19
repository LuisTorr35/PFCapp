package com.example.pfc_app

import com.example.pfc_app.procesamiento.Csv
import com.example.pfc_app.procesamiento.FuenteArchivosCarpeta
import com.example.pfc_app.procesamiento.LectorCsvAtencion
import com.example.pfc_app.procesamiento.MapaColumnas
import java.io.File

/** Rutas de los recursos de las pruebas de JVM (el directorio de trabajo es el del módulo app). */
object Recursos {
    val CRUDO = File("src/test/resources/crudo")
    val CONSOLIDADOR = File("src/test/resources/consolidador/caracteristicas_nucleo.csv")
    val CASOS_INFERENCIA = File("src/test/resources/modelo/casos_prueba_inferencia.csv")
    val MODELO = File("src/main/assets/modelo")
    val DEMO = File("src/main/assets/demo")

    /** Las 25 atenciones del corte, por clase: C0, C1, C2 y C3. */
    val CORTE = listOf(
        "ATN-001", "ATN-006", "ATN-007", "ATN-009", "ATN-013", "ATN-021", "ATN-026", "ATN-033", "ATN-034",
        "ATN-005", "ATN-010", "ATN-014", "ATN-022", "ATN-037",
        "ATN-002", "ATN-015", "ATN-020", "ATN-023", "ATN-028",
        "ATN-004", "ATN-016", "ATN-019", "ATN-024", "ATN-027", "ATN-035",
    )

    val lector: LectorCsvAtencion by lazy { LectorCsvAtencion(FuenteArchivosCarpeta(CRUDO)) }

    /** id_atencion → fila, con null donde la celda está vacía. */
    fun tablaConsolidador(): Map<String, Map<String, Double?>> {
        val t = Csv.leer(CONSOLIDADOR.readText())
        return t.comoMapas().associate { f ->
            f.getValue("id_atencion") to f.filterKeys { it != "id_atencion" }.mapValues { MapaColumnas.aNumero(it.value) }
        }
    }

    fun columnasConsolidador(): List<String> = Csv.leer(CONSOLIDADOR.readText()).encabezados.drop(1)
}
