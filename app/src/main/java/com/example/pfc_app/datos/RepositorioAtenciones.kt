package com.example.pfc_app.datos

import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.Diagnostico
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.Direccion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.ParametroDesviado
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen
import kotlin.math.roundToLong

/** Una fila del historial (CU-06): fecha, dictamen y resultado del modelo, si lo hay. */
data class ResumenAtencion(
    val idAtencion: Long,
    val fecha: String,
    val resultado: ResultadoDictamen,
    val observacion: String,
    val clase: String?,
    val probabilidad: Double?,
    val versionModelo: String?,
    /** true si la atención ya está respaldada en la nube; false si está pendiente. */
    val respaldada: Boolean = false
) {
    val tieneDiagnostico: Boolean get() = clase != null
}

/** Una atención leída de la base con su dictamen y su diagnóstico (null si quedó sin él). */
data class AtencionGuardada(val atencion: Atencion, val dictamen: Dictamen, val diagnostico: Diagnostico?)

/** Atenciones guardadas en el historial del vehículo (RF-06). */
class RepositorioAtenciones(private val base: BaseDatosLocal) {
    private val dao = base.atenciones()

    /**
     * Guarda la atención con todas sus filas en una sola transacción: si algo falla, no
     * queda nada a medias. Las atenciones rechazadas no se guardan. Sin [diagnostico]
     * (regla 8, CU-05 E1) no se escribe fila en diagnostico. [uidRespaldo] es la cuenta con
     * sesión abierta al guardar: solo a su nube se subirá la atención.
     */
    fun guardar(atencion: Atencion, dictamen: Dictamen, diagnostico: Diagnostico?, uidRespaldo: String? = null): Long {
        require(dictamen.permiteGuardar()) { "Las atenciones rechazadas no se guardan" }
        require(atencion.regimenes.isNotEmpty()) { "La atención no tiene regímenes capturados" }
        try {
            return base.runInTransaction<Long> {
                val idAtencion = dao.insertarAtencion(
                    EntidadAtencion(
                        idVehiculo = atencion.idVehiculo,
                        fecha = FechasIso.normalizar(atencion.fecha),
                        baroKpa = atencion.barometricaSesion,
                        uidRespaldo = uidRespaldo
                    )
                )
                for (tipo in TipoRegimen.entries) {
                    val regimen = atencion.regimenes[tipo] ?: continue
                    insertarRegimen(idAtencion, regimen)
                }
                dao.insertarCodigos(
                    atencion.codigos.map {
                        EntidadCodigoDtc(idAtencion = idAtencion, codigo = it.codigo, tipo = it.tipo.texto)
                    }
                )
                dao.insertarContadores(
                    atencion.contadoresModo06.map {
                        EntidadContadorModo06(
                            idAtencion = idAtencion,
                            cilindro = it.cilindro,
                            conteoFallo = it.conteoActual,
                            conteoPromedio = it.conteoPromedio
                        )
                    }
                )
                dao.insertarDictamen(
                    EntidadDictamen(
                        idAtencion = idAtencion,
                        resultado = dictamen.resultado.texto,
                        observacion = dictamen.observacion.takeUnless { it.isBlank() }
                    )
                )
                if (diagnostico != null) insertarDiagnostico(idAtencion, diagnostico)
                Registro.i(
                    Registro.DATOS,
                    "Atención $idAtencion guardada: ${atencion.regimenes.size} regímenes, " +
                        "dictamen ${dictamen.resultado.texto}, diagnóstico ${diagnostico?.clase ?: "ninguno"}"
                )
                idAtencion
            }
        } catch (e: Exception) {
            Registro.e(Registro.DATOS, "No se pudo guardar la atención; no queda nada a medias", e)
            throw e
        }
    }

    /** Atenciones del vehículo, de la más reciente a la más antigua. */
    fun listarPorVehiculo(idVehiculo: Long): List<ResumenAtencion> = dao.listarResumenes(idVehiculo).map { f ->
        ResumenAtencion(
            idAtencion = f.idAtencion,
            fecha = f.fecha,
            resultado = ResultadoDictamen.desdeTexto(f.resultado) ?: ResultadoDictamen.PARCIAL,
            observacion = f.observacion.orEmpty(),
            clase = f.clase,
            probabilidad = f.probabilidad,
            versionModelo = f.versionModelo,
            respaldada = f.respaldada
        )
    }

    // Respaldo en la nube (versión 3). La base es del teléfono: cada atención recuerda la
    // cuenta con que se guardó y solo se sube a la nube de esa cuenta.

    fun pendientesDeRespaldo(uid: String): List<Long> = dao.pendientesDeRespaldo(uid)

    fun contarPendientes(uid: String): Int = dao.contarPendientes(uid)

    fun contarPendientesDeOtrasCuentas(uid: String): Int = dao.contarPendientesDeOtrasCuentas(uid)

    fun marcarRespaldada(idAtencion: Long, fecha: String = FechasIso.ahora()) {
        dao.marcarRespaldada(idAtencion, fecha)
        Registro.i(Registro.DATOS, "Atención $idAtencion marcada como respaldada ($fecha)")
    }

    /** Las atenciones de antes de la versión 3 pasan a la primera cuenta que inicia sesión. */
    fun asignarSinCuenta(uid: String): Int = dao.asignarSinCuenta(uid).also { n ->
        if (n > 0) Registro.i(Registro.DATOS, "$n atenciones sin cuenta asignadas a la sesión actual")
    }

