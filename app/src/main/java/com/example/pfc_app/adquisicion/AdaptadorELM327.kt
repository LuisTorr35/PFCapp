package com.example.pfc_app.adquisicion

import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.TipoRegimen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class EstadoEnlace(val texto: String) {
    DESCONECTADO("Desconectado"),
    CONECTANDO("Conectando"),
    CONECTADO("Conectado"),
    RECONECTANDO("Reconectando"),
}

data class ResultadoConexion(
    val exito: Boolean,
    /** Protocolo del vehículo tal como lo describe el ELM327, por ejemplo "ISO 15765-4 (CAN 11/500)". */
    val protocolo: String? = null,
    /** Familia de gestión según los PID que contestan (MAF 0x10, MAP 0x0B). */
    val familiaGestion: FamiliaGestion? = null,
    /** Motivo legible si no se pudo conectar (CU-02, excepciones de los pasos 1 y 4). */
    val mensajeError: String? = null,
)

/**
 * Comunicación con el adaptador ELM327 (paquete datos del Capítulo 5, figura de clases).
 * Dos implementaciones: [AdaptadorELM327Bluetooth], por el perfil de puerto serie del
 * Bluetooth clásico, y [AdaptadorELM327Demo], que reproduce atenciones grabadas.
 */
interface AdaptadorELM327 {
    /** true en la implementación de demostración: la pantalla de captura lo avisa. */
    val esDemostracion: Boolean

    val estadoEnlace: StateFlow<EstadoEnlace>

    /** Abre el enlace, configura el ELM327, detecta el protocolo y consulta los PID soportados (CU-02). */
    suspend fun conectar(): ResultadoConexion

    /** Códigos almacenados (modo 03) y pendientes (modo 07). */
    suspend fun leerCodigos(): List<CodigoDTC>

    /** Contadores de fallo de encendido por cilindro del modo 06. Lista vacía si el vehículo no los expone. */
    suspend fun leerContadoresModo06(): List<ContadorModo06>

    /** Presión barométrica de la sesión (PID 0x33, o la del múltiple con el motor apagado). */
    suspend fun leerBarometrica(): Double?

    /** Temperatura del refrigerante en este momento (PID 0x05), para decidir R1 y R2. */
    suspend fun leerTemperaturaRefrigerante(): Double?

    /**
     * Emite las lecturas del régimen mientras se recolecta. [Lectura.instante] cuenta desde
     * el inicio del régimen. En la demostración el flujo termina al acabar la grabación;
     * en el adaptador real sigue hasta que se cancela.
     */
    fun leerParametros(regimen: TipoRegimen): Flow<Lectura>

    /** Restablece el enlace tras una caída (RNF-01). */
    suspend fun reconectar(): Boolean

    suspend fun cerrar()
}
