package com.example.pfc_app.adquisicion

import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.modelos.Vehiculo
import com.example.pfc_app.procesamiento.ProcesadorCaracteristicas
import com.example.pfc_app.procesamiento.Redondeo
import com.example.pfc_app.procesamiento.ReglaMuestras
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** La captura no pudo seguir; [message] es legible para el mecánico. */
class ErrorCaptura(mensaje: String, causa: Throwable? = null) : Exception(mensaje, causa)

/**
 * Orquesta la captura de una atención según CU-03 (paquete adquisicion del Capítulo 5):
 * conexión (CU-02), lectura inicial, R1 si el motor llegó frío, R2 con el motor
 * caliente y R3, con reconexión automática que conserva lo ya capturado (RNF-01).
 *
 * En modo real pide confirmación al mecánico antes del arranque y de las
 * aceleraciones, espera el calentamiento antes de R2 y corta cada régimen como
 * registrar_obd.py v4 ([ConfiguracionCaptura]). En demostración avanza solo y cada
 * régimen dura lo que la grabación.
 */
class ServicioAdquisicionImpl(
    private val procesador: ProcesadorCaracteristicas = ProcesadorCaracteristicas(),
    private val config: ConfiguracionCaptura = ConfiguracionCaptura(),
    private val reloj: () -> Long = { System.currentTimeMillis() },
) : ServicioAdquisicion {

    private val _estado = MutableStateFlow(EstadoCaptura())
    override val estado: StateFlow<EstadoCaptura> = _estado.asStateFlow()

    private var adaptador: AdaptadorELM327? = null
    private var vehiculo: Vehiculo? = null
    private var confirmacion: CompletableDeferred<Unit>? = null

    /** La atención en curso, con los regímenes capturados hasta ahora (también tras un error). */
    var atencionActual: Atencion? = null
        private set

    private val demo: Boolean get() = adaptador?.esDemostracion == true

    override suspend fun capturarAtencion(vehiculo: Vehiculo, adaptador: AdaptadorELM327): Atencion {
        this.adaptador = adaptador
        this.vehiculo = vehiculo
        atencionActual = null
        _estado.value = EstadoCaptura(modoDemostracion = adaptador.esDemostracion)
        Registro.i(
            Registro.CAPTURA,
            "CU-03: atención de ${vehiculo.codigo} (${vehiculo.marca} ${vehiculo.modelo} ${vehiculo.anio})" +
                if (adaptador.esDemostracion) " en modo demostración" else "",
        )
        return conVigiaDelEnlace(adaptador) { ejecutarAtencion(vehiculo, adaptador) }
    }

    override suspend fun repetirRegimen(tipo: TipoRegimen): Atencion {
        val ad = adaptador ?: throw ErrorCaptura("No hay una atención en curso")
        val at = atencionActual ?: throw ErrorCaptura("No hay una atención en curso")
        Registro.i(Registro.CAPTURA, "CU-08: se descarta $tipo y se vuelve a capturar")
        atencionActual = at.copy(regimenes = at.regimenes - tipo)
        _estado.update {
            it.copy(
                terminada = false,
                error = null,
                mensaje = null,
                regimenes = it.regimenes + (tipo to ProgresoRegimen(tipo, objetivo = objetivoMostrado(tipo))),
            )
        }
        return conVigiaDelEnlace(ad) {
            prepararRegimen(tipo, ad)
            capturarRegimen(tipo, ad)
            _estado.update { it.copy(terminada = true, instruccion = null, esperaConfirmacion = false) }
            atencionActual!!
        }
    }

    override fun confirmar() {
        Registro.d(Registro.CAPTURA, "El mecánico confirmó: ${_estado.value.instruccion}")
        confirmacion?.complete(Unit)
    }

    override suspend fun finalizar() {
        try {
            adaptador?.cerrar()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Registro.w(Registro.CAPTURA, "Error al cerrar el enlace: ${e.message}")
        }
        _estado.update { it.copy(enlace = EstadoEnlace.DESCONECTADO, esperaConfirmacion = false) }
        confirmacion?.cancel()
        confirmacion = null
        Registro.i(Registro.CAPTURA, "Captura finalizada")
    }

    // ---------------------------------------------------------------------------

    private suspend fun ejecutarAtencion(vehiculo: Vehiculo, ad: AdaptadorELM327): Atencion {
        // CU-02: conexión
        _estado.update { it.copy(enlace = EstadoEnlace.CONECTANDO, instruccion = "Conectando con el adaptador...") }
        val conexion = try {
            ad.conectar()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ResultadoConexion(exito = false, mensajeError = e.message)
        }
        if (!conexion.exito) {
            val msg = conexion.mensajeError ?: "No se pudo conectar con el adaptador"
            Registro.e(Registro.CAPTURA, "Sin conexión: $msg")
            _estado.update { it.copy(error = msg, instruccion = null, enlace = EstadoEnlace.DESCONECTADO) }
            throw ErrorCaptura(msg)
        }
        _estado.update {
            it.copy(
                protocolo = conexion.protocolo,
                familiaGestion = conexion.familiaGestion,
                instruccion = "Lectura inicial: códigos de diagnóstico y contadores del modo 06",
            )
        }

        // paso 3: lectura inicial con el contacto puesto y el motor apagado
        val codigos = conReconexion("leer los códigos") { ad.leerCodigos() }
        val modo06 = conReconexion("leer el modo 06") { ad.leerContadoresModo06() }
        val baro = conReconexion("leer la barométrica") { ad.leerBarometrica() }
        val mapContacto = conReconexion("leer la MAP con el motor apagado") { ad.leerMapContacto() }
        val baroSesion = baro ?: mapContacto
        val ectInicial = conReconexion("leer el refrigerante") { ad.leerTemperaturaRefrigerante() }
        _estado.update {
            it.copy(lecturaInicialHecha = true, codigos = codigos, contadoresModo06 = modo06, baroKpa = baroSesion)
        }
        Registro.i(
            Registro.CAPTURA,
            "Lectura inicial: ${codigos.size} códigos, ${modo06.size} cilindros en modo 06, barométrica " +
                (if (baro != null) "$baro kPa (PID 0x33)" else "$mapContacto kPa (MAP con el motor apagado)") + ", " +
                "refrigerante $ectInicial °C",
        )
        atencionActual = Atencion(
            idVehiculo = vehiculo.id,
            fecha = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(reloj())),
            baroKpa = baro,
            mapContactoKpa = mapContacto,
            protocolo = conexion.protocolo,
            codigos = codigos,
            contadoresModo06 = modo06,
        )

        // paso 4: arranque
        instruir("Arranque el motor y déjelo en ralentí, sin acelerar ni encender el aire acondicionado")

        // paso 5: R1 solo si el motor llegó frío
        val ect = if (demo) ectInicial else conReconexion("leer el refrigerante") { ad.leerTemperaturaRefrigerante() } ?: ectInicial
        if (ect != null && ect < config.ectTopeR1) {
            capturarRegimen(TipoRegimen.R1, ad)
        } else {
            val motivo = if (ect == null) "sin lectura de refrigerante: no se puede saber si el motor está frío"
            else "el motor llegó caliente (${Redondeo.python(ect, 1)} °C)"
            Registro.i(Registro.CAPTURA, "R1 omitido: $motivo")
            progreso(TipoRegimen.R1) { it.copy(estado = EstadoRegimen.OMITIDO, motivoOmision = motivo, ect = ect) }
        }

        // R2 con el motor caliente
        prepararRegimen(TipoRegimen.R2, ad)
        capturarRegimen(TipoRegimen.R2, ad)

        // paso 6: R3, aceleraciones controladas
        prepararRegimen(TipoRegimen.R3, ad)
        capturarRegimen(TipoRegimen.R3, ad)

        _estado.update { it.copy(terminada = true, instruccion = null, esperaConfirmacion = false) }
        val at = atencionActual!!
        Registro.i(
            Registro.CAPTURA,
            "Atención capturada: " + at.regimenes.values.joinToString {
                "${it.tipo} ${it.lecturas.size} filas, ${it.muestrasUtiles} útiles, ${"%.2f".format(it.frecuenciaHz)} Hz"
            },
        )
        return at
    }

    /** Instrucción y condición previa de cada régimen (también al repetirlo). */
    private suspend fun prepararRegimen(tipo: TipoRegimen, ad: AdaptadorELM327) {
        when (tipo) {
            TipoRegimen.R1 -> instruir("Motor recién arrancado, en frío y en ralentí, sin acelerar")
            TipoRegimen.R2 -> {
                if (!demo) esperarCalentamiento(ad)
                if (demo) instruir("Ralentí caliente: motor estabilizado, sin acelerar ni encender el aire acondicionado")
                else _estado.update {
                    it.copy(instruccion = "Ralentí caliente: no acelere ni encienda el aire acondicionado", esperaConfirmacion = false)
                }
            }
            TipoRegimen.R3 -> instruir(
                "Haga tres subidas de 800 a 3000 rpm en neutro y vuelva al ralentí, cada subida de unos 8 segundos, " +
                    "sin pisar a fondo",
            )
        }
    }

    /**
     * Captura un régimen y lo agrega a la atención. Si el enlace cae y el adaptador
     * no se recupera solo, reintenta la reconexión y sigue con el mismo régimen,
     * conservando sus lecturas y la base de tiempo.
     */
    private suspend fun capturarRegimen(tipo: TipoRegimen, ad: AdaptadorELM327) {
        val objetivo = objetivoMostrado(tipo)
        progreso(tipo) { ProgresoRegimen(tipo, EstadoRegimen.CAPTURANDO, objetivo = objetivo) }
        if (!demo) _estado.update { it.copy(esperaConfirmacion = false) }
        Registro.i(Registro.CAPTURA, "Capturando $tipo (${tipo.nombre})" + if (demo) " desde la grabación" else "")

        val lecturas = ArrayList<Lectura>()
        val corte = Corte(tipo)
        val inicio = reloj()
        var offset = 0.0
        var rondas = 0
        while (true) {
            try {
                ad.leerParametros(tipo).firstOrNull { l0 ->
                    val l = if (offset > 0) l0.copy(instante = Redondeo.python(l0.instante + offset, 2)) else l0
                    lecturas += l
                    val utiles = ReglaMuestras.contarUtiles(tipo, lecturas)
                    progreso(tipo) { it.copy(muestrasUtiles = utiles, rpm = l.rpm, ect = l.tempRefrigerante) }
                    !demo && corte.debeCortar(l, utiles, (reloj() - inicio) / 1000.0)
                }
                break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                rondas++
                Registro.w(Registro.CAPTURA, "$tipo: enlace caído (${e.message}), ronda de reconexión $rondas")
                _estado.update { it.copy(mensaje = MENSAJE_RECONECTANDO, enlace = EstadoEnlace.RECONECTANDO) }
                if (rondas > config.rondasReconexion || !reconectarCon(ad)) {
                    guardarParcial(tipo, lecturas)
                    val msg = "Se perdió el enlace con el adaptador durante $tipo y no se pudo restablecer. " +
                        "Los regímenes ya capturados se conservan."
                    _estado.update { it.copy(error = msg, mensaje = null, enlace = EstadoEnlace.DESCONECTADO) }
                    throw ErrorCaptura(msg, e)
                }
                // la nueva lectura cuenta su tiempo desde cero: se desplaza al tiempo real del régimen
                offset = (reloj() - inicio) / 1000.0
                _estado.update { it.copy(mensaje = "Enlace restablecido, se continúa con $tipo", enlace = EstadoEnlace.CONECTADO) }
            }
        }
        if (corte.motivo != null) Registro.i(Registro.CAPTURA, "$tipo cortado: ${corte.motivo}")
        guardarParcial(tipo, lecturas)
    }

    /** Agrega el régimen contado a la atención (o lo marca omitido si no tiene filas). */
    private fun guardarParcial(tipo: TipoRegimen, lecturas: List<Lectura>) {
        if (lecturas.isEmpty()) {
            progreso(tipo) { it.copy(estado = EstadoRegimen.OMITIDO, motivoOmision = "no se registró ninguna muestra") }
            return
        }
        val regimen = procesador.contarMuestras(Regimen(tipo = tipo, lecturas = lecturas.toList()))
        atencionActual = atencionActual?.agregarRegimen(regimen)
        progreso(tipo) {
            it.copy(
                estado = EstadoRegimen.CAPTURADO,
                muestrasUtiles = regimen.muestrasUtiles,
                rpm = lecturas.last().rpm,
                ect = lecturas.last().tempRefrigerante,
            )
        }
        Registro.i(
            Registro.CAPTURA,
            "$tipo capturado: ${lecturas.size} filas, ${regimen.muestrasUtiles} útiles, " +
                "${regimen.descartadasTransitorio} por transitorio, ${regimen.descartadasTecho} por techo, " +
                "${"%.2f".format(regimen.frecuenciaHz)} Hz",
        )
    }

    /** Espera a que el refrigerante supere 85 °C, consultando cada 5 s y como máximo 25 min. */
    private suspend fun esperarCalentamiento(ad: AdaptadorELM327) {
        val maximo = (config.esperaCalentamientoMaxMin * 60_000L / config.consultaCalentamientoMs).toInt()
        var sinLectura = 0
        for (i in 0..maximo) {
            val ect = conReconexion("leer el refrigerante") { ad.leerTemperaturaRefrigerante() }
            progreso(TipoRegimen.R2) { it.copy(ect = ect) }
            if (ect != null && ect > config.ectR2) {
                Registro.i(Registro.CAPTURA, "Temperatura de trabajo alcanzada: $ect °C")
                return
            }
            if (ect == null) {
                if (++sinLectura >= config.calentamientoSinLectura) {
                    Registro.w(Registro.CAPTURA, "El vehículo no entrega el refrigerante: no se espera el calentamiento")
                    _estado.update { it.copy(mensaje = "El vehículo no entrega la temperatura del refrigerante") }
                    return
                }
            } else {
                sinLectura = 0
            }
            if (i == maximo) break
            val transcurrido = i * config.consultaCalentamientoMs / 1000
            _estado.update {
                it.copy(
                    instruccion = "Motor en ralentí calentando: refrigerante ${ect?.let { e -> Redondeo.python(e, 1) } ?: "--"} °C. " +
                        "R2 empieza al superar ${config.ectR2.toInt()} °C (${transcurrido / 60}:${"%02d".format(transcurrido % 60)})",
                    esperaConfirmacion = false,
                )
            }
            delay(config.consultaCalentamientoMs)
        }
        Registro.w(Registro.CAPTURA, "El refrigerante no superó ${config.ectR2} °C en ${config.esperaCalentamientoMaxMin} min")
        _estado.update {
            it.copy(mensaje = "El motor no llegó a la temperatura de trabajo en ${config.esperaCalentamientoMaxMin} min; se captura R2 igual")
        }
    }

    /** En modo real espera el "Continuar" del mecánico; en demostración muestra la instrucción y avanza. */
    private suspend fun instruir(texto: String) {
        if (demo) {
            _estado.update { it.copy(instruccion = texto, esperaConfirmacion = false) }
            delay(config.pausaInstruccionDemoMs)
            return
        }
        val d = CompletableDeferred<Unit>()
        confirmacion = d
        _estado.update { it.copy(instruccion = texto, esperaConfirmacion = true) }
        Registro.i(Registro.CAPTURA, "Esperando confirmación: $texto")
        d.await()
        confirmacion = null
        _estado.update { it.copy(esperaConfirmacion = false) }
    }

    /** Lectura suelta con reconexión del servicio si el adaptador lanza. */
    private suspend fun <T> conReconexion(que: String, bloque: suspend () -> T): T {
        var ultimo: Exception? = null
        for (ronda in 0..config.rondasReconexion) {
            try {
                return bloque()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ultimo = e
                Registro.w(Registro.CAPTURA, "No se pudo $que: ${e.message}")
                _estado.update { it.copy(mensaje = MENSAJE_RECONECTANDO) }
                val ad = adaptador ?: break
                if (ronda == config.rondasReconexion || !reconectarCon(ad)) break
            }
        }
        val msg = "No se pudo $que: se perdió el enlace con el adaptador. Revise que siga enchufado."
        _estado.update { it.copy(error = msg, mensaje = null) }
        throw ErrorCaptura(msg, ultimo)
    }

    private suspend fun reconectarCon(ad: AdaptadorELM327): Boolean {
        delay(config.pausaReconexionMs)
        return try {
            ad.reconectar()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Registro.w(Registro.CAPTURA, "La reconexión falló: ${e.message}")
            false
        }
    }

    /** Refleja el estado del enlace del adaptador en la pantalla mientras dura la captura. */
    private suspend fun <T> conVigiaDelEnlace(ad: AdaptadorELM327, bloque: suspend () -> T): T = coroutineScope {
        val vigia = launch {
            ad.estadoEnlace.collect { e ->
                _estado.update { s ->
                    s.copy(
                        enlace = e,
                        mensaje = when {
                            e == EstadoEnlace.RECONECTANDO -> MENSAJE_RECONECTANDO
                            s.mensaje == MENSAJE_RECONECTANDO && e == EstadoEnlace.CONECTADO -> "Enlace restablecido"
                            else -> s.mensaje
                        },
                    )
                }
            }
        }
        try {
            bloque()
        } finally {
            vigia.cancel()
        }
    }

    private fun progreso(tipo: TipoRegimen, cambio: (ProgresoRegimen) -> ProgresoRegimen) {
        _estado.update { s ->
            val actual = s.regimenes[tipo] ?: ProgresoRegimen(tipo)
            s.copy(regimenes = s.regimenes + (tipo to cambio(actual)))
        }
    }

    private fun objetivoMostrado(tipo: TipoRegimen): Int = if (demo) config.objetivoDemostracion else config.objetivo(tipo)

    /** Estado del corte de un régimen en modo real (capturar_regimen de registrar_obd.py v4). */
    private inner class Corte(private val tipo: TipoRegimen) {
        private var pausa = 0.0
        private var anterior: Double? = null
        private var avisoRampas = false
        /** Última rpm leída: R3 no corta mientras siga por encima del ralentí (en_rampa). */
        private var ultimoRpm: Double? = null
        var motivo: String? = null
            private set

        /**
         * [t] es el tiempo real desde el inicio del régimen. Los huecos se miden entre
         * los instantes de dos filas seguidas, como entre el inicio de dos ciclos.
         */
        fun debeCortar(l: Lectura, utiles: Int, t: Double): Boolean {
            val previo = anterior
            if (previo != null && l.instante - previo > config.huecoSeg) {
                pausa += l.instante - previo - config.periodoSeg
                Registro.w(Registro.CAPTURA, "$tipo: hueco de ${l.instante - previo} s, el reloj se pausa")
                if (tipo == TipoRegimen.R3) {
                    _estado.update { it.copy(mensaje = "El corte se comió parte de una rampa: haga una rampa más") }
                }
            }
            anterior = l.instante
            l.rpm?.let { ultimoRpm = it }
            val tEf = t - pausa
            val objetivo = config.objetivo(tipo)
            when {
                t >= config.techo(tipo) + config.techoAbsolutoExtraSeg -> motivo = "techo absoluto por cortes del enlace"
                tEf >= config.techo(tipo) -> motivo = "techo de tiempo"
                tipo == TipoRegimen.R1 && (l.tempRefrigerante ?: 0.0) >= config.ectTopeR1 ->
                    motivo = "refrigerante llegó a ${l.tempRefrigerante} °C"
                tEf >= config.piso(tipo) && utiles >= objetivo &&
                    !(tipo == TipoRegimen.R3 && (ultimoRpm ?: 0.0) > ReglaMuestras.TECHO_RALENTI_RPM) ->
                    motivo = "conteo de muestras útiles"
            }
            if (motivo == null && tipo == TipoRegimen.R3 && tEf >= config.piso(tipo) && !avisoRampas && utiles < objetivo) {
                avisoRampas = true
                _estado.update {
                    it.copy(mensaje = "Faltan muestras ($utiles de $objetivo): haga más rampas de 800 a 3000 rpm en neutro")
                }
            }
            return motivo != null
        }
    }

    companion object {
        const val MENSAJE_RECONECTANDO = "Enlace caído, reconectando..."
    }
}
