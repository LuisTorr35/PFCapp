package com.example.pfc_app.ui

import com.example.pfc_app.modelos.TipoRegimen

/**
 * Etiquetas (testTag) de los elementos clave, para las pruebas de interfaz.
 * Las tarjetas que muestran texto (dictamen, aviso, filas) fusionan sus textos, de modo
 * que hasText(..., substring = true) funciona sobre el nodo con la etiqueta.
 */
object EtiquetasPrueba {
    // Comunes
    const val BOTON_VOLVER = "boton_volver"

    // Inicio de sesión y menú de la cuenta
    const val BOTON_CONTINUAR_GOOGLE = "boton_continuar_google"
    const val AVISO_SIN_CONEXION = "aviso_sin_conexion"
    const val BOTON_MENU_CUENTA = "boton_menu_cuenta"
    const val MENU_CERRAR_SESION = "menu_cerrar_sesion"
    const val DIALOGO_CERRAR_SESION = "dialogo_cerrar_sesion"
    const val BOTON_CONFIRMAR_CERRAR_SESION = "boton_confirmar_cerrar_sesion"

    // Vehículos (CU-01)
    const val INTERRUPTOR_DEMO = "interruptor_demo"
    const val BOTON_ELEGIR_ADAPTADOR = "boton_elegir_adaptador"
    const val DIALOGO_ADAPTADOR = "dialogo_adaptador"
    const val CAMPO_MARCA = "campo_marca"
    const val CAMPO_MODELO = "campo_modelo"
    const val CAMPO_ANIO = "campo_anio"
    const val CAMPO_CILINDROS = "campo_cilindros"
    const val BOTON_REGISTRAR = "boton_registrar"
    const val MENSAJE_REGISTRO = "mensaje_registro"
    const val CAMPO_BUSCAR = "campo_buscar"
    const val LISTA_VEHICULOS = "lista_vehiculos"
    fun filaVehiculo(codigo: String) = "fila_vehiculo_$codigo"
    fun botonAtender(codigo: String) = "boton_atender_$codigo"
    fun botonHistorial(codigo: String) = "boton_historial_$codigo"

    // Diálogo de la grabación a reproducir (modo demostración)
    const val DIALOGO_GRABACION = "dialogo_grabacion"
    fun opcionGrabacion(indice: Int) = "opcion_grabacion_$indice"
    const val CASILLA_RECHAZO = "casilla_rechazo_r2"
    const val BOTON_CONFIRMAR_GRABACION = "boton_confirmar_grabacion"

    // Captura (CU-02, CU-03, CU-04, CU-08)
    const val AVISO_DEMO = "aviso_demostracion"
    const val TARJETA_ADAPTADOR = "tarjeta_adaptador"
    const val TARJETA_LECTURA_INICIAL = "tarjeta_lectura_inicial"
    fun tarjetaRegimen(tipo: TipoRegimen) = "tarjeta_regimen_${tipo.name}"
    fun botonRepetir(tipo: TipoRegimen) = "boton_repetir_${tipo.name}"
    const val TARJETA_INSTRUCCION = "tarjeta_instruccion"
    const val BOTON_CONTINUAR = "boton_continuar"
    const val TARJETA_MENSAJE = "tarjeta_mensaje"
    const val TARJETA_ERROR = "tarjeta_error"
    const val TARJETA_DICTAMEN = "tarjeta_dictamen"
    const val BOTON_DIAGNOSTICAR = "boton_diagnosticar"
    const val DIALOGO_GUARDADA_SIN_DIAGNOSTICO = "dialogo_guardada_sin_diagnostico"
    const val BOTON_ACEPTAR_SIN_DIAGNOSTICO = "boton_aceptar_sin_diagnostico"

    // Diagnóstico (CU-07)
    const val NOMBRE_CLASE = "nombre_clase"
    const val TEXTO_PROBABILIDAD = "texto_probabilidad"
    const val BARRA_PROBABILIDAD = "barra_probabilidad"
    const val PARAMETRO_DESVIADO = "parametro_desviado"
    const val TEXTO_CODIGOS = "texto_codigos"
    const val PIE_DIAGNOSTICO = "pie_diagnostico"
    const val BOTON_GUARDAR_ATENCION = "boton_guardar_atencion"

    // Historial (CU-06)
    const val TARJETA_VEHICULO = "tarjeta_vehiculo"
    const val AVISO_HISTORIAL = "aviso_historial"
    const val LISTA_ATENCIONES = "lista_atenciones"

    /** Todas las filas llevan la misma etiqueta; la primera es la más reciente. */
    const val FILA_ATENCION = "fila_atencion"
    const val HISTORIAL_VACIO = "historial_vacio"
    const val DIALOGO_SIN_DIAGNOSTICO = "dialogo_sin_diagnostico"
}
