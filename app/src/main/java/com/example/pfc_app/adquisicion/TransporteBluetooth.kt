package com.example.pfc_app.adquisicion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.pfc_app.Registro
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Enlace RFCOMM con el adaptador por el perfil de puerto serie (SPP) del Bluetooth
 * clásico. Solo usa adaptadores ya vinculados en Ajustes: no escanea.
 *
 * La lectura es bloqueante en el socket, así que corre en un hilo propio que pasa
 * los bytes a un canal; quien lee espera en el canal con su tiempo límite. Si
 * read() devuelve −1 o lanza una excepción, el canal se cierra con
 * [EnlaceCaidoException] y la siguiente lectura lo informa (en Android 17 un
 * socket cerrado por el otro extremo devuelve −1 en vez de lanzar).
 * Cada enlace es de un solo uso: al reconectar se crea un transporte nuevo.
 */
class TransporteBluetooth(
    private val contexto: Context,
    private val direccionMac: String,
    private val despachador: CoroutineDispatcher = Dispatchers.IO,
) : TransporteSerie {

    override val descripcion: String = "Bluetooth $direccionMac"

    private var socket: BluetoothSocket? = null
    private var entrada: InputStream? = null
    private var salida: OutputStream? = null
    private var hiloLector: Thread? = null
    private val recibidos = Channel<ByteArray>(Channel.UNLIMITED)

    // los SecurityException por falta de BLUETOOTH_CONNECT se convierten en PermisoBluetoothException
    @SuppressLint("MissingPermission")
    override suspend fun abrir() = withContext(despachador) {
        val manager = contexto.getSystemService(BluetoothManager::class.java)
        val adaptador = manager?.adapter
            ?: throw AdaptadorNoEncontradoException("Este teléfono no tiene Bluetooth")
        try {
            if (!adaptador.isEnabled) {
                throw AdaptadorNoEncontradoException("El Bluetooth del teléfono está apagado. Enciéndalo y vuelva a intentar.")
            }
            val dispositivo = adaptador.getRemoteDevice(direccionMac)
            // El descubrimiento en curso hace muy lenta la conexión RFCOMM. Cancelarlo exige
            // BLUETOOTH_SCAN desde Android 12, y la app no escanea: sin ese permiso no se intenta.
            if (puedeCancelarDescubrimiento()) {
                try {
                    adaptador.cancelDiscovery()
                } catch (e: SecurityException) {
                    Registro.w(Registro.BT, "No se pudo cancelar el descubrimiento: ${e.message}")
                }
            } else {
                Registro.d(Registro.BT, "Sin BLUETOOTH_SCAN: no se cancela el descubrimiento (la app no escanea)")
            }
            val s = conectarSocket(dispositivo)
            socket = s
            entrada = s.inputStream
            salida = s.outputStream
            iniciarLector(s.inputStream)
            Registro.i(Registro.BT, "Socket RFCOMM abierto con $direccionMac")
        } catch (e: SecurityException) {
            throw PermisoBluetoothException(
                "Falta el permiso de Bluetooth (dispositivos cercanos). Concédalo a la aplicación en Ajustes.", e,
            )
        } catch (e: IllegalArgumentException) {
            throw AdaptadorNoEncontradoException("La dirección $direccionMac no es una dirección Bluetooth válida", e)
        }
    }

    /** Primero el socket seguro con el UUID SPP y, si falla, el inseguro, que aceptan muchos clones. */
    @SuppressLint("MissingPermission")
    private fun conectarSocket(dispositivo: BluetoothDevice): BluetoothSocket {
        val intentos = listOf<Pair<String, () -> BluetoothSocket>>(
            "seguro" to { dispositivo.createRfcommSocketToServiceRecord(UUID_SPP) },
            "inseguro" to { dispositivo.createInsecureRfcommSocketToServiceRecord(UUID_SPP) },
        )
        var ultimo: IOException? = null
        for ((nombre, crear) in intentos) {
            var s: BluetoothSocket? = null
            try {
                Registro.d(Registro.BT, "Conectando socket RFCOMM $nombre con $direccionMac (UUID $UUID_SPP)")
                s = crear()
                s.connect()
                return s
            } catch (e: IOException) {
                Registro.w(Registro.BT, "Socket $nombre falló: ${e.message}")
                ultimo = e
                try {
                    s?.close()
                } catch (_: IOException) {
                }
            }
        }
        val nombre = try {
            dispositivo.name?.takeIf(String::isNotBlank)
        } catch (_: SecurityException) {
            null
        }
        throw AdaptadorNoEncontradoException(
            "No se pudo abrir el puerto serie con «${nombre ?: direccionMac}» ($direccionMac) tras 2 intentos. " +
                "El dispositivo elegido no responde como adaptador ELM327: revise que sea el adaptador OBD-II, " +
                "que esté enchufado al conector del vehículo con el contacto puesto y vinculado con el teléfono.",
            ultimo,
        )
    }

    private fun puedeCancelarDescubrimiento(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            contexto.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun iniciarLector(flujo: InputStream) {
        hiloLector = Thread({
            val buffer = ByteArray(1024)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = flujo.read(buffer)
                    if (n == -1) {
                        Registro.w(Registro.BT, "read() devolvió -1: el adaptador cerró el enlace")
                        recibidos.close(EnlaceCaidoException("El adaptador cerró el enlace (read() devolvió -1)"))
                        return@Thread
                    }
                    if (n > 0) recibidos.trySend(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                Registro.w(Registro.BT, "Error de lectura del socket: ${e.message}")
                recibidos.close(EnlaceCaidoException("Se perdió el enlace Bluetooth: ${e.message}", e))
            }
        }, "ELM327-lector").apply {
            isDaemon = true
            start()
        }
    }

    override suspend fun escribir(texto: String) = withContext(despachador) {
        val s = salida ?: throw EnlaceCaidoException("El enlace no está abierto")
        try {
            s.write(texto.toByteArray(Charsets.US_ASCII))
            s.flush()
        } catch (e: IOException) {
            throw EnlaceCaidoException("No se pudo escribir al adaptador: ${e.message}", e)
        }
    }

    override suspend fun leer(): ByteArray =
        try {
            recibidos.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw EnlaceCaidoException("El enlace con el adaptador está cerrado", e)
        }

    override suspend fun descartarEntrada() {
        while (recibidos.tryReceive().isSuccess) Unit
    }

    override suspend fun cerrar() = withContext(despachador) {
        hiloLector?.interrupt()
        try {
            socket?.close()
        } catch (e: IOException) {
            Registro.w(Registro.BT, "Error al cerrar el socket: ${e.message}")
        }
        recibidos.close()
        socket = null
        entrada = null
        salida = null
        Registro.d(Registro.BT, "Socket cerrado ($direccionMac)")
    }

    companion object {
        /** Perfil de puerto serie (SPP). */
        val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
