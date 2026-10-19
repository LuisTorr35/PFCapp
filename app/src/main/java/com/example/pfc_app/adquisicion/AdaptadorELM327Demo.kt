package com.example.pfc_app.adquisicion

import android.content.Context
import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.procesamiento.FuenteArchivos
import com.example.pfc_app.procesamiento.LectorCsvAtencion
import com.example.pfc_app.procesamiento.ReglaMuestras
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Reproduce de forma acelerada una atención grabada. Con [simularRechazo] la primera
 * captura de R2 se corta antes de las 45 muestras útiles, para mostrar el dictamen
 * rechazado; al repetir R2 se reproduce completo.
 *
 * Cada régimen se reproduce con TODAS sus filas y su instante original, esperando
 * Δt / [aceleracion] entre filas, y el flujo termina con la grabación. Así la fila
 * de características de la demostración es exactamente la de la grabación.
 */
class AdaptadorELM327Demo(
    private val fuente: FuenteArchivos,
    val grabacion: GrabacionDemo,
    private val simularRechazo: Boolean = false,
    private val aceleracion: Double = 20.0
) : AdaptadorELM327 {

    /** Constructor del teléfono: los archivos están en assets/demo/<id>/. */
    constructor(
        contexto: Context,
        grabacion: GrabacionDemo,
        simularRechazo: Boolean = false,
        aceleracion: Double = 20.0
    ) : this(
        FuenteArchivosAssets(contexto.applicationContext, "${CatalogoDemoAssets.CARPETA}/${grabacion.id}"),
        grabacion,
        simularRechazo,
        aceleracion
    )

    override val esDemostracion: Boolean = true

    private val _estadoEnlace = MutableStateFlow(EstadoEnlace.DESCONECTADO)
    override val estadoEnlace: StateFlow<EstadoEnlace> = _estadoEnlace.asStateFlow()

    private val lector = LectorCsvAtencion(fuente)
    private val lecturas: Map<TipoRegimen, List<Lectura>> by lazy {
        TipoRegimen.entries.mapNotNull { t -> lector.leerLecturas(grabacion.id, t)?.let { t to it } }.toMap()
    }
    private var reproduccionesR2 = 0
    private var ultimoReproducido: TipoRegimen? = null

    /** true si la grabación trae ese régimen. */
    fun tieneRegimen(tipo: TipoRegimen): Boolean = lecturas[tipo]?.isNotEmpty() == true

    override suspend fun conectar(): ResultadoConexion {
        _estadoEnlace.value = EstadoEnlace.CONECTANDO
        Registro.i(Registro.CAPTURA, "Demostración ${grabacion.id}: conectando con el adaptador simulado")
        delay(PAUSA_CONEXION_MS)
        if (!tieneRegimen(TipoRegimen.R2) && !tieneRegimen(TipoRegimen.R3)) {
            _estadoEnlace.value = EstadoEnlace.DESCONECTADO
            val msg = "La grabación ${grabacion.id} no trae regímenes en $fuente"
            Registro.e(Registro.CAPTURA, msg)
            return ResultadoConexion(exito = false, mensajeError = msg)
        }
        val protocolo = lector.protocolo(grabacion.id) ?: grabacion.protocolo
        val familia = lector.familiaGestion(grabacion.id) ?: grabacion.familiaGestion
        _estadoEnlace.value = EstadoEnlace.CONECTADO
        Registro.i(
            Registro.CAPTURA,
            "Demostración ${grabacion.id} conectada: protocolo $protocolo, familia ${familia?.texto}, " +
                "regímenes ${lecturas.keys.joinToString()}" + if (simularRechazo) ", simula el rechazo de R2" else ""
        )
        return ResultadoConexion(exito = true, protocolo = protocolo, familiaGestion = familia)
    }

    override suspend fun leerCodigos(): List<CodigoDTC> = lector.leerCodigos(grabacion.id)

    override suspend fun leerContadoresModo06(): List<ContadorModo06> = lector.leerModo06(grabacion.id)

    override suspend fun leerBarometrica(): Double? = lector.barometrica(grabacion.id)

    override suspend fun leerMapContacto(): Double? = lector.mapContacto(grabacion.id)

    /**
     * Antes de reproducir nada, el refrigerante inicial: el primero de R1 si la
     * grabación lo trae (motor frío) o, si no, el primero de R2 (motor caliente).
     * Después, el primero del régimen que sigue al último reproducido.
     */
    override suspend fun leerTemperaturaRefrigerante(): Double? {
        val ect = when (ultimoReproducido) {
            null -> primerRefrigerante(TipoRegimen.R1) ?: primerRefrigerante(TipoRegimen.R2)
            TipoRegimen.R1 -> primerRefrigerante(TipoRegimen.R2)
            TipoRegimen.R2, TipoRegimen.R3 -> primerRefrigerante(TipoRegimen.R3) ?: primerRefrigerante(TipoRegimen.R2)
        }
        Registro.d(Registro.CAPTURA, "Demostración ${grabacion.id}: refrigerante $ect °C")
        return ect
    }

    override fun leerParametros(regimen: TipoRegimen): Flow<Lectura> = flow {
        val filas = lecturas[regimen].orEmpty()
        val cortar = simularRechazo && regimen == TipoRegimen.R2 && reproduccionesR2 == 0
        if (regimen == TipoRegimen.R2) reproduccionesR2++
        ultimoReproducido = regimen
        Registro.i(
            Registro.CAPTURA,
            "Demostración ${grabacion.id}: reproduciendo $regimen, ${filas.size} filas a ${aceleracion}x" +
                if (cortar) ", cortado a $UTILES_CON_RECHAZO útiles para simular el rechazo" else ""
        )
        val t0 = filas.firstOrNull { !it.instante.isNaN() }?.instante ?: 0.0
        var utiles = 0
        for ((i, l) in filas.withIndex()) {
            if (i > 0) {
                val dt = l.instante - filas[i - 1].instante
                if (dt > 0 && dt.isFinite()) delay((dt * 1000.0 / aceleracion).toLong())
            }
            emit(l)
            if (cortar) {
                val rpm = l.rpm
                val esUtil = l.instante - t0 >= ReglaMuestras.SEGUNDOS_DESCARTE &&
                    (!ReglaMuestras.aplicaTecho(regimen) || rpm == null || rpm <= ReglaMuestras.TECHO_RALENTI_RPM)
                if (esUtil) utiles++
                if (utiles >= UTILES_CON_RECHAZO) {
                    Registro.i(Registro.CAPTURA, "Demostración: R2 cortado en la fila ${i + 1} de ${filas.size}")
                    break
                }
            }
        }
    }

    override suspend fun reconectar(): Boolean {
        _estadoEnlace.value = EstadoEnlace.CONECTADO
        return true
    }

    override suspend fun cerrar() {
        _estadoEnlace.value = EstadoEnlace.DESCONECTADO
        Registro.i(Registro.CAPTURA, "Demostración ${grabacion.id}: enlace cerrado")
    }

    private fun primerRefrigerante(tipo: TipoRegimen): Double? = lecturas[tipo]?.firstNotNullOfOrNull { it.tempRefrigerante }

    companion object {
        /** Útiles que deja el R2 cortado de la demostración con rechazo (por debajo de 45). */
        const val UTILES_CON_RECHAZO = 31
        private const val PAUSA_CONEXION_MS = 800L
    }
}

/** Fuente de una grabación del catálogo, para construir el adaptador sin Context (pruebas). */
fun CatalogoDemoArchivos.adaptador(
    grabacion: GrabacionDemo,
    simularRechazo: Boolean = false,
    aceleracion: Double = 20.0
): AdaptadorELM327Demo = AdaptadorELM327Demo(archivos(grabacion), grabacion, simularRechazo, aceleracion)
