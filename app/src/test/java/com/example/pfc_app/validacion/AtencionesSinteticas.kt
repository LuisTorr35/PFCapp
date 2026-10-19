package com.example.pfc_app.validacion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.TipoRegimen

/**
 * Atenciones sintéticas para probar cada regla por separado: [buena] cumple las
 * nueve, y cada prueba cambia solo lo que su regla mira.
 */
object AtencionesSinteticas {

    /** Ralentí: [n] filas cada 1,7 s, el transitorio de 15 s deja n − 9 útiles. */
    fun ralenti(
        tipo: TipoRegimen,
        n: Int = 70,
        ect: (Int) -> Double? = { 90.0 },
        rpm: (Int) -> Double? = { 750.0 + (it % 5) },
        ltft: (Int) -> Double? = { 2.344 },
        velocidad: Double? = 0.0,
    ): Regimen = Regimen(
        tipo = tipo,
        lecturas = (0 until n).map { i ->
            Lectura(
                instante = Math.round(i * 1.7 * 100) / 100.0,
                rpm = rpm(i),
                tempRefrigerante = ect(i),
                stft = -1.563 + (i % 3),
                ltft = ltft(i),
                cargaCalculada = 22.353,
                avanceEncendido = 8.0 + (i % 4) * 0.5,
                map = 30.0,
                velocidad = velocidad,
            )
        },
    )

    /** R3 con [rampas] subidas de 800 a [pico] rpm y vuelta al ralentí, 20 filas por rampa. */
    fun aceleraciones(
        rampas: Int = 3,
        pico: Double = 3100.0,
        ltft: (Int) -> Double? = { 2.344 },
        velocidad: (Int) -> Double? = { 0.0 },
        filasExtra: Int = 20,
    ): Regimen {
        val perfil = ArrayList<Double>()
        repeat(filasExtra) { perfil += 800.0 }
        repeat(rampas) {
            for (k in 0 until 10) perfil += 800.0 + (pico - 800.0) * k / 9
            for (k in 0 until 10) perfil += pico - (pico - 800.0) * k / 9
        }
        return Regimen(
            tipo = TipoRegimen.R3,
            lecturas = perfil.mapIndexed { i, rpm ->
                Lectura(
                    instante = Math.round(i * 1.5 * 100) / 100.0,
                    rpm = rpm,
                    tempRefrigerante = 92.0,
                    stft = 0.781,
                    ltft = ltft(i),
                    cargaCalculada = 20.0 + rpm / 100,
                    avanceEncendido = 15.0,
                    velocidad = velocidad(i),
                )
            },
        )
    }

    fun buena(
        r1: Regimen? = ralenti(TipoRegimen.R1, ect = { 30.0 + it * 0.1 }),
        r2: Regimen? = ralenti(TipoRegimen.R2),
        r3: Regimen? = aceleraciones(),
        baro: Double? = 74.0,
        mapContacto: Double? = null,
    ): Atencion = Atencion(
        idVehiculo = 1,
        fecha = "2026-10-19 10:00",
        baroKpa = baro,
        mapContactoKpa = mapContacto,
        regimenes = listOfNotNull(r1, r2, r3).associateBy { it.tipo },
    )
}
