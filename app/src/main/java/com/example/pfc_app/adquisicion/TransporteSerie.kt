package com.example.pfc_app.adquisicion

import java.io.IOException

/**
 * El canal de bytes con el ELM327, separado del protocolo para poder probar el
 * protocolo en la JVM con un transporte falso que devuelve respuestas grabadas.
 * La implementación del teléfono es [TransporteBluetooth] (RFCOMM, perfil SPP).
 */
interface TransporteSerie {
    /** Para el registro, por ejemplo "Bluetooth 00:1D:A5:68:98:8B". */
    val descripcion: String

    /** Abre el enlace. Lanza [AdaptadorNoEncontradoException] o [PermisoBluetoothException]. */
    suspend fun abrir()

    suspend fun escribir(texto: String)

    /**
     * Espera los siguientes bytes que lleguen y los devuelve. Lanza
     * [EnlaceCaidoException] si el enlace se cerró (read() == -1) o falló la E/S.
     * El tiempo límite lo pone quien llama, cancelando la espera.
     */
    suspend fun leer(): ByteArray

    /** Descarta lo que haya llegado y nadie leyó. */
    suspend fun descartarEntrada()

    suspend fun cerrar()
}

/** El enlace con el adaptador se perdió: read() devolvió −1, falló la E/S o no se pudo reconectar. */
open class EnlaceCaidoException(mensaje: String, causa: Throwable? = null) : IOException(mensaje, causa)

/** El adaptador no terminó su respuesta (no llegó el prompt '>') dentro del tiempo límite. */
class TiempoAgotadoException(mensaje: String) : EnlaceCaidoException(mensaje)

/** No se pudo abrir el enlace Bluetooth con el adaptador (CU-02, excepción del paso 1). */
class AdaptadorNoEncontradoException(mensaje: String, causa: Throwable? = null) : IOException(mensaje, causa)

/** Falta el permiso BLUETOOTH_CONNECT (dispositivos cercanos) en Android 12 o superior. */
class PermisoBluetoothException(mensaje: String, causa: Throwable? = null) : IOException(mensaje, causa)

/** El adaptador contesta pero la unidad de control del vehículo no (CU-02, excepción del paso 4). */
class VehiculoNoRespondeException(mensaje: String) : IOException(mensaje)
