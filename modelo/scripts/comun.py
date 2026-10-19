#!/usr/bin/env python3
"""
Definiciones compartidas por los scripts de modelo/: rutas, características,
clases, corte de atenciones y los tres clasificadores candidatos.

No se ejecuta solo. Lo importan preparar_corte.py, comparar_modelos.py,
exportar_modelo.py y verificar_onnx.py, para que el corte, el orden de las
características y los hiperparámetros sean exactamente los mismos en la
comparación y en el modelo exportado.

Los scripts se corren con "python -I -X utf8". En modo -I Python no agrega la
carpeta del script a sys.path, así que cada script la agrega a mano antes de
importar este módulo, y apaga la escritura de __pycache__.
"""

import hashlib
import warnings
from pathlib import Path

import pandas as pd

# ---------------------------------------------------------------------------
# RUTAS (todas dentro de PFCapp/modelo/)
# ---------------------------------------------------------------------------

RAIZ = Path(__file__).resolve().parent.parent
CRUDO = RAIZ / "datos" / "crudo"
ARCHIVO_FICHA = RAIZ / "datos" / "ficha" / "ficha_registro_obd2.xlsx"
ARCHIVO_EXCLUIDAS = CRUDO / "atenciones_excluidas.csv"
ARCHIVO_CONSOLIDADOR = RAIZ / "referencia" / "consolidar_lecturas.py"
ARCHIVO_NUCLEO = RAIZ / "procesado" / "caracteristicas_nucleo.csv"
SALIDA = RAIZ / "salida"
ARCHIVO_TABLA_CORTE = SALIDA / "tabla_corte.csv"

FECHA_CORTE = "2026-10-18"
SEMILLAS = [0, 1, 2, 3, 4]
SEMILLA_FINAL = 0

# ---------------------------------------------------------------------------
# CARACTERÍSTICAS: núcleo universal de consolidar_lecturas.py, en su orden
# ---------------------------------------------------------------------------

# Mismo orden que NUCLEO_UNIVERSAL del consolidador y que el contrato del
# modelo provisional 0.1.0. El orden de esta lista es el orden del tensor de
# entrada del .onnx, así que no se cambia.
CARACTERISTICAS = [
    # (nombre, régimen, parámetro canónico, columna de la tabla lectura,
    #  estadístico, etiqueta para el mecánico, unidad)
    ("R2_rpm_media", "R2", "rpm", "rpm", "media",
     "Revoluciones en ralentí caliente", "rpm"),
    ("R2_rpm_desv", "R2", "rpm", "rpm", "desv",
     "Variación de rpm en ralentí caliente", "rpm"),
    ("R2_carga_calc_pct_media", "R2", "carga_calc_pct", "carga_calculada", "media",
     "Carga calculada en ralentí caliente", "%"),
    ("R2_stft_b1_pct_media", "R2", "stft_b1_pct", "stft", "media",
     "Ajuste de combustible de corto plazo en ralentí caliente", "%"),
    ("R2_stft_b1_pct_desv", "R2", "stft_b1_pct", "stft", "desv",
     "Variación del ajuste de corto plazo en ralentí caliente", "%"),
    ("R2_ltft_b1_pct_media", "R2", "ltft_b1_pct", "ltft", "media",
     "Ajuste de combustible de largo plazo en ralentí caliente", "%"),
    ("R2_avance_grados_media", "R2", "avance_grados", "avance_encendido", "media",
     "Avance de encendido en ralentí caliente", "°"),
    ("R2_avance_grados_desv", "R2", "avance_grados", "avance_encendido", "desv",
     "Variación del avance de encendido en ralentí caliente", "°"),
    ("R2_trim_total", "R2", "stft_b1_pct + ltft_b1_pct", "stft + ltft", "trim_total",
     "Ajuste de combustible total en ralentí caliente", "%"),
    ("R3_rpm_pendiente", "R3", "rpm", "rpm", "pendiente",
     "Tendencia de las rpm durante las aceleraciones", "rpm/s"),
    ("R3_carga_calc_pct_max", "R3", "carga_calc_pct", "carga_calculada", "max",
     "Carga calculada máxima en las aceleraciones", "%"),
    ("R3_stft_b1_pct_media", "R3", "stft_b1_pct", "stft", "media",
     "Ajuste de combustible de corto plazo en las aceleraciones", "%"),
    ("R3_ltft_b1_pct_media", "R3", "ltft_b1_pct", "ltft", "media",
     "Ajuste de combustible de largo plazo en las aceleraciones", "%"),
    ("R3_avance_grados_media", "R3", "avance_grados", "avance_encendido", "media",
     "Avance de encendido en las aceleraciones", "°"),
]
NOMBRES = [c[0] for c in CARACTERISTICAS]

# ---------------------------------------------------------------------------
# CLASES
# ---------------------------------------------------------------------------

# Clases de la Tabla 1, con el nombre que ve el mecánico.
NOMBRE_CLASE = {
    "C0": "Sin falla de motor",
    "C1": "Encendido y combustión",
    "C2": "Mezcla de aire y combustible",
    "C3": "Admisión y flujo de aire",
}
CLASES = sorted(NOMBRE_CLASE)
CLASES_BINARIO = ["C0", "falla"]

# ---------------------------------------------------------------------------
# CORTE
# ---------------------------------------------------------------------------

