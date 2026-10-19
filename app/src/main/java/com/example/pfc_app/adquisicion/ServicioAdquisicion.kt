package com.example.pfc_app.adquisicion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.modelos.Vehiculo
import kotlinx.coroutines.flow.StateFlow

enum class EstadoRegimen(val texto: String) {
    PENDIENTE("Pendiente"),
    CAPTURANDO("Capturando"),
    CAPTURADO("Capturado"),
    OMITIDO("No capturado"),
}

data class ProgresoRegimen(
    val tipo: TipoRegimen,
    val estado: EstadoRegimen = EstadoRegimen.PENDIENTE,
    val muestrasUtiles: Int = 0,
    val objetivo: Int = 45,
    val rpm: Double? = null,
    val ect: Double? = null,
    /** Por qué no se capturó, por ejemplo "el motor llegó caliente" en R1. */
    val motivoOmision: String? = null,
)

/** Lo que la pantalla de Captura muestra mientras avanza la atención. */
data class EstadoCaptura(
    val modoDemostracion: Boolean = false,
    val enlace: EstadoEnlace = EstadoEnlace.DESCONECTADO,
    val protocolo: String? = null,
    val familiaGestion: FamiliaGestion? = null,
    val lecturaInicialHecha: Boolean = false,
    val codigos: List<CodigoDTC> = emptyList(),
    val contadoresModo06: List<ContadorModo06> = emptyList(),
    val baroKpa: Double? = null,
    val regimenes: Map<TipoRegimen, ProgresoRegimen> =
        TipoRegimen.entries.associateWith { ProgresoRegimen(it) },
    /** Instrucción para el mecánico, por ejemplo "Arranque el motor y déjelo en ralentí". */
    val instruccion: String? = null,
    /** true si la captura espera a que el mecánico pulse "Continuar" ([ServicioAdquisicion.confirmar]). */
    val esperaConfirmacion: Boolean = false,
    /** Aviso pasajero, por ejemplo "Enlace caído, reconectando...". */
    val mensaje: String? = null,
    /** Error que detuvo la captura (CU-03, excepción del paso 2). */
    val error: String? = null,
    /** La captura de todos los regímenes terminó. */
    val terminada: Boolean = false,
)

/**
 * Orquesta la captura de una atención según CU-03 (paquete adquisicion del Capítulo 5).
 */
interface ServicioAdquisicion {
    val estado: StateFlow<EstadoCaptura>

    /**
     * Ejecuta la atención completa: conexión (CU-02), lectura inicial (códigos, modo 06 y
     * barométrica), R1 si el motor llegó frío, R2 y R3. Actualiza [estado] mientras avanza
     * y devuelve la atención capturada, todavía sin validar ni guardar. Si el enlace cae
     * durante un régimen, reconecta solo y conserva los regímenes ya capturados (RNF-01).
     * Lanza una excepción con mensaje legible si no logra conectar.
     */
    suspend fun capturarAtencion(vehiculo: Vehiculo, adaptador: AdaptadorELM327): Atencion

    /** Descarta las lecturas del régimen indicado y lo vuelve a capturar (CU-08). */
    suspend fun repetirRegimen(tipo: TipoRegimen): Atencion

    /** El mecánico confirma la instrucción que está en pantalla. */
    fun confirmar()

    /** Cierra el enlace con el adaptador. */
    suspend fun finalizar()
}
