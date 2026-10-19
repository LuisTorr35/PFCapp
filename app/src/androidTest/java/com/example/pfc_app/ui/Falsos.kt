package com.example.pfc_app.ui

import com.example.pfc_app.adquisicion.AdaptadorELM327
import com.example.pfc_app.adquisicion.CatalogoDemo
import com.example.pfc_app.adquisicion.EstadoCaptura
import com.example.pfc_app.adquisicion.EstadoEnlace
import com.example.pfc_app.adquisicion.EstadoRegimen
import com.example.pfc_app.adquisicion.GrabacionDemo
import com.example.pfc_app.adquisicion.ProgresoRegimen
import com.example.pfc_app.adquisicion.ResultadoConexion
import com.example.pfc_app.adquisicion.ServicioAdquisicion
import com.example.pfc_app.datos.FechasIso
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.CaracteristicaModelo
import com.example.pfc_app.modelos.ClaseModelo
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.ContratoModelo
import com.example.pfc_app.modelos.Diagnostico
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.Direccion
import com.example.pfc_app.modelos.EfectoRegla
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ParametroDesviado
import com.example.pfc_app.modelos.ReferenciaSinFalla
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.ResultadoRegla
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.modelos.Vehiculo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update

// Falsos para probar la interfaz sin las clases reales de captura, validación y modelo.

object Falsos {
    val grabaciones = listOf(
        GrabacionDemo("ATN-037", "ATN-037 · Hyundai Tucson 2011", "Hyundai", "Tucson", 2011, 4),
        GrabacionDemo("ATN-012", "ATN-012 · Kia Picanto 2019", "Kia", "Picanto", 2019, 4)
    )

    val catalogo = object : CatalogoDemo {
        override fun grabaciones(): List<GrabacionDemo> = Falsos.grabaciones
    }

    val contrato = ContratoModelo(
        versionModelo = "0.1.0-provisional",
        provisional = true,
        archivoOnnx = "modelo_provisional.onnx",
        nombreEntrada = "caracteristicas",
        nombreSalidaProbabilidades = "probabilities",
        clases = listOf(
            ClaseModelo(0, "C0", "Sin falla de motor"),
            ClaseModelo(1, "C1", "Encendido y combustión"),
            ClaseModelo(2, "C2", "Mezcla de aire y combustible"),
            ClaseModelo(3, "C3", "Admisión y flujo de aire")
        ),
        caracteristicas = listOf(
            CaracteristicaModelo(
                1,
                "R2_rpm_desv",
                "Variación de rpm en ralentí caliente",
                "rpm",
                TipoRegimen.R2,
                "rpm",
                "desv",
                ReferenciaSinFalla(5.89, 3.84, 8.44, 9.07, -3.06, 15.33)
            )
        ),
        regimenesRequeridos = listOf(TipoRegimen.R2, TipoRegimen.R3),
        columnasLecturaRequeridas = listOf("rpm", "carga_calculada", "stft", "ltft", "avance_encendido")
    )

    /** R2 con menos de 45 muestras útiles rechaza; [sinParametros] imita la regla 8. */
    fun validar(a: Atencion, sinParametros: Boolean = false): Dictamen {
        val r2 = a.regimen(TipoRegimen.R2)
        if (r2 == null || r2.muestrasUtiles < 45) {
            val motivo = "R2 no reunió las 45 muestras útiles."
            return Dictamen(
                resultado = ResultadoDictamen.RECHAZADA,
                reglas = listOf(
                    ResultadoRegla(
                        2,
                        NivelRegla.OBLIGATORIA,
                        "45 muestras útiles por régimen",
                        EfectoRegla.RECHAZA,
                        TipoRegimen.R2,
                        motivo
                    )
                ),
                regimenesARepetir = listOf(TipoRegimen.R2),
                observacion = motivo
            )
        }
        return Dictamen(ResultadoDictamen.COMPLETA, emptyList(), faltanParametrosModelo = sinParametros)
    }

    fun diagnosticar(a: Atencion): Diagnostico = Diagnostico(
        clase = "C1",
        nombreClase = "Encendido y combustión",
        probabilidad = 0.72,
        versionModelo = "0.1.0-provisional",
        parametrosDesviados = listOf(
            // Sin etiqueta ni unidad: la pantalla debe completarlas con el contrato.
            ParametroDesviado("R2_rpm_desv", 14.9, 3.5, Direccion.MAYOR)
        ),
        codigosLeidos = a.codigos,
        modeloProvisional = true
    )
}

class AdaptadorFalso : AdaptadorELM327 {
    override val esDemostracion: Boolean = true
    private val enlace = MutableStateFlow(EstadoEnlace.DESCONECTADO)
    override val estadoEnlace: StateFlow<EstadoEnlace> = enlace.asStateFlow()

    @Volatile
    var cerrado = false

