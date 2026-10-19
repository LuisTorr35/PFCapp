package com.example.pfc_app.modelos

/** Dictamen de la validación (RF-04). Solo completa y parcial pasan al historial. */
enum class ResultadoDictamen(val texto: String) {
    COMPLETA("completa"),
    PARCIAL("parcial"),
    RECHAZADA("rechazada");

    companion object {
        fun desdeTexto(texto: String?): ResultadoDictamen? =
            entries.firstOrNull { it.texto.equals(texto, ignoreCase = true) }
    }
}

/** Nivel de una regla de pfc_reglas_validacion_v2.md. */
enum class NivelRegla(val texto: String) {
    OBLIGATORIA("obligatoria"),
    GRADUADA("graduada"),
    CONDICIONAL("condicional"),
}

/** Qué produce una regla al evaluarse sobre una atención. */
enum class EfectoRegla {
    /** Se cumple, o no aplica. */
    CUMPLE,
    /** Degrada la atención a parcial. */
    DEGRADA,
    /** Rechaza la atención. */
    RECHAZA,
    /** No rechaza ni degrada, pero la atención se guarda sin diagnóstico (regla 8). */
    SIN_DIAGNOSTICO,
}

data class ResultadoRegla(
    val numero: Int,
    val nivel: NivelRegla,
    val descripcion: String,
    val efecto: EfectoRegla,
    /** Régimen al que se refiere el incumplimiento, si corresponde a uno. */
    val regimen: TipoRegimen? = null,
    /** Motivo legible para el mecánico, vacío si se cumple. */
    val motivo: String = "",
)

data class Dictamen(
    val resultado: ResultadoDictamen,
    val reglas: List<ResultadoRegla>,
    val regimenesARepetir: List<TipoRegimen> = emptyList(),
    /** Comentario del dictamen: reglas graduadas incumplidas o motivo del rechazo. */
    val observacion: String = "",
    /** true si el vehículo no expone los parámetros que pide el modelo (regla 8, CU-05 E1). */
    val faltanParametrosModelo: Boolean = false,
) {
    val reglasIncumplidas: List<ResultadoRegla> get() = reglas.filter { it.efecto != EfectoRegla.CUMPLE }

    /** Se puede diagnosticar: no rechazada y con los parámetros que pide el modelo. */
    fun permiteInferencia(): Boolean = resultado != ResultadoDictamen.RECHAZADA && !faltanParametrosModelo

    /** Se puede guardar en el historial (con o sin diagnóstico). */
    fun permiteGuardar(): Boolean = resultado != ResultadoDictamen.RECHAZADA
}