    /** La atención con sus regímenes, lecturas, códigos, contadores, dictamen y diagnóstico. */
    fun obtener(idAtencion: Long): AtencionGuardada? {
        val cabecera = dao.obtenerAtencion(idAtencion) ?: return null

        val regimenes = dao.regimenes(idAtencion).map { r ->
            Regimen(
                id = r.idRegimen,
                tipo = TipoRegimen.valueOf(r.tipo),
                lecturas = dao.lecturas(r.idRegimen).map { it.aLectura() },
                muestrasUtiles = r.muestrasUtiles,
                descartadasTransitorio = r.descartadasTransitorio,
                descartadasTecho = r.descartadasTecho,
                frecuenciaHz = r.frecuenciaHz ?: 0.0
            )
        }
        val codigos = dao.codigos(idAtencion).map { CodigoDTC(it.codigo, TipoDTC.desdeTexto(it.tipo)) }
        val contadores = dao.contadores(idAtencion).map { ContadorModo06(it.cilindro, it.conteoFallo, it.conteoPromedio) }

        val atencion = Atencion(
            id = idAtencion,
            idVehiculo = cabecera.idVehiculo,
            fecha = cabecera.fecha,
            baroKpa = cabecera.baroKpa,
            codigos = codigos,
            contadoresModo06 = contadores,
            regimenes = regimenes.associateBy { it.tipo }
        )

        val diagnostico = leerDiagnostico(idAtencion, codigos)

        val dictamen = dao.dictamen(idAtencion)?.let { d ->
            Dictamen(
                resultado = ResultadoDictamen.desdeTexto(d.resultado) ?: ResultadoDictamen.PARCIAL,
                reglas = emptyList(),
                observacion = d.observacion.orEmpty(),
                // Una atención guardada sin diagnóstico es la de la regla 8 (CU-05 E1).
                faltanParametrosModelo = diagnostico == null
            )
        } ?: return null

        return AtencionGuardada(atencion, dictamen, diagnostico)
    }

    private fun insertarRegimen(idAtencion: Long, r: Regimen) {
        val idRegimen = dao.insertarRegimen(
            EntidadRegimen(
                idAtencion = idAtencion,
                tipo = r.tipo.name,
                muestrasUtiles = r.muestrasUtiles,
                descartadasTransitorio = r.descartadasTransitorio,
                descartadasTecho = r.descartadasTecho,
                frecuenciaHz = r.frecuenciaHz
            )
        )
        if (r.lecturas.isEmpty()) Registro.w(Registro.DATOS, "El régimen ${r.tipo} no tiene lecturas")
        dao.insertarLecturas(
            r.lecturas.map { l ->
                // Un parámetro no expuesto se guarda como NULL, nunca como cero.
                EntidadLectura(
                    idRegimen = idRegimen,
                    instante = l.instante,
                    rpm = l.rpm,
                    tempRefrigerante = l.tempRefrigerante,
                    stft = l.stft,
                    ltft = l.ltft,
                    cargaCalculada = l.cargaCalculada,
                    avanceEncendido = l.avanceEncendido,
                    maf = l.maf,
                    map = l.map,
                    // La velocidad del PID 0x0D es un entero en km/h.
                    velocidad = l.velocidad?.roundToLong()
                )
            }
        )
    }

    private fun insertarDiagnostico(idAtencion: Long, d: Diagnostico) {
        val idDiagnostico = dao.insertarDiagnostico(
            EntidadDiagnostico(
                idAtencion = idAtencion,
                clase = d.clase,
                probabilidad = ajustarProbabilidad(d.probabilidad),
                versionModelo = d.versionModelo
            )
        )
        dao.insertarParametros(
            d.parametrosDesviados.map { p ->
                EntidadParametroDesviado(
                    idDiagnostico = idDiagnostico,
                    parametro = p.parametro,
                    valor = p.valor,
                    referencia = p.referencia.takeUnless { it.isNaN() },
                    direccion = p.direccion.texto
                )
            }
        )
    }

    /**
     * El diagnóstico tal como se guardó. El nombre de la clase y la etiqueta y unidad de cada
     * parámetro no se guardan: la pantalla los toma del contrato del modelo por nombre.
     */
    private fun leerDiagnostico(idAtencion: Long, codigos: List<CodigoDTC>): Diagnostico? {
        val fila = dao.diagnostico(idAtencion) ?: return null
        val parametros = dao.parametros(fila.idDiagnostico).map { p ->
            ParametroDesviado(
                parametro = p.parametro,
                valor = p.valor,
                referencia = p.referencia ?: Double.NaN,
                direccion = Direccion.desdeTexto(p.direccion) ?: Direccion.MAYOR
            )
        }
        return Diagnostico(
            id = fila.idDiagnostico,
            clase = fila.clase,
            nombreClase = fila.clase,
            probabilidad = fila.probabilidad,
            versionModelo = fila.versionModelo,
            parametrosDesviados = parametros,
            codigosLeidos = codigos,
            modeloProvisional = fila.versionModelo.contains("provisional", ignoreCase = true)
        )
    }

    private companion object {
        /** El float32 del modelo puede pasarse de 1 por redondeo; un error mayor lo rechaza el CHECK. */
        fun ajustarProbabilidad(p: Double): Double = when {
            p > 1.0 && p <= 1.0 + 1e-5 -> 1.0
            p < 0.0 && p >= -1e-5 -> 0.0
            else -> p
        }

        fun EntidadLectura.aLectura() = Lectura(
            instante = instante,
            rpm = rpm,
            tempRefrigerante = tempRefrigerante,
            stft = stft,
            ltft = ltft,
            cargaCalculada = cargaCalculada,
            avanceEncendido = avanceEncendido,
            maf = maf,
            map = map,
            velocidad = velocidad?.toDouble()
        )
    }
}
