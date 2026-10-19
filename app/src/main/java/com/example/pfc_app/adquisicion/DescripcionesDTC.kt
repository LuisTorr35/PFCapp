package com.example.pfc_app.adquisicion

/**
 * Descripción en castellano de los códigos genéricos SAE J2012 más frecuentes en las
 * clases del esquema (admisión, mezcla, encendido). Un código que no está aquí se
 * muestra con la descripción del sistema que indica su prefijo.
 */
object DescripcionesDTC {
    private val TABLA = mapOf(
        "P0100" to "Circuito del caudalímetro (MAF)",
        "P0101" to "Caudalímetro (MAF) fuera de rango o con mal desempeño",
        "P0102" to "Caudalímetro (MAF), señal baja",
        "P0103" to "Caudalímetro (MAF), señal alta",
        "P0105" to "Circuito del sensor de presión del múltiple (MAP) o barométrica",
        "P0106" to "Sensor MAP o barométrica fuera de rango o con mal desempeño",
        "P0107" to "Sensor MAP o barométrica, señal baja",
        "P0108" to "Sensor MAP o barométrica, señal alta",
        "P0110" to "Circuito del sensor de temperatura del aire de admisión",
        "P0112" to "Sensor de temperatura del aire de admisión, señal baja",
        "P0113" to "Sensor de temperatura del aire de admisión, señal alta",
        "P0115" to "Circuito del sensor de temperatura del refrigerante",
        "P0117" to "Sensor de temperatura del refrigerante, señal baja",
        "P0118" to "Sensor de temperatura del refrigerante, señal alta",
        "P0120" to "Circuito del sensor de posición de la mariposa (TPS)",
        "P0121" to "Sensor de posición de la mariposa fuera de rango o con mal desempeño",
        "P0122" to "Sensor de posición de la mariposa, señal baja",
        "P0123" to "Sensor de posición de la mariposa, señal alta",
        "P0130" to "Circuito de la sonda de oxígeno (banco 1, sensor 1)",
        "P0131" to "Sonda de oxígeno (banco 1, sensor 1), voltaje bajo",
        "P0132" to "Sonda de oxígeno (banco 1, sensor 1), voltaje alto",
        "P0133" to "Sonda de oxígeno (banco 1, sensor 1), respuesta lenta",
        "P0134" to "Sonda de oxígeno (banco 1, sensor 1), sin actividad",
        "P0135" to "Calefactor de la sonda de oxígeno (banco 1, sensor 1)",
        "P0136" to "Circuito de la sonda de oxígeno (banco 1, sensor 2)",
        "P0141" to "Calefactor de la sonda de oxígeno (banco 1, sensor 2)",
        "P0171" to "Mezcla demasiado pobre (banco 1)",
        "P0172" to "Mezcla demasiado rica (banco 1)",
        "P0201" to "Circuito del inyector, cilindro 1",
        "P0202" to "Circuito del inyector, cilindro 2",
        "P0203" to "Circuito del inyector, cilindro 3",
        "P0204" to "Circuito del inyector, cilindro 4",
        "P0300" to "Fallo de encendido aleatorio o en varios cilindros",
        "P0301" to "Fallo de encendido, cilindro 1",
        "P0302" to "Fallo de encendido, cilindro 2",
        "P0303" to "Fallo de encendido, cilindro 3",
        "P0304" to "Fallo de encendido, cilindro 4",
        "P0305" to "Fallo de encendido, cilindro 5",
        "P0306" to "Fallo de encendido, cilindro 6",
        "P0325" to "Circuito del sensor de detonación",
        "P0335" to "Circuito del sensor de posición del cigüeñal",
        "P0340" to "Circuito del sensor de posición del árbol de levas",
        "P0343" to "Sensor de posición del árbol de levas, señal alta",
        "P0351" to "Circuito primario de la bobina de encendido A",
        "P0352" to "Circuito primario de la bobina de encendido B",
        "P0353" to "Circuito primario de la bobina de encendido C",
        "P0354" to "Circuito primario de la bobina de encendido D",
        "P0420" to "Eficiencia del catalizador bajo el umbral (banco 1)",
        "P0440" to "Sistema de control de emisiones evaporativas",
        "P0505" to "Sistema de control del ralentí",
        "P0506" to "Ralentí más bajo de lo esperado",
        "P0507" to "Ralentí más alto de lo esperado",
        "P0562" to "Voltaje del sistema bajo",
        "P0563" to "Voltaje del sistema alto",
    )

    fun descripcion(codigo: String): String =
        TABLA[codigo.uppercase()] ?: when (codigo.firstOrNull()?.uppercaseChar()) {
            'P' -> "Tren motriz (motor y transmisión)"
            'C' -> "Chasis"
            'B' -> "Carrocería"
            'U' -> "Red de comunicación entre módulos"
            else -> ""
        }
}
