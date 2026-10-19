package com.example.pfc_app.procesamiento

import java.text.Normalizer

/**
 * mapear_columnas() de consolidar_lecturas.py: renombra los encabezados de un CSV
 * exportado a los nombres canónicos. Primero busca la coincidencia exacta con el
 * nombre canónico y, si no la hay, la palabra clave más corta. Se conserva el
 * diccionario completo y su orden, porque una clave temprana puede quedarse con
 * una columna que de otro modo tomaría una posterior.
 */
object MapaColumnas {

    private val MAPA: List<Pair<String, List<String>>> = listOf(
        "t_seg" to listOf("time", "tiempo", "seconds", "segundos", "timestamp"),
        "rpm" to listOf("engine rpm", "revoluciones", "rpm"),
        "carga_calc_pct" to listOf("calculated engine load", "carga calculada", "engine load"),
        "carga_abs_pct" to listOf("absolute load value", "carga absoluta", "absolute load"),
        "map_kpa" to listOf(
            "intake manifold absolute pressure",
            "presion absoluta del multiple",
            "intake manifold pressure",
            "map"
        ),
        "maf_gps" to listOf("mass air flow", "flujo de masa de aire", "maf"),
        "iat_c" to listOf("intake air temperature", "temperatura del aire de admision", "iat"),
        "ect_c" to listOf("engine coolant temperature", "temperatura del refrigerante", "coolant"),
        "stft_b1_pct" to listOf(
            "short term fuel trim bank 1",
            "ajuste de combustible a corto plazo",
            "short term fuel",
            "stft"
        ),
        "ltft_b1_pct" to listOf(
            "long term fuel trim bank 1",
            "ajuste de combustible a largo plazo",
            "long term fuel",
            "ltft"
        ),
        "lambda_eq" to listOf("commanded equivalence ratio", "relacion de equivalencia", "lambda"),
        "o2_arriba_v" to listOf("o2 arriba", "o2 s1 wr voltage", "o2 b1s1", "oxygen sensor 1", "sensor de oxigeno 1"),
        "o2_abajo_v" to listOf("o2 abajo", "o2 s2 wr voltage", "o2 b1s2", "oxygen sensor 2", "sensor de oxigeno 2"),
        "estado_comb" to listOf("estado comb", "fuel system status", "fuel status", "estado del sistema de combustible"),
        "stft_b2_pct" to listOf("short term fuel trim bank 2", "stft b2"),
        "ltft_b2_pct" to listOf("long term fuel trim bank 2", "ltft b2"),
        "o2_v" to listOf("oxygen sensor", "sensor de oxigeno", "o2 sensor"),
        "avance_grados" to listOf("timing advance", "avance de encendido", "ignition timing"),
        "tps_pct" to listOf("throttle position", "posicion del acelerador", "tps"),
        "baro_kpa" to listOf("barometric", "barometrica", "baro"),
        "volt_modulo" to listOf("control module voltage", "voltaje del modulo", "module voltage"),
        "vel_kmh" to listOf("vehicle speed", "velocidad", "speed"),
        "presion_comb_kpa" to listOf("presion comb", "fuel pressure"),
        "presion_riel_kpa" to listOf("presion riel kpa", "fuel rail pressure"),
        "presion_riel_dir_kpa" to listOf("presion riel dir", "fuel rail pressure direct"),
        "inyeccion_grados" to listOf("inyeccion grados", "fuel inject timing"),
        "consumo_lph" to listOf("consumo lph", "fuel rate"),
        "egr_cmd_pct" to listOf("egr cmd", "commanded egr"),
        "egr_error_pct" to listOf("egr error"),
        "purga_evap_pct" to listOf("purga evap", "evaporative purge"),
        "cat_b1s1_c" to listOf("cat b1s1", "catalyst temp b1s1"),
        "cat_b1s2_c" to listOf("cat b1s2", "catalyst temp b1s2"),
        "amb_c" to listOf("amb c", "ambient air", "ambiant air"),
        "aceite_c" to listOf("aceite c", "oil temp"),
        "tiempo_marcha_s" to listOf("tiempo marcha", "run time"),
        "tipo_comb" to listOf("tipo comb", "fuel type"),
        "version_protocolo" to listOf("version protocolo"),
        "presion_riel_vac_kpa" to listOf("presion riel vac")
    )

    private val PARENTESIS = Regex("\\([^)]*\\)")
    private val MARCAS = Regex("\\p{M}+")
    private val NO_ALFANUMERICO = Regex("[^a-z0-9 ]+")
    private val ESPACIOS = Regex("\\s+")

    /** normalizar(): minúsculas, sin tildes, sin unidades entre paréntesis, espacios simples. */
    fun normalizar(texto: String): String {
        var t = PARENTESIS.replace(texto, " ")
        t = MARCAS.replace(Normalizer.normalize(t, Normalizer.Form.NFKD), "")
        t = NO_ALFANUMERICO.replace(t.lowercase(), " ")
        return ESPACIOS.replace(t, " ").trim()
    }

    /** Nombre canónico → índice de la columna del CSV que le corresponde. */
    fun mapear(encabezados: List<String>): Map<String, Int> {
        // {normalizar(c): c}: con encabezados repetidos gana el último, como en el dict de Python
        val originales = LinkedHashMap<String, Int>()
        for ((i, e) in encabezados.withIndex()) originales[normalizar(e)] = i
        val usadas = HashSet<Int>()
        val salida = LinkedHashMap<String, Int>()
        for ((canonico, claves) in MAPA) {
            val canN = normalizar(canonico)
            var elegida: Int? = originales.entries.firstOrNull { (n, i) -> i !in usadas && n == canN }?.value
            if (elegida == null) {
                for (clave in claves) {
                    val claveN = normalizar(clave)
                    val candidatas = originales.entries.filter { (n, i) -> claveN in n && i !in usadas }.map { it.value }
                    if (candidatas.isNotEmpty()) {
                        // la coincidencia más corta suele ser la columna correcta (min de Python: la primera)
                        elegida = candidatas.minBy { encabezados[it].length }
                        break
                    }
                }
            }
            if (elegida != null) {
                salida[canonico] = elegida
                usadas += elegida
            }
        }
        return salida
    }

    private val NUMERO = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
    private val NO_NUMERICO = Regex("[^0-9.\\-eE]")

    /** Valores que pd.read_csv toma como vacíos. */
    private val VACIOS_PANDAS = setOf(
        "", "#N/A", "#N/A N/A", "#NA", "-1.#IND", "-1.#QNAN", "-NaN", "-nan", "1.#IND", "1.#QNAN",
        "<NA>", "N/A", "NA", "NULL", "NaN", "None", "n/a", "nan", "null"
    )

    /** a_numerico(): número tolerando coma decimal y texto residual; null si no es número. */
    fun aNumero(celda: String?): Double? {
        if (celda == null) return null
        val s = celda.trim()
        if (s in VACIOS_PANDAS) return null
        if (NUMERO.matches(s)) return s.toDouble()
        val limpia = NO_NUMERICO.replace(s.replace(",", "."), "")
        if (limpia.isEmpty() || limpia == "." || limpia == "-") return null
        return limpia.toDoubleOrNull()
    }
}
