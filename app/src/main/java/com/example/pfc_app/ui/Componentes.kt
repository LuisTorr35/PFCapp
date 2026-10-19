package com.example.pfc_app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pfc_app.ui.theme.AmarilloAviso
import com.example.pfc_app.ui.theme.GrisBorde
import com.example.pfc_app.ui.theme.Negro

/** Forma de las tarjetas y botones de las maquetas. */
val FormaTarjeta = RoundedCornerShape(14.dp)
val FormaBoton = RoundedCornerShape(10.dp)

/** Barra superior con "←" como texto (sin íconos) y una línea inferior, como en las maquetas. */
@Composable
fun BarraSuperior(titulo: String, alVolver: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (alVolver != null) {
                TextButton(onClick = alVolver, modifier = Modifier.testTag(EtiquetasPrueba.BOTON_VOLVER)) {
                    Text("←", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(titulo, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * Tarjeta con borde redondeado. Fusiona los textos de su contenido para que las pruebas
 * puedan buscarlos en el nodo con la etiqueta; si [alPulsar] no es null, se puede tocar.
 */
@Composable
fun Tarjeta(
    modifier: Modifier = Modifier,
    alPulsar: (() -> Unit)? = null,
    borde: Color = MaterialTheme.colorScheme.outline,
    fondo: Color = MaterialTheme.colorScheme.surface,
    grosorBorde: Int = 1,
    /** false en tarjetas con campos de texto, que deben conservar su propio nodo. */
    fusionar: Boolean = true,
    contenido: @Composable ColumnScope.() -> Unit
) {
    val base = modifier
        .fillMaxWidth()
        .clip(FormaTarjeta)
        .background(fondo)
        .border(BorderStroke(grosorBorde.dp, borde), FormaTarjeta)
    val conAccion = when {
        alPulsar != null -> base.clickable(onClick = alPulsar)
        fusionar -> base.semantics(mergeDescendants = true) {}
        else -> base
    }
    Column(
        modifier = conAccion.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = contenido
    )
}

@Composable
fun TituloTarjeta(texto: String, modifier: Modifier = Modifier) {
    Text(texto, modifier = modifier, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun TituloSeccion(texto: String, modifier: Modifier = Modifier) {
    Text(texto, modifier = modifier.padding(top = 8.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun TextoTarjeta(texto: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(texto, modifier = modifier, fontSize = 15.sp, lineHeight = 21.sp, color = color)
}

@Composable
fun TextoSecundario(texto: String, modifier: Modifier = Modifier) {
    Text(texto, modifier = modifier, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Botón principal negro de ancho completo; deshabilitado se ve con borde gris, como en la maqueta. */
@Composable
fun BotonPrincipal(texto: String, alPulsar: () -> Unit, modifier: Modifier = Modifier, habilitado: Boolean = true) {
    Button(
        onClick = alPulsar,
        enabled = habilitado,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = FormaBoton,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = GrisBorde
        ),
        border = if (habilitado) null else BorderStroke(1.dp, GrisBorde)
    ) {
        Text(texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Botón con borde negro, como "Atender", "Historial" o "Repetir R2". */
@Composable
fun BotonSecundario(texto: String, alPulsar: () -> Unit, modifier: Modifier = Modifier, habilitado: Boolean = true) {
    OutlinedButton(
        onClick = alPulsar,
        enabled = habilitado,
        modifier = modifier.heightIn(min = 44.dp),
        shape = FormaBoton,
        border = BorderStroke(1.dp, if (habilitado) MaterialTheme.colorScheme.outline else GrisBorde),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = GrisBorde
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(texto, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Aviso muy visible del modo demostración: el mecánico y el jurado deben saber que se
 * reproduce una atención grabada y no se está leyendo un vehículo real.
 */
@Composable
fun AvisoDemostracion(idGrabacion: String?, detalle: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(EtiquetasPrueba.AVISO_DEMO).semantics(mergeDescendants = true) {},
        shape = FormaTarjeta,
        color = AmarilloAviso,
        contentColor = Negro,
        border = BorderStroke(2.dp, Negro)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Modo demostración", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (idGrabacion != null) "Se reproduce la atención grabada $idGrabacion" else "Se reproduce una atención grabada",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (!detalle.isNullOrBlank()) Text(detalle, fontSize = 14.sp)
            Text("No se está leyendo un vehículo conectado.", fontSize = 14.sp)
        }
    }
}

/** Tarjeta de error con su motivo y, opcionalmente, un botón para volver. */
@Composable
fun TarjetaError(titulo: String, motivo: String, alVolver: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Tarjeta(
        modifier = modifier.testTag(EtiquetasPrueba.TARJETA_ERROR),
        borde = MaterialTheme.colorScheme.error,
        fondo = MaterialTheme.colorScheme.errorContainer,
        grosorBorde = 2
    ) {
        Text(titulo, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(motivo, fontSize = 15.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        if (alVolver != null) {
            Spacer(Modifier.height(4.dp))
            BotonSecundario("Volver", alVolver)
        }
    }
}

/** Barra de probabilidad de la maqueta de Diagnóstico: borde y relleno negro. */
@Composable
fun BarraProbabilidad(probabilidad: Double, modifier: Modifier = Modifier) {
    val fraccion = probabilidad.coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .testTag(EtiquetasPrueba.BARRA_PROBABILIDAD)
    ) {
        if (fraccion > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraccion)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            )
        }
    }
}

/** Barra de avance de un régimen (n/45). */
@Composable
fun BarraAvance(fraccion: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
    ) {
        val f = fraccion.coerceIn(0f, 1f)
        if (f > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(MaterialTheme.colorScheme.primary))
        }
    }
}
