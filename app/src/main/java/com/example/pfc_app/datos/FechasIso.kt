package com.example.pfc_app.datos

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Fechas de la base en ISO 8601 local, por ejemplo 2026-09-28T11:20:00 (Capítulo 6).
 * Con un solo formato, ordenar el texto equivale a ordenar por fecha.
 */
object FechasIso {
    private val FORMATO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun ahora(): String = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(FORMATO)

    fun formatear(fecha: LocalDateTime): String = fecha.truncatedTo(ChronoUnit.SECONDS).format(FORMATO)

    /** Interpreta fechas ISO locales, con zona horaria o instantes UTC; null si no se entiende. */
    fun interpretar(texto: String?, zona: ZoneId = ZoneId.systemDefault()): LocalDateTime? {
        val t = texto?.trim().orEmpty()
        if (t.isEmpty()) return null
        return runCatching { LocalDateTime.parse(t) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(t).atZoneSameInstant(zona).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.ofInstant(Instant.parse(t), zona) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t.replace(' ', 'T')) }.getOrNull()
    }

    /** La fecha en el formato de la base; si no se entiende, la fecha y hora actuales. */
    fun normalizar(texto: String?): String = interpretar(texto)?.let(::formatear) ?: ahora()
}
