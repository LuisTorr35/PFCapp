package com.example.pfc_app.adquisicion

import android.content.Context
import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.procesamiento.FuenteArchivos
import org.json.JSONObject

/** Una atención grabada que el modo demostración puede reproducir. */
data class GrabacionDemo(
    val id: String,
    /** Texto para el selector, por ejemplo "ATN-037 · Hyundai Tucson 2011". */
    val descripcion: String,
    val marca: String,
    val modelo: String,
    val anio: Int,
    val cilindros: Int,
    /** Protocolo con que se grabó, por ejemplo "ISO 15765-4 (CAN 11/500)". */
    val protocolo: String? = null,
    val familiaGestion: FamiliaGestion? = null,
    /** Clase confirmada por el mecánico en la ficha (C0 a C3), solo como referencia del presentador. */
    val claseConfirmada: String? = null,
    /** Qué muestra esta grabación, por ejemplo "con R1 en frío" o "sin diagnóstico (regla 8)". */
    val nota: String = "",
    /** true si la grabación trae R1 (el vehículo llegó frío). */
    val tieneR1: Boolean = false,
)

interface CatalogoDemo {
    fun grabaciones(): List<GrabacionDemo>
}

/**
 * Lee catalogo.json de una fuente de archivos cuya raíz es la carpeta demo/: en el
 * teléfono, los assets; en las pruebas de JVM, src/main/assets/demo del disco.
 */
class CatalogoDemoArchivos(private val fuente: FuenteArchivos) : CatalogoDemo {
    override fun grabaciones(): List<GrabacionDemo> {
        val texto = fuente.leerTexto(ARCHIVO) ?: run {
            Registro.w(Registro.CAPTURA, "No hay $ARCHIVO en la carpeta de demostración")
            return emptyList()
        }
        val lista = JSONObject(texto).getJSONArray("grabaciones")
        return (0 until lista.length()).map { i ->
            val g = lista.getJSONObject(i)
            GrabacionDemo(
                id = g.getString("id"),
                descripcion = g.getString("descripcion"),
                marca = g.getString("marca"),
                modelo = g.getString("modelo"),
                anio = g.getInt("anio"),
                cilindros = g.getInt("cilindros"),
                protocolo = g.optString("protocolo").takeIf { it.isNotEmpty() },
                familiaGestion = FamiliaGestion.desdeTexto(g.optString("familia_gestion").takeIf { it.isNotEmpty() }),
                claseConfirmada = g.optString("clase_confirmada").takeIf { it.isNotEmpty() },
                nota = g.optString("nota"),
                tieneR1 = g.optBoolean("tiene_r1", false),
            )
        }
    }

    /** Fuente con los archivos de una grabación (demo/ATN-###/). */
    fun archivos(grabacion: GrabacionDemo): FuenteArchivos = fuente.subcarpeta(grabacion.id)

    companion object {
        const val ARCHIVO = "catalogo.json"
    }
}

/** Lee el catálogo de grabaciones incluidas en assets/demo. */
class CatalogoDemoAssets(private val contexto: Context) : CatalogoDemo {
    private val catalogo by lazy {
        CatalogoDemoArchivos(FuenteArchivosAssets(contexto.applicationContext, CARPETA))
    }

    override fun grabaciones(): List<GrabacionDemo> = catalogo.grabaciones()

    companion object {
        const val CARPETA = "demo"
    }
}
