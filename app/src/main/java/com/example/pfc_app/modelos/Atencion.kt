package com.example.pfc_app.modelos

/** Regímenes de operación del protocolo de medición. */
enum class TipoRegimen(val nombre: String) {
    R1("Ralentí en frío"),
    R2("Ralentí caliente"),
    R3("Aceleraciones en neutro"),
}

/**
 * Una muestra de un régimen: los parámetros leídos en un mismo instante.
 * Un parámetro que el vehículo no expone queda en null, nunca en cero.
 * [instante] son los segundos desde el inicio del régimen (t_seg del CSV).
 * rpm se guarda con decimales porque el PID 0x0C los entrega en cuartos de rpm
 * y el cálculo de características debe ser idéntico al de consolidar_lecturas.py.
 */
data class Lectura(
    val instante: Double,
    val rpm: Double? = null,
    val tempRefrigerante: Double? = null,
    val stft: Double? = null,
    val ltft: Double? = null,
    val cargaCalculada: Double? = null,
    val avanceEncendido: Double? = null,
    val maf: Double? = null,
    val map: Double? = null,
    val velocidad: Double? = null,
)

/** Un régimen capturado con sus lecturas y sus conteos de muestras. */
data class Regimen(
    val id: Long = 0,
    val tipo: TipoRegimen,
    val lecturas: List<Lectura>,
    val muestrasUtiles: Int = 0,
    val descartadasTransitorio: Int = 0,
    val descartadasTecho: Int = 0,
    val frecuenciaHz: Double = 0.0,
)

enum class TipoDTC(val texto: String) {
    ALMACENADO("almacenado"),
    PENDIENTE("pendiente");

    companion object {
        fun desdeTexto(texto: String?): TipoDTC =
            entries.firstOrNull { it.texto.equals(texto, ignoreCase = true) } ?: ALMACENADO
    }
}

/** Código de diagnóstico leído en la lectura inicial (modos 03 y 07). */
data class CodigoDTC(
    val codigo: String,
    val tipo: TipoDTC,
    val descripcion: String = "",
)

/**
 * Contadores de fallo de encendido de un cilindro (modo 06, MID A2 a A9).
 * [conteoActual] es la prueba MISFIRE_COUNT (TID 0C, ciclo actual) y
 * [conteoPromedio] la prueba MISFIRE_AVERAGE (TID 0B, promedio de diez ciclos).
 */
data class ContadorModo06(
    val cilindro: Int,
    val conteoActual: Int? = null,
    val conteoPromedio: Int? = null,
)

/**
 * La atención: registro completo de una sesión de medición sobre un vehículo.
 * [regimenes] contiene solo los regímenes capturados (R1 puede faltar).
 * [baroKpa] es la presión barométrica de la sesión (PID 0x33, o la presión del
 * múltiple con el motor apagado si el vehículo no expone la barométrica).
 */
data class Atencion(
    val id: Long = 0,
    val idVehiculo: Long,
    val fecha: String,
    val baroKpa: Double? = null,
    val protocolo: String? = null,
    val codigos: List<CodigoDTC> = emptyList(),
    val contadoresModo06: List<ContadorModo06> = emptyList(),
    val regimenes: Map<TipoRegimen, Regimen> = emptyMap(),
) {
    fun regimen(tipo: TipoRegimen): Regimen? = regimenes[tipo]

    fun agregarRegimen(r: Regimen): Atencion = copy(regimenes = regimenes + (r.tipo to r))

    fun estaCompleta(): Boolean = TipoRegimen.entries.all { it in regimenes }
}