    override suspend fun conectar() = ResultadoConexion(true, "ISO 15765-4 (CAN 11/500)", FamiliaGestion.MAP)
    override suspend fun leerCodigos(): List<CodigoDTC> = emptyList()
    override suspend fun leerContadoresModo06(): List<ContadorModo06> = emptyList()
    override suspend fun leerBarometrica(): Double? = 75.0
    override suspend fun leerTemperaturaRefrigerante(): Double? = 88.0
    override fun leerParametros(regimen: TipoRegimen): Flow<Lectura> = emptyFlow()
    override suspend fun reconectar(): Boolean = true
    override suspend fun cerrar() {
        cerrado = true
    }
}

/**
 * Servicio de adquisición falso: conecta, hace la lectura inicial, omite R1 (motor caliente),
 * pide "Continuar" y captura R2 (31 muestras si [r2Corto]) y R3. Al repetir, R2 sale completo.
 */
class ServicioFalso(private val r2Corto: Boolean) : ServicioAdquisicion {
    private val _estado = MutableStateFlow(EstadoCaptura(modoDemostracion = true))
    override val estado: StateFlow<EstadoCaptura> = _estado.asStateFlow()
    private val confirmacion = CompletableDeferred<Unit>()
    private var atencion: Atencion? = null

    @Volatile
    var finalizado = false

    override suspend fun capturarAtencion(vehiculo: Vehiculo, adaptador: AdaptadorELM327): Atencion {
        _estado.update { it.copy(enlace = EstadoEnlace.CONECTANDO) }
        delay(150)
        _estado.update {
            it.copy(enlace = EstadoEnlace.CONECTADO, protocolo = "ISO 15765-4 (CAN 11/500)", familiaGestion = FamiliaGestion.MAP)
        }
        val codigos = listOf(CodigoDTC("P0303", TipoDTC.ALMACENADO))
        val contadores = (1..4).map { ContadorModo06(it, if (it == 3) 12 else 0, 0) }
        _estado.update {
            it.copy(lecturaInicialHecha = true, codigos = codigos, contadoresModo06 = contadores, baroKpa = 75.0)
        }
        progreso(TipoRegimen.R1) { it.copy(estado = EstadoRegimen.OMITIDO, motivoOmision = "el motor llegó caliente") }
        _estado.update { it.copy(instruccion = "Arranque el motor y déjelo en ralentí", esperaConfirmacion = true) }
        confirmacion.await()
        _estado.update { it.copy(instruccion = null, esperaConfirmacion = false) }
        val r2 = capturar(TipoRegimen.R2, if (r2Corto) 31 else 46)
        val r3 = capturar(TipoRegimen.R3, 50)
        val a = Atencion(
            idVehiculo = vehiculo.id,
            fecha = FechasIso.ahora(),
            baroKpa = 75.0,
            protocolo = "ISO 15765-4 (CAN 11/500)",
            codigos = codigos,
            contadoresModo06 = contadores,
            regimenes = mapOf(TipoRegimen.R2 to r2, TipoRegimen.R3 to r3)
        )
        atencion = a
        _estado.update { it.copy(terminada = true) }
        return a
    }

    override suspend fun repetirRegimen(tipo: TipoRegimen): Atencion {
        val r = capturar(tipo, 46)
        val a = checkNotNull(atencion).agregarRegimen(r)
        atencion = a
        return a
    }

    override fun confirmar() {
        confirmacion.complete(Unit)
    }

    override suspend fun finalizar() {
        finalizado = true
    }

    private suspend fun capturar(tipo: TipoRegimen, muestras: Int): Regimen {
        for (n in 0..muestras step 5) {
            progreso(tipo) { it.copy(estado = EstadoRegimen.CAPTURANDO, muestrasUtiles = n, rpm = 812.0, ect = 88.0) }
            delay(25)
        }
        progreso(tipo) { it.copy(estado = EstadoRegimen.CAPTURADO, muestrasUtiles = muestras) }
        val lecturas = (0 until muestras).map { i ->
            Lectura(
                instante = i * 0.4, rpm = 800.0 + i % 4 * 0.25, tempRefrigerante = 88.0, stft = 1.5, ltft = 9.4,
                cargaCalculada = 23.0, avanceEncendido = 8.0, maf = null, map = 35.0, velocidad = 0.0
            )
        }
        return Regimen(tipo = tipo, lecturas = lecturas, muestrasUtiles = muestras, frecuenciaHz = 2.5)
    }

    private fun progreso(tipo: TipoRegimen, cambio: (ProgresoRegimen) -> ProgresoRegimen) {
        _estado.update { e ->
            val actual = e.regimenes[tipo] ?: ProgresoRegimen(tipo)
            e.copy(regimenes = e.regimenes + (tipo to cambio(actual)))
        }
    }
}
