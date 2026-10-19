package com.example.pfc_app.adquisicion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.pfc_app.Registro

data class DispositivoBT(val nombre: String, val direccionMac: String)

/** Adaptadores ya vinculados en Ajustes. Requiere BLUETOOTH_CONNECT en Android 12+. */
object DispositivosBluetooth {

    /** true si el teléfono tiene Bluetooth (encendido o no). */
    fun existe(contexto: Context): Boolean = adaptador(contexto) != null

    /** true si el teléfono tiene Bluetooth y está encendido. */
    @SuppressLint("MissingPermission")
    fun disponible(contexto: Context): Boolean {
        val adaptador = adaptador(contexto) ?: return false
        return try {
            adaptador.isEnabled
        } catch (e: SecurityException) {
            Registro.w(Registro.BT, "Sin permiso para consultar el Bluetooth: ${e.message}")
            false
        }
    }

    /** true si la aplicación tiene el permiso que pide la conexión (BLUETOOTH_CONNECT desde Android 12). */
    fun tienePermiso(contexto: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            contexto.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Dispositivos vinculados, con los que parecen adaptadores OBD-II primero
     * (OBDII, ELM327, V-LINK, Vgate...). Lista vacía si falta el permiso.
     */
    @SuppressLint("MissingPermission")
    fun vinculados(contexto: Context): List<DispositivoBT> {
        val adaptador = adaptador(contexto) ?: return emptyList()
        return try {
            adaptador.bondedDevices.orEmpty()
                .map { DispositivoBT(it.name?.takeIf(String::isNotBlank) ?: it.address, it.address) }
                .sortedWith(compareByDescending<DispositivoBT> { pareceObd(it.nombre) }.thenBy { it.nombre })
                .also { l -> Registro.d(Registro.BT, "Vinculados: ${l.joinToString { "${it.nombre} (${it.direccionMac})" }}") }
        } catch (e: SecurityException) {
            Registro.w(Registro.BT, "Falta BLUETOOTH_CONNECT para listar los vinculados: ${e.message}")
            emptyList()
        }
    }

    private fun adaptador(contexto: Context): BluetoothAdapter? =
        contexto.getSystemService(BluetoothManager::class.java)?.adapter

    private fun pareceObd(nombre: String): Boolean {
        val n = nombre.uppercase()
        return listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "KONNWEI", "SCAN").any { it in n }
    }
}