# Segundas variantes de una misma clase en un mismo vehículo. Decisión de Luis
# para este corte: entra solo la primera falla de cada clase por vehículo, así
# ningún vehículo aporta dos atenciones de la misma clase. ATN-039 ya viene
# fuera por atenciones_excluidas.csv, pero se lista igual para que la regla no
# dependa de ese archivo.
SEGUNDAS_VARIANTES = {
    "ATN-003": "segunda variante de C2 en VEH-001 (sonda de oxígeno); el vehículo ya tiene su C2 con la fuga de vacío (ATN-002)",
    "ATN-017": "segunda variante de C2 en VEH-005 (sonda de oxígeno); el vehículo ya tiene su C2 con el inyector (ATN-015)",
    "ATN-039": "segunda variante de C2 en VEH-010 (sonda de oxígeno); el vehículo ya tiene su C2 con el inyector (ATN-036)",
}

# Lo que el corte debe dar. Si el cálculo no da esto, preparar_corte.py se
# detiene y la diferencia se explica en corte.md.
CORTE_ESPERADO = {
    "C0": ["ATN-001", "ATN-006", "ATN-007", "ATN-009", "ATN-013",
           "ATN-021", "ATN-026", "ATN-033", "ATN-034"],
    "C1": ["ATN-005", "ATN-010", "ATN-014", "ATN-022", "ATN-037"],
    "C2": ["ATN-002", "ATN-015", "ATN-020", "ATN-023", "ATN-028"],
    "C3": ["ATN-004", "ATN-016", "ATN-019", "ATN-024", "ATN-027", "ATN-035"],
}

# ---------------------------------------------------------------------------
# CLASIFICADORES CANDIDATOS (hiperparámetros fijos, sin búsqueda)
# ---------------------------------------------------------------------------

# Orden de simplicidad para el desempate final: el primero es el más simple.
ORDEN_SIMPLICIDAD = ["kNN", "SVM", "RF"]
TOLERANCIA_EMPATE = 0.02
EPS_NUMERICO = 1e-9

DESCRIPCION_MODELO = {
    "RF": "RandomForestClassifier(n_estimators=300, resto por defecto, random_state=semilla)",
    "kNN": "KNeighborsClassifier(n_neighbors=3, weights='uniform', metric='euclidean')",
    "SVM": ("SVC(kernel='rbf', C=1.0, gamma='scale', probability=True, "
            "random_state=semilla)"),
}

# En scikit-learn 1.9 el parámetro probability de SVC está marcado como
# obsoleto (se quitará en 1.11). Sigue funcionando igual; se silencia solo ese
# aviso para que no tape la salida, y queda declarado en corte.md.
warnings.filterwarnings("ignore", message="The `probability` parameter was deprecated",
                        category=FutureWarning)


def nuevo_clasificador(nombre: str, semilla: int):
    """Clasificador sin entrenar, con los hiperparámetros fijados en corte.md."""
    from sklearn.ensemble import RandomForestClassifier
    from sklearn.neighbors import KNeighborsClassifier
    from sklearn.svm import SVC

    if nombre == "RF":
        return RandomForestClassifier(n_estimators=300, random_state=semilla)
    if nombre == "kNN":
        return KNeighborsClassifier(n_neighbors=3, weights="uniform", metric="euclidean")
    if nombre == "SVM":
        return SVC(kernel="rbf", C=1.0, gamma="scale", probability=True,
                   random_state=semilla)
    raise ValueError(f"clasificador desconocido: {nombre}")


def nuevo_pipeline(nombre: str, semilla: int):
    """Pipeline de imblearn: escalado, sobremuestreo y clasificador.

    El escalado y el sobremuestreo se ajustan solo con lo que recibe fit(), así
    que dentro de la validación cruzada viven dentro de cada pliegue de
    entrenamiento. El sobremuestreo actúa solo al entrenar: al predecir, el
    Pipeline de imblearn lo salta.
    """
    from imblearn.over_sampling import RandomOverSampler
    from imblearn.pipeline import Pipeline
    from sklearn.preprocessing import StandardScaler

    return Pipeline([
        ("escalado", StandardScaler()),
        ("sobremuestreo", RandomOverSampler(random_state=semilla)),
        ("clasificador", nuevo_clasificador(nombre, semilla)),
    ])


# ---------------------------------------------------------------------------
# UTILIDADES
# ---------------------------------------------------------------------------

def sha256(ruta: Path) -> str:
    return hashlib.sha256(ruta.read_bytes()).hexdigest()


def leer_ficha() -> pd.DataFrame:
    """Columnas de la hoja Atenciones que usa el corte."""
    ficha = pd.read_excel(ARCHIVO_FICHA, sheet_name="Atenciones")
    ficha = ficha[ficha["id_atencion"].astype(str).str.match(r"ATN-\d+$")]
    ficha = ficha[["id_atencion", "id_vehiculo", "marca", "modelo", "anio", "clase",
                   "confianza_etiqueta", "componente_confirmado"]].copy()
    ficha["id_atencion"] = ficha["id_atencion"].str.upper().str.strip()
    ficha["clase"] = ficha["clase"].astype("string").str.strip()
    return ficha


def leer_tabla_corte() -> pd.DataFrame:
    """La tabla del corte escrita por preparar_corte.py."""
    tabla = pd.read_csv(ARCHIVO_TABLA_CORTE, encoding="utf-8")
    columnas = [c for c in tabla.columns if c not in ("id_atencion", "id_vehiculo",
                                                     "clase", "confianza")]
    if columnas != NOMBRES:
        raise SystemExit(f"Las columnas de tabla_corte.csv no son las 14 del núcleo en orden:\n"
                         f"  {columnas}")
    return tabla
