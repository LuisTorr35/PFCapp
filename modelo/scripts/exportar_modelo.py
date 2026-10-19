#!/usr/bin/env python3
"""
Entrena el modelo elegido por comparar_modelos.py con las 25 atenciones del
corte y lo exporta a ONNX junto con su contrato.

Sigue siendo un modelo PROVISIONAL (0.2.0-provisional), no el modelo definitivo
de la tesis. Reemplaza al 0.1.0 (regresión logística) de modelo_provisional/ y
mantiene el mismo esquema de contrato que la aplicación ya lee.

Entrenamiento, igual que dentro de cada pliegue de la comparación:
    escalado ajustado a las 25 atenciones -> sobremuestreo del conjunto ya
    escalado -> clasificador, con la semilla 0
Lo que se exporta es un Pipeline de scikit-learn con el escalado y el
clasificador. El sobremuestreo no va, porque solo actúa al entrenar.

Entradas (dentro de PFCapp/modelo/):
    salida/tabla_corte.csv         escrita por preparar_corte.py
    salida/comparacion.json        escrita por comparar_modelos.py (elegido y métricas)
    referencia/consolidar_lecturas.py  solo para registrar su huella

Salidas en salida/:
    modelo_pfc.onnx                clasificador exportado con skl2onnx
    contrato_modelo.json           lo que la aplicación lee junto al .onnx
    modelo_sklearn.pkl             el mismo modelo en scikit-learn, para la verificación

Uso, desde PFCapp/modelo:
    ..\\modelo_provisional\\.venv\\Scripts\\python -I -X utf8 scripts\\exportar_modelo.py
"""

import json
import pickle
import sys
from datetime import date
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import imblearn  # noqa: E402
import numpy as np  # noqa: E402
import onnx  # noqa: E402
import onnxruntime  # noqa: E402
import pandas as pd  # noqa: E402
import skl2onnx  # noqa: E402
import sklearn  # noqa: E402
from skl2onnx import convert_sklearn  # noqa: E402
from skl2onnx.common.data_types import FloatTensorType  # noqa: E402
from sklearn.pipeline import Pipeline  # noqa: E402

from comun import (ARCHIVO_CONSOLIDADOR, ARCHIVO_NUCLEO, CARACTERISTICAS, CLASES,  # noqa: E402
                   DESCRIPCION_MODELO, FECHA_CORTE, NOMBRE_CLASE, NOMBRES, SALIDA,
                   SEGUNDAS_VARIANTES, SEMILLA_FINAL, leer_ficha, leer_tabla_corte,
                   nuevo_pipeline, sha256)

VERSION_MODELO = "0.2.0-provisional"
NOMBRE_ONNX = "modelo_pfc.onnx"
NOMBRE_ENTRADA = "caracteristicas"

# Opset fijo y conservador para que lo cargue ONNX Runtime para Android sin
# depender de la versión más nueva de la biblioteca.
OPSET = {"": 17, "ai.onnx.ml": 3}

NOMBRE_LARGO = {"RF": "Random Forest", "kNN": "k-NN", "SVM": "SVM"}

# Una clase se declara en el modelo si tiene al menos estas atenciones y estos
# vehículos distintos (mismo umbral que el modelo 0.1.0).
MIN_ATENCIONES_CLASE = 5
MIN_VEHICULOS_CLASE = 5


def referencia_c0(tabla: pd.DataFrame) -> dict:
    """Valores de referencia sin falla de cada característica, con las C0 del corte.

    Los límites siguen la regla de Tukey sobre las atenciones sanas: un valor
    por encima de Q3 + 1,5 RIC se marca "mayor" y uno por debajo de Q1 - 1,5 RIC
    se marca "menor". La aplicación solo compara contra los límites ya
    calculados, así que cambiar la regla no exige reentrenar el modelo.
    """
    sanos = tabla[tabla["clase"] == "C0"]
    ref = {}
    for nombre in NOMBRES:
        v = sanos[nombre].astype(float).to_numpy()
        q1, mediana, q3 = np.percentile(v, [25, 50, 75])
        ric = q3 - q1
        ref[nombre] = {
            "n": int(len(v)),
            "media": round(float(v.mean()), 4),
            "desv": round(float(v.std(ddof=1)), 4),
            "mediana": round(float(mediana), 4),
            "q1": round(float(q1), 4),
            "q3": round(float(q3), 4),
            "min": round(float(v.min()), 4),
            "max": round(float(v.max()), 4),
            "limite_inferior": round(float(q1 - 1.5 * ric), 4),
            "limite_superior": round(float(q3 + 1.5 * ric), 4),
        }
    return ref


