package com.example.pfc_app.validacion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ResultadoRegla

/**
 * Regla 9 (obligatoria con degradación): la barométrica de la sesión está
 * registrada y entre 70 y 80 kPa (Arequipa, unos 74 kPa). Un valor cercano a 101
 * kPa es la atmósfera estándar que la unidad de control pone por defecto ante una
 * falla eléctrica, no una medición. El núcleo universal no usa la barométrica,
 * así que su incumplimiento degrada a parcial y no rechaza.
 */
class ReglaBarometrica(
    private val minimo: Double = MINIMO_KPA,
    private val maximo: Double = MAXIMO_KPA,
    private val porDefecto: Double = SUSTITUIDA_KPA,
) : ReglaBase() {
    override val numero = 9
    override val nivel = NivelRegla.OBLIGATORIA
    override val descripcion = "Barométrica de la sesión registrada y entre ${texto(minimo)} y ${texto(maximo)} kPa"

    override fun evaluar(atencion: Atencion): ResultadoRegla {
        val baro = atencion.baroKpa ?: return degrada("Barométrica no registrada: el vehículo no la entregó")
        return when {
            baro in minimo..maximo -> cumple()
            baro >= porDefecto -> degrada(
                "Barométrica sustituida por defecto (${texto(baro)} kPa, la atmósfera a nivel del mar): " +
                    "la unidad de control no la midió",
            )
            else -> degrada(
                "Barométrica fuera de rango (${texto(baro)} kPa; se esperan entre ${texto(minimo)} y ${texto(maximo)} kPa)",
            )
        }
    }

    companion object {
        const val MINIMO_KPA = 70.0
        const val MAXIMO_KPA = 80.0

        /** Desde aquí se considera el 101 kPa de atmósfera estándar puesto por la unidad de control. */
        const val SUSTITUIDA_KPA = 95.0
    }
}
