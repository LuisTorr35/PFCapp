package com.example.pfc_app.adquisicion

import com.example.pfc_app.modelos.TipoRegimen

/**
 * Corte de los regímenes en modo real, igual que registrar_obd.py v4: el régimen
 * para cuando pasa el piso Y junta el objetivo de muestras útiles, o al llegar al
 * techo. R1 corta además cuando el refrigerante llega a 40 °C, R3 no corta a mitad
 * de una rampa, y todo hueco de más de 6 s entre dos filas (un corte del enlace)
 * pausa el reloj de piso y techo. El techo absoluto, techo + 120 s, evita que un
 * enlace que no deja de caerse alargue la captura sin fin.
 */
data class ConfiguracionCaptura(
    val objetivoUtiles: Map<TipoRegimen, Int> = TipoRegimen.entries.associateWith { 55 },
    val pisoSeg: Map<TipoRegimen, Double> = mapOf(TipoRegimen.R1 to 90.0, TipoRegimen.R2 to 120.0, TipoRegimen.R3 to 120.0),
    val techoSeg: Map<TipoRegimen, Double> = mapOf(TipoRegimen.R1 to 120.0, TipoRegimen.R2 to 150.0, TipoRegimen.R3 to 150.0),
    val techoAbsolutoExtraSeg: Double = 120.0,
    val huecoSeg: Double = 6.0,
    /** Periodo nominal de una fila (--hz 1.0), que se descuenta de cada hueco. */
    val periodoSeg: Double = 1.0,
    /** R1 solo con el motor bajo 40 °C, y corta al llegar a 40. */
    val ectTopeR1: Double = 40.0,
    /** R2 espera a que el refrigerante supere 85 °C. */
    val ectR2: Double = 85.0,
    val consultaCalentamientoMs: Long = 5_000,
    val esperaCalentamientoMaxMin: Int = 25,
    /** Lecturas seguidas sin refrigerante tras las que se deja de esperar el calentamiento. */
    val calentamientoSinLectura: Int = 3,
    /** Rondas de reconexión del servicio cuando el adaptador no logró reconectar solo. */
    val rondasReconexion: Int = 3,
    val pausaReconexionMs: Long = 500,
    /** En demostración, cuánto se muestra cada instrucción antes de avanzar sola. */
    val pausaInstruccionDemoMs: Long = 1_500,
    /** Mínimo de útiles que se muestra como objetivo en la demostración (el de las reglas 1 y 2). */
    val objetivoDemostracion: Int = 45,
) {
    fun objetivo(tipo: TipoRegimen): Int = objetivoUtiles.getValue(tipo)
    fun piso(tipo: TipoRegimen): Double = pisoSeg.getValue(tipo)
    fun techo(tipo: TipoRegimen): Double = techoSeg.getValue(tipo)
}