def main() -> None:
    SALIDA.mkdir(parents=True, exist_ok=True)
    tabla = leer_tabla_corte()
    comparacion = json.loads((SALIDA / "comparacion.json").read_text(encoding="utf-8"))
    elegido = comparacion["elegido"]
    if comparacion["corte"]["ids_atenciones"] != tabla["id_atencion"].tolist():
        raise SystemExit("comparacion.json no corresponde a la tabla_corte.csv actual. "
                         "Correr de nuevo comparar_modelos.py.")

    X = tabla[NOMBRES].to_numpy(dtype=np.float64)
    y = tabla["clase"].to_numpy(dtype=object)

    resumen_clases = (tabla.groupby("clase")
                      .agg(atenciones=("id_atencion", "count"),
                           vehiculos=("id_vehiculo", "nunique")))
    descartadas = [c for c in CLASES
                   if c not in resumen_clases.index
                   or resumen_clases.loc[c, "atenciones"] < MIN_ATENCIONES_CLASE
                   or resumen_clases.loc[c, "vehiculos"] < MIN_VEHICULOS_CLASE]
    if descartadas:
        raise SystemExit(f"Clases sin datos suficientes: {descartadas}")

    # Entrenamiento con el mismo Pipeline de imblearn que la comparación. Al
    # ajustarlo, el escalado se ajusta a las 25 atenciones y el sobremuestreo
    # actúa sobre el conjunto ya escalado.
    entrenado = nuevo_pipeline(elegido, SEMILLA_FINAL).fit(X, y)
    escalado = entrenado.named_steps["escalado"]
    clasificador = entrenado.named_steps["clasificador"]
    _, y_sobre = entrenado.named_steps["sobremuestreo"].fit_resample(escalado.transform(X), y)
    filas_sobremuestreadas = {c: int((y_sobre == c).sum()) for c in CLASES}

    # Modelo exportado: escalado y clasificador ya ajustados, sin el sobremuestreo.
    modelo = Pipeline([("escalado", escalado), ("clasificador", clasificador)])
    assert list(modelo.classes_) == CLASES
    assert np.array_equal(modelo.predict(X), entrenado.predict(X))
    assert np.allclose(modelo.predict_proba(X), entrenado.predict_proba(X), atol=0, rtol=0)
    aciertos_propios = int((modelo.predict(X) == y).sum())

    # Exportación. zipmap=False deja las probabilidades como un tensor float
    # [n, clases] en el orden de "clases", que es lo cómodo desde Kotlin.
    onx = convert_sklearn(
        modelo,
        name="modelo_pfc",
        initial_types=[(NOMBRE_ENTRADA, FloatTensorType([None, len(NOMBRES)]))],
        options={id(clasificador): {"zipmap": False}},
        target_opset=OPSET,
    )
    # skl2onnx declara dos veces el dominio por defecto en opset_import (también
    # pasaba en el 0.1.0). Se deja una sola entrada por dominio.
    unicos = {}
    for d in onx.opset_import:
        unicos[d.domain] = max(unicos.get(d.domain, 0), d.version)
    del onx.opset_import[:]
    for dominio, version in unicos.items():
        onx.opset_import.add(domain=dominio, version=version)
    onx.doc_string = (f"Modelo PROVISIONAL {VERSION_MODELO} de la aplicación PFC "
                      f"({NOMBRE_LARGO[elegido]}). No es el modelo definitivo de la tesis.")
    meta = onx.metadata_props.add()
    meta.key, meta.value = "version_modelo", VERSION_MODELO
    meta = onx.metadata_props.add()
    meta.key, meta.value = "provisional", "true"
    onnx.checker.check_model(onx, full_check=True)
    ruta_onnx = SALIDA / NOMBRE_ONNX
    ruta_onnx.write_bytes(onx.SerializeToString())

    entradas = [{"nombre": i.name, "tipo": "float32", "forma": [-1, len(NOMBRES)]}
                for i in onx.graph.input]
    salidas = []
    for o in onx.graph.output:
        if o.name == "probabilities":
            salidas.append({"nombre": o.name, "tipo": "float32", "forma": [-1, len(CLASES)],
                            "descripcion": "probabilidad de cada clase, en el orden de 'clases'"})
        else:
            salidas.append({"nombre": o.name, "tipo": "string", "forma": [-1],
                            "descripcion": "código de la clase más probable"})
    if [s["nombre"] for s in salidas] != ["label", "probabilities"]:
        raise SystemExit(f"Salidas inesperadas del .onnx: {[s['nombre'] for s in salidas]}")

    # Lo que quedó fuera del corte entre las válidas del consolidador.
    nucleo = pd.read_csv(ARCHIVO_NUCLEO, encoding="utf-8-sig")
    clase_ficha = dict(zip(*leer_ficha()[["id_atencion", "clase"]].to_numpy().T))
    fuera_clase = sorted(a for a in nucleo["id_atencion"] if clase_ficha.get(a) not in CLASES)
    con_clase = nucleo[~nucleo["id_atencion"].isin(fuera_clase + list(SEGUNDAS_VARIANTES))]
    fuera_vacia = {r["id_atencion"]: [n for n in NOMBRES if pd.isna(r[n])]
                   for _, r in con_clase[con_clase[NOMBRES].isna().any(axis=1)].iterrows()}

    referencia = referencia_c0(tabla)
    por_clase = {c: {"atenciones": int(resumen_clases.loc[c, "atenciones"]),
                     "vehiculos": int(resumen_clases.loc[c, "vehiculos"])} for c in CLASES}
    m4 = comparacion["modelos"][elegido]["cuatro_clases"]
    m2 = comparacion["modelos"][elegido]["binario"]
    perm = comparacion["permutacion"]
    redondeo = lambda d: {"media": round(d["media"], 3), "desv": round(d["desv"], 3),  # noqa: E731
                          "por_semilla": [round(v, 3) for v in d["por_semilla"]]}

    contrato = {
        "nombre": "Modelo provisional de diagnóstico de fallas de motor",
        "version_modelo": VERSION_MODELO,
        "provisional": True,
        "aviso": ("Modelo PROVISIONAL, declarado como tal. No es el modelo definitivo de la "
                  f"tesis: se entrenó con las {len(tabla)} atenciones de "
                  f"{tabla['id_vehiculo'].nunique()} vehículos del corte del "
                  f"{pd.Timestamp(FECHA_CORTE):%d/%m/%Y} para desarrollar y demostrar la "
                  "aplicación, como prevé la Tabla 4 del documento del proyecto. Su exactitud "
                  "no está validada y la aplicación debe mostrar la versión del modelo junto "
                  "al diagnóstico."),
        "fecha_creacion": f"{date.today():%Y-%m-%d}",
        "archivo_onnx": NOMBRE_ONNX,
        "sha256_onnx": sha256(ruta_onnx),
        "formato": {
            "ir_version": int(onx.ir_version),
            "opset": {d.domain or "ai.onnx": int(d.version) for d in onx.opset_import},
            "entradas": entradas,
            "salidas": salidas,
            "nota": ("La fila se pasa como float32 en el orden de 'caracteristicas'. "
                     "Ninguna puede ir vacía: el modelo no imputa."),
            "nota_empates": ("Las probabilidades del bosque son proporciones de votos de los "
                             "árboles, así que dos clases pueden quedar empatadas. En un empate "
                             "exacto, 'label' elige la que salga mayor por redondeo en float32, "
                             "y scikit-learn elige la de menor índice. Si la diferencia entre "
                             "las dos más probables es menor que 1e-6, conviene tratarlas como "
                             "empatadas. Ver verificacion_onnx.json."),
        },
        "clases": [{"indice": i, "codigo": c, "nombre": NOMBRE_CLASE[c]}
                   for i, c in enumerate(CLASES)],
        "clases_sin_datos_suficientes": descartadas,
        "regimenes_requeridos": sorted({c[1] for c in CARACTERISTICAS}),
        "columnas_lectura_requeridas": ["rpm", "carga_calculada", "stft", "ltft",
                                        "avance_encendido"],
        "caracteristicas": [
            {"indice": i, "nombre": n, "etiqueta": et, "unidad": u, "regimen": r,
             "parametro": p, "columna_lectura": col, "estadistico": e,
             "referencia_sin_falla": referencia[n]}
            for i, (n, r, p, col, e, et, u) in enumerate(CARACTERISTICAS)
        ],
        "regla_desviacion": {
            "descripcion": ("Un parámetro se marca desviado si su valor queda fuera de "
                            "[limite_inferior, limite_superior] de su referencia sin falla: "
                            "'mayor' si supera el límite superior y 'menor' si queda bajo el "
                            "inferior. Para mostrar el valor de referencia se usa la mediana."),
            "limites": "Tukey sobre las atenciones C0: Q1 - 1,5 RIC y Q3 + 1,5 RIC",
            "percentiles": "interpolación lineal (numpy.percentile por defecto)",
            "orden_sugerido": ("de mayor a menor distancia al límite superado, dividida por "
                               "el RIC (o por la desviación si el RIC es cero)"),
            "atenciones_c0": tabla.loc[tabla["clase"] == "C0", "id_atencion"].tolist(),
        },
        "calculo_caracteristicas": {
            "herramienta": "consolidar_lecturas.py (núcleo universal)",
            "sha256_herramienta": sha256(ARCHIVO_CONSOLIDADOR),
            "tiempo": ("t_seg relativo al primer instante del archivo del régimen "
                       "(t - min(t)); si el máximo supera 10 000 se asume milisegundos"),
            "segundos_descarte": 15,
            "techo_ralenti_rpm": 1100,
            "regimenes_con_techo": ["R1", "R2"],
            "filtro": ("se descartan las filas con t_seg < 15; en R1 y R2 además las filas "
                       "con rpm > 1100 (las filas con rpm vacío se conservan)"),
            "vacios": "cada estadístico se calcula sobre los valores no vacíos del parámetro",
            "media": "media aritmética",
            "desv": "desviación estándar muestral (ddof = 1)",
            "max": "máximo",
            "pendiente": ("R3: pendiente de la recta de mínimos cuadrados de rpm contra "
                          "t_seg (numpy.polyfit grado 1) con las filas de rpm no vacío, "
                          "si hay más de 5"),
            "trim_total": ("suma de R2_stft_b1_pct_media y R2_ltft_b1_pct_media ya "
                           "redondeadas, redondeada otra vez"),
            "redondeo": ("4 decimales con round() de Python: mitad al par sobre el valor "
                         "binario exacto (en Kotlin, BigDecimal(double).setScale(4, "
                         "RoundingMode.HALF_EVEN))"),
            "precision_de_lectura": ("las lecturas grabadas por registrar_obd.py vienen "
                                     "redondeadas a 3 decimales"),
            "filas_de_referencia": ("filas_consolidador_corte.csv: las 14 características "
                                    "que calculó el consolidador para las atenciones del corte"),
        },
        "entrenamiento": {
            "algoritmo": (f"StandardScaler + {DESCRIPCION_MODELO[elegido]} con semilla "
                          f"{SEMILLA_FINAL}, entrenado tras RandomOverSampler(random_state="
                          f"{SEMILLA_FINAL}) sobre el conjunto escalado; sin ajuste de "
                          "hiperparámetros"),
            "modelo_elegido": elegido,
            "criterio_eleccion": ("comparación de Random Forest, k-NN y SVM dejando un vehículo "
                                  "fuera, con la regla fijada en corte.md antes de ver "
                                  "resultados; detalle en comparacion.json y resultados.md"),
            "sobremuestreo": {"metodo": "RandomOverSampler (imbalanced-learn)",
                              "random_state": SEMILLA_FINAL,
                              "filas_por_clase_tras_sobremuestreo": filas_sobremuestreadas,
                              "nota": "solo al entrenar; no forma parte del .onnx"},
            "versiones": {"scikit-learn": sklearn.__version__,
                          "imbalanced-learn": imblearn.__version__,
                          "skl2onnx": skl2onnx.__version__,
                          "onnx": onnx.__version__, "onnxruntime": onnxruntime.__version__},
            "fuente_clase": "datos/ficha/ficha_registro_obd2.xlsx, hoja Atenciones",
            "fecha_corte": FECHA_CORTE,
            "atenciones": int(len(tabla)),
            "vehiculos": int(tabla["id_vehiculo"].nunique()),
            "por_clase": por_clase,
            "ids_atenciones": tabla["id_atencion"].tolist(),
            "fuera_por_caracteristica_vacia": fuera_vacia,
            "fuera_por_clase_vacia": fuera_clase,
            "fuera_por_segunda_variante": sorted(SEGUNDAS_VARIANTES),
            "aciertos_sobre_sus_propias_atenciones": aciertos_propios,
        },
        "evaluacion_informativa": {
            "validacion": "dejando un vehículo fuera a la vez",
            "exactitud_balanceada": round(m4["exactitud_balanceada"]["media"], 3),
            "azar": round(1 / len(CLASES), 3),
            "matriz_confusion": {"filas_reales_columnas_predichas": CLASES,
                                 "valores": m4["matriz_confusion_semilla0"]["valores"],
                                 "semilla": 0},
            "advertencia": ("Cifra orientativa con 25 atenciones de 9 vehículos. No sirve como "
                            "resultado de la tesis."),
            "metricas_media_de_semillas": "media y desviación (ddof = 1) de las semillas 0 a 4",
            "cuatro_clases": {"exactitud_balanceada": redondeo(m4["exactitud_balanceada"]),
                              "f1_macro": redondeo(m4["f1_macro"])},
            "binario_c0_contra_falla": {"exactitud_balanceada": redondeo(m2["exactitud_balanceada"]),
                                        "f1_macro": redondeo(m2["f1_macro"]),
                                        "azar": 0.5,
                                        "matriz_confusion_semilla0": m2["matriz_confusion_semilla0"]},
            "permutacion": {"permutaciones": perm["permutaciones"],
                            "observada_semilla0": round(perm["observada"], 3),
                            "nula_media": round(perm["nula_media"], 3),
                            "p": round(perm["p"], 4)},
        },
    }

    (SALIDA / "contrato_modelo.json").write_text(
        json.dumps(contrato, ensure_ascii=False, indent=2), encoding="utf-8")
    with (SALIDA / "modelo_sklearn.pkl").open("wb") as f:
        pickle.dump(modelo, f)

    print(f"Modelo elegido: {NOMBRE_LARGO[elegido]} ({DESCRIPCION_MODELO[elegido]})")
    print(f"Entrenado con {len(tabla)} atenciones de {tabla['id_vehiculo'].nunique()} vehículos; "
          f"filas tras sobremuestreo: {filas_sobremuestreadas}")
    print(f"Aciertos sobre sus propias atenciones: {aciertos_propios} de {len(tabla)}")
    print(f"Opset: {contrato['formato']['opset']}, ir_version {onx.ir_version}")
    print(f"Modelo:   {ruta_onnx} ({ruta_onnx.stat().st_size} bytes, sha256 {contrato['sha256_onnx']})")
    print(f"Contrato: {SALIDA / 'contrato_modelo.json'}")


if __name__ == "__main__":
    main()
