package com.example.pfc_app.ui

import com.example.pfc_app.datos.FechasIso
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.Direccion
import com.example.pfc_app.modelos.EfectoRegla
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.ParametroDesviado
import com.example.pfc_app.modelos.TipoRegimen
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Textos que ve el mecánico: coma decimal, unidades y frases de las maquetas. */
object Formatos {
    private val simbolos = DecimalFormatSymbols(Locale.ROOT).apply {
        decimalSeparator = ','
        minusSign = '-'
    }
    private val FECHA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Número con coma decimal, por ejemplo 14,9. */
    fun numero(valor: Double, decimales: Int = 1): String {
        val patron = if (decimales <= 0) "0" else "0." + "0".repeat(decimales)
        return DecimalFormat(patron, simbolos).apply {
            isGroupingUsed = false
            roundingMode = RoundingMode.HALF_UP
        }.format(valor)
    }

    /** Sin decimales desde 100 (rpm), con uno por debajo (ajustes, cargas). */
    fun numeroLegible(valor: Double): String = if (abs(valor) >= 100) numero(valor, 0) else numero(valor, 1)

    fun conUnidad(texto: String, unidad: String): String = if (unidad.isBlank()) texto else "$texto $unidad"

    /** Probabilidad entre 0 y 1 como "72 %". */
    fun porcentaje(probabilidad: Double): String = "${(probabilidad * 100).roundToInt()} %"

    /** "28/09/2026 · 11:20"; si la fecha no se entiende, el texto tal cual. */
    fun fechaHora(iso: String): String {
        val f = FechasIso.interpretar(iso) ?: return iso
        return "${f.format(FECHA)} · ${f.format(HORA)}"
    }

    fun cilindros(n: Int): String = if (n == 1) "1 cilindro" else "$n cilindros"

    /** Familia corta para la lista de vehículos: "MAP", "MAF", "MAF y MAP" o "familia pendiente". */
    fun familiaCorta(f: FamiliaGestion?): String = when (f) {
        null -> "familia pendiente"
        FamiliaGestion.AMBOS -> "MAF y MAP"
        else -> f.texto
    }

    /** Familia larga para el historial: "presión del múltiple (MAP)". */
    fun familiaLarga(f: FamiliaGestion?): String = f?.descripcion ?: "familia de gestión pendiente"

    /** "P0303 almacenado, P0171 pendiente" o "ninguno". */
    fun codigos(codigos: List<CodigoDTC>): String =
        if (codigos.isEmpty()) "ninguno" else codigos.joinToString(", ") { "${it.codigo} ${it.tipo.texto}" }

    /**
     * Fallos de encendido del modo 06 por cilindro (conteo del ciclo actual): "cilindro 3, 12",
     * "sin fallos" si todos son 0, "no disponible" si el vehículo no expone los contadores.
     */
    fun fallosModo06(contadores: List<ContadorModo06>): String {
        val conDato = contadores.filter { it.conteoActual != null }
        if (conDato.isEmpty()) return "no disponible"
        val conFallos = conDato.filter { (it.conteoActual ?: 0) > 0 }.sortedBy { it.cilindro }
        if (conFallos.isEmpty()) return "sin fallos"
        return conFallos.joinToString("; ") { "cilindro ${it.cilindro}, ${it.conteoActual}" }
    }

    /** "↑ Mayor que sin falla (14,9 frente a 3,5 rpm)". */
    fun lineaParametro(p: ParametroDesviado): String {
        val sentido = if (p.direccion == Direccion.MAYOR) "Mayor" else "Menor"
        val valores = if (p.referencia.isNaN()) {
            conUnidad(numeroLegible(p.valor), p.unidad)
        } else {
            conUnidad("${numeroLegible(p.valor)} frente a ${numeroLegible(p.referencia)}", p.unidad)
        }
        return "${p.direccion.flecha} $sentido que sin falla ($valores)"
    }

    /** Los códigos leídos como contexto del diagnóstico, o "Ninguno". */
    fun codigosDiagnostico(codigos: List<CodigoDTC>): String {
        if (codigos.isEmpty()) return "Ninguno"
        val lista = codigos.joinToString(", ") { "${it.codigo} ${it.tipo.texto}" }
        return if (codigos.size == 1) {
            "$lista. Se muestra como contexto, no como fundamento de la clasificación."
        } else {
            "$lista. Se muestran como contexto, no como fundamento de la clasificación."
        }
    }

    /** Motivo del dictamen: su observación o, si viene vacía, los motivos de las reglas incumplidas. */
    fun motivoDictamen(d: Dictamen, efecto: EfectoRegla? = null): String {
        if (d.observacion.isNotBlank()) return d.observacion
        return d.reglasIncumplidas
            .filter { efecto == null || it.efecto == efecto }
            .map { it.motivo.ifBlank { it.descripcion } }
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /** "Repita solo R2; R1 y R3 se conservan." según los regímenes capturados. */
    fun indicacionRepeticion(aRepetir: List<TipoRegimen>, capturados: Collection<TipoRegimen>): String {
        if (aRepetir.isEmpty()) return "Vuelva a la pantalla Vehículos y repita la atención."
        val repetir = enumerar(aRepetir.sorted().map { it.name })
        val conservados = capturados.filter { it !in aRepetir }.sorted().map { it.name }
        return when (conservados.size) {
            0 -> "Repita solo $repetir."
            1 -> "Repita solo $repetir; ${conservados[0]} se conserva."
            else -> "Repita solo $repetir; ${enumerar(conservados)} se conservan."
        }
    }

    private fun enumerar(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " y " + items.last()
    }

    fun resultadoTitulo(texto: String): String = texto.replaceFirstChar { it.uppercase() }
}
