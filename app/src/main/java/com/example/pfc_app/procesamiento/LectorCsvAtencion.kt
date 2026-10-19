package com.example.pfc_app.procesamiento

import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen

/**
 * Arma una [Atencion] desde los CSV de una atención grabada por registrar_obd.py:
 * ATN-###_R1.csv, _R2.csv, _R3.csv, _DTC.csv, _modo06.csv, _contacto.csv,
 * _pids.csv y _sesion.txt. Lo usan las pruebas de equivalencia (RF-05) y el modo
 * demostración. Sin dependencias de Android.
 *
 * Los encabezados se mapean con la misma regla que consolidar_lecturas.py y cada
 * celda se convierte como a_numerico(); una celda vacía queda en null, nunca en
 * cero. Un t_seg vacío queda como NaN en [Lectura.instante].
 */
class LectorCsvAtencion(
    private val fuente: FuenteArchivos,
    private val procesador: ProcesadorCaracteristicas = ProcesadorCaracteristicas(),
) {

    /** La atención completa: regímenes contados, códigos, modo 06, barométrica y protocolo. */
    fun leer(idAtencion: String, idVehiculo: Long = 0, id: Long = 0): Atencion {
        val tablas = TipoRegimen.entries.associateWith { tablaRegimen(idAtencion, it) }
        val regimenes = LinkedHashMap<TipoRegimen, Regimen>()
        for ((tipo, tabla) in tablas) {
            if (tabla == null) continue
            regimenes[tipo] = procesador.contarMuestras(Regimen(tipo = tipo, lecturas = lecturas(tabla)))
        }
        val atencion = Atencion(
            id = id,
            idVehiculo = idVehiculo,
            fecha = fecha(idAtencion),
            baroKpa = barometrica(idAtencion),
            mapContactoKpa = mapContacto(idAtencion),
            protocolo = protocolo(idAtencion),
            codigos = leerCodigos(idAtencion),
            contadoresModo06 = leerModo06(idAtencion),
            regimenes = regimenes,
        )
        Registro.d(
            Registro.PROCESAMIENTO,
            "Leída $idAtencion de $fuente: " +
                regimenes.values.joinToString { "${it.tipo} ${it.lecturas.size} filas/${it.muestrasUtiles} útiles" } +
                ", ${atencion.codigos.size} códigos, barométrica ${atencion.baroKpa}, " +
                "MAP del contacto ${atencion.mapContactoKpa}",
        )
        return atencion
    }

    /** Lecturas del régimen grabado, o null si no hay archivo. */
    fun leerLecturas(idAtencion: String, tipo: TipoRegimen): List<Lectura>? =
        tablaRegimen(idAtencion, tipo)?.let { lecturas(it) }

    fun tieneRegimen(idAtencion: String, tipo: TipoRegimen): Boolean = fuente.existe(nombre(idAtencion, tipo.name))

    /** Códigos de _DTC.csv: tipo "Almacenado" (modo 03) o "Pendiente" (modo 07). */
    fun leerCodigos(idAtencion: String): List<CodigoDTC> {
        val t = tabla(nombre(idAtencion, "DTC")) ?: return emptyList()
        return t.comoMapas().mapNotNull { f ->
            val codigo = f["codigo"]?.trim().orEmpty()
            if (codigo.isEmpty()) null
            else CodigoDTC(codigo, TipoDTC.desdeTexto(f["tipo"]), f["descripcion"]?.trim().orEmpty())
        }
    }

    /** Contadores de _modo06.csv: MISFIRE_COUNT es el ciclo actual y MISFIRE_AVERAGE el promedio. */
    fun leerModo06(idAtencion: String): List<ContadorModo06> {
        val t = tabla(nombre(idAtencion, "modo06")) ?: return emptyList()
        val porCilindro = sortedMapOf<Int, ContadorModo06>()
        for (f in t.comoMapas()) {
            val cil = MapaColumnas.aNumero(f["cilindro"])?.toInt() ?: continue
            val prueba = f["prueba"]?.uppercase().orEmpty()
            val valor = MapaColumnas.aNumero(f["valor"])?.toInt() ?: continue
            val previo = porCilindro[cil] ?: ContadorModo06(cil)
            porCilindro[cil] = when {
                "AVERAGE" in prueba -> previo.copy(conteoPromedio = valor)
                "COUNT" in prueba -> previo.copy(conteoActual = valor)
                else -> previo
            }
        }
        return porCilindro.values.toList()
    }

    /** _contacto.csv como campo → valor (vacío si no hay archivo). */
    fun leerContacto(idAtencion: String): Map<String, String> {
        val t = tabla(nombre(idAtencion, "contacto")) ?: return emptyMap()
        val salida = LinkedHashMap<String, String>()
        for (f in t.filas) {
            val campo = f.getOrNull(0)?.trim() ?: continue
            if (campo.isNotEmpty()) salida[campo] = f.getOrNull(1)?.trim().orEmpty()
        }
        return salida
    }

    /** Comandos que declaraba la máscara de soportados (_pids.csv). */
    fun leerPids(idAtencion: String): List<String> {
        val t = tabla(nombre(idAtencion, "pids")) ?: return emptyList()
        return t.comoMapas().mapNotNull { it["comando"]?.trim()?.takeIf(String::isNotEmpty) }
    }

    /** Bitácora _sesion.txt, campo → valor de las líneas "Campo:  valor". */
    fun leerSesion(idAtencion: String): Map<String, String> {
        val texto = fuente.leerTexto("${idAtencion}_sesion.txt") ?: return emptyMap()
        val salida = LinkedHashMap<String, String>()
        for (linea in texto.lineSequence()) {
            val i = linea.indexOf(':')
            if (i <= 0) continue
            salida.putIfAbsent(linea.substring(0, i).trim(), linea.substring(i + 1).trim())
        }
        return salida
    }

    /** Protocolo del contacto o de la bitácora de sesión. */
    fun protocolo(idAtencion: String): String? =
        leerContacto(idAtencion)["protocolo"]?.takeIf { it.isNotBlank() }
            ?: leerSesion(idAtencion)["Protocolo"]?.takeIf { it.isNotBlank() && it != "simulado" }

    fun fecha(idAtencion: String): String =
        leerContacto(idAtencion)["fecha_hora"]?.takeIf { it.isNotBlank() }
            ?: leerSesion(idAtencion)["Fecha y hora"].orEmpty()

    /**
     * Barométrica que entregó el vehículo (PID 0x33): la del contacto o, si falta, la
     * constante de sesión de las filas del régimen. La MAP con el motor apagado va
     * aparte, en [mapContacto].
     */
    fun barometrica(idAtencion: String): Double? {
        val contacto = leerContacto(idAtencion)
        MapaColumnas.aNumero(contacto["baro_kpa"])?.let { return it }
        for (tipo in listOf(TipoRegimen.R2, TipoRegimen.R3, TipoRegimen.R1)) {
            val t = tablaRegimen(idAtencion, tipo) ?: continue
            val i = MapaColumnas.mapear(t.encabezados)["baro_kpa"] ?: continue
            t.filas.firstNotNullOfOrNull { MapaColumnas.aNumero(it.getOrNull(i)) }?.let { return it }
        }
        return null
    }

    /** Presión del múltiple con el motor apagado, del contacto (map_koeo_kpa). */
    fun mapContacto(idAtencion: String): Double? = MapaColumnas.aNumero(leerContacto(idAtencion)["map_koeo_kpa"])

    /**
     * Familia de gestión: por la máscara de _pids.csv (MAF e INTAKE_PRESSURE), por
     * gestion_motor del contacto o, si faltan los dos, por qué columnas traen datos.
     */
    fun familiaGestion(idAtencion: String): FamiliaGestion? {
        val pids = leerPids(idAtencion)
        if (pids.isNotEmpty()) {
            FamiliaGestion.desdeSensores("MAF" in pids, "INTAKE_PRESSURE" in pids)?.let { return it }
        }
        when (leerContacto(idAtencion)["gestion_motor"]) {
            "caudalimetro" -> return FamiliaGestion.MAF
            "densidad_velocidad" -> return FamiliaGestion.MAP
            "ambos" -> return FamiliaGestion.AMBOS
        }
        var maf = false
        var map = false
        for (tipo in TipoRegimen.entries) {
            val l = leerLecturas(idAtencion, tipo) ?: continue
            maf = maf || l.any { it.maf != null }
            map = map || l.any { it.map != null }
        }
        return FamiliaGestion.desdeSensores(maf, map)
    }

    private fun nombre(idAtencion: String, sufijo: String) = "${idAtencion}_$sufijo.csv"

    private fun tabla(nombre: String): TablaCsv? = fuente.leerTexto(nombre)?.let { Csv.leer(it) }

    private fun tablaRegimen(idAtencion: String, tipo: TipoRegimen): TablaCsv? = tabla(nombre(idAtencion, tipo.name))

    private fun lecturas(t: TablaCsv): List<Lectura> {
        val m = MapaColumnas.mapear(t.encabezados)
        fun col(fila: List<String>, canonico: String): Double? =
            m[canonico]?.let { MapaColumnas.aNumero(fila.getOrNull(it)) }
        return t.filas.map { f ->
            Lectura(
                instante = col(f, "t_seg") ?: Double.NaN,
                rpm = col(f, "rpm"),
                tempRefrigerante = col(f, "ect_c"),
                stft = col(f, "stft_b1_pct"),
                ltft = col(f, "ltft_b1_pct"),
                cargaCalculada = col(f, "carga_calc_pct"),
                avanceEncendido = col(f, "avance_grados"),
                maf = col(f, "maf_gps"),
                map = col(f, "map_kpa"),
                velocidad = col(f, "vel_kmh"),
            )
        }
    }
}
