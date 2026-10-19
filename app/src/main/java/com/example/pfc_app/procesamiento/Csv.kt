package com.example.pfc_app.procesamiento

/** Un CSV leído: encabezados y filas, todo como texto. */
class TablaCsv(val encabezados: List<String>, val filas: List<List<String>>) {
    private val indices: Map<String, Int> =
        encabezados.withIndex().associate { (i, n) -> n to i }

    fun indice(nombre: String): Int? = indices[nombre]

    fun valor(fila: List<String>, nombre: String): String? = indices[nombre]?.let { fila.getOrNull(it) }

    fun comoMapas(): List<Map<String, String>> =
        filas.map { f -> encabezados.withIndex().associate { (i, n) -> n to (f.getOrNull(i) ?: "") } }

    val vacia: Boolean get() = filas.isEmpty()
}

/**
 * Lector de CSV mínimo para los archivos de registrar_obd.py: UTF-8 con BOM,
 * campos entre comillas con comas dentro (estado_comb) y saltos de línea de
 * Windows. Detecta el separador como pd.read_csv(sep=None) en los casos que
 * aparecen en la práctica: coma, punto y coma o tabulador.
 */
object Csv {
    fun leer(texto: String): TablaCsv {
        val limpio = texto.removePrefix("\uFEFF")
        val separador = detectarSeparador(limpio)
        val registros = registros(limpio, separador).filter { r -> r.any { it.isNotBlank() } }
        if (registros.isEmpty()) return TablaCsv(emptyList(), emptyList())
        val encabezados = registros.first().map { it.trim() }
        return TablaCsv(encabezados, registros.drop(1))
    }

    private fun detectarSeparador(texto: String): Char {
        val primera = texto.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        var enComillas = false
        val cuenta = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        for (c in primera) {
            if (c == '"') {
                enComillas = !enComillas
            } else if (!enComillas && c in cuenta) {
                cuenta[c] = cuenta.getValue(c) + 1
            }
        }
        return cuenta.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    private fun registros(texto: String, sep: Char): List<List<String>> {
        val salida = ArrayList<List<String>>()
        var campos = ArrayList<String>()
        val campo = StringBuilder()
        var enComillas = false
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            if (enComillas) {
                if (c == '"') {
                    if (i + 1 < texto.length && texto[i + 1] == '"') {
                        campo.append('"')
                        i++
                    } else {
                        enComillas = false
                    }
                } else {
                    campo.append(c)
                }
            } else {
                when (c) {
                    '"' -> enComillas = true

                    sep -> {
                        campos.add(campo.toString())
                        campo.setLength(0)
                    }

                    '\r' -> Unit

                    '\n' -> {
                        campos.add(campo.toString())
                        campo.setLength(0)
                        salida.add(campos)
                        campos = ArrayList()
                    }

                    else -> campo.append(c)
                }
            }
            i++
        }
        if (campo.isNotEmpty() || campos.isNotEmpty()) {
            campos.add(campo.toString())
            salida.add(campos)
        }
        return salida
    }
}
