package com.example.pfc_app.cuenta

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Si el teléfono tiene internet ahora (red activa con salida a internet comprobada). */
object Conectividad {
    fun hayInternet(contexto: Context): Boolean {
        val gestor = contexto.getSystemService(ConnectivityManager::class.java) ?: return false
        val capacidades = gestor.getNetworkCapabilities(gestor.activeNetwork) ?: return false
        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
