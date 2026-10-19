#!/usr/bin/env python3
"""
Compara Random Forest, k-NN y SVM sobre el corte del 18/10/2026 y elige uno
con la regla fijada en corte.md antes de ver resultados.

Para cada modelo, en cuatro clases (C0 a C3) y en binario (C0 contra falla):
  - validación dejando un vehículo fuera a la vez (LeaveOneGroupOut)
  - escalado, sobremuestreo y clasificador dentro de cada pliegue de
    entrenamiento (Pipeline de imblearn, ver comun.nuevo_pipeline)
  - semillas 0 a 4: exactitud balanceada y F1 macro, media y desviación
  - matriz de confusión de la semilla 0 y suma de las cinco semillas

Después aplica la regla de elección (solo con cuatro clases; el binario no
decide) y hace la prueba de permutación del elegido.

Entrada:
    salida/tabla_corte.csv      escrita por preparar_corte.py

Salida:
    salida/comparacion.json     métricas, matrices, regla aplicada, elegido
                                y prueba de permutación

Uso, desde PFCapp/modelo:
    ..\\modelo_provisional\\.venv\\Scripts\\python -I -X utf8 scripts\\comparar_modelos.py
"""

import json
import os
import shutil
import sys
import time
from datetime import date
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import imblearn  # noqa: E402
import joblib  # noqa: E402
import numpy as np  # noqa: E402
import sklearn  # noqa: E402
from joblib import Parallel, delayed  # noqa: E402
from sklearn.metrics import balanced_accuracy_score, confusion_matrix, f1_score  # noqa: E402
from sklearn.model_selection import LeaveOneGroupOut, cross_val_predict  # noqa: E402

from comun import (CLASES, CLASES_BINARIO, DESCRIPCION_MODELO, EPS_NUMERICO,  # noqa: E402
                   FECHA_CORTE, NOMBRES, ORDEN_SIMPLICIDAD, SALIDA, SEMILLAS,
                   TOLERANCIA_EMPATE, leer_tabla_corte, nuevo_pipeline)

MODELOS = ["RF", "kNN", "SVM"]
NOMBRE_LARGO = {"RF": "Random Forest", "kNN": "k-NN", "SVM": "SVM"}
N_PERMUTACIONES = 1000
SEMILLA_PERMUTACION = 0


# ---------------------------------------------------------------------------
# VALIDACIÓN
# ---------------------------------------------------------------------------

def predecir_logo(nombre: str, semilla: int, X: np.ndarray, y: np.ndarray,
                  grupos: np.ndarray) -> np.ndarray:
    """Predicción de cada atención con el modelo entrenado sin su vehículo."""
    return cross_val_predict(nuevo_pipeline(nombre, semilla), X, y, groups=grupos,
                             cv=LeaveOneGroupOut(), method="predict")


def metricas(y: np.ndarray, pred: np.ndarray, etiquetas: list) -> dict:
    return {
        "exactitud_balanceada": float(balanced_accuracy_score(y, pred)),
        "f1_macro": float(f1_score(y, pred, average="macro", labels=etiquetas,
                                   zero_division=0)),
    }


def evaluar(nombre: str, X: np.ndarray, y: np.ndarray, grupos: np.ndarray,
            etiquetas: list, ids: list) -> dict:
    """Las cinco semillas de un modelo en un esquema (cuatro clases o binario)."""
    por_semilla, matrices, pred0 = [], [], None
    for s in SEMILLAS:
        pred = predecir_logo(nombre, s, X, y, grupos)
        por_semilla.append(metricas(y, pred, etiquetas))
        matrices.append(confusion_matrix(y, pred, labels=etiquetas))
        if s == 0:
            pred0 = pred
    resumen = {}
    for m in ("exactitud_balanceada", "f1_macro"):
        v = np.array([r[m] for r in por_semilla])
        resumen[m] = {"media": float(v.mean()), "desv": float(v.std(ddof=1)),
                      "por_semilla": [float(x) for x in v]}
    resumen["matriz_confusion_semilla0"] = {
        "filas_reales_columnas_predichas": etiquetas, "valores": matrices[0].tolist()}
    resumen["matriz_confusion_suma_semillas"] = {
        "filas_reales_columnas_predichas": etiquetas,
        "valores": np.sum(matrices, axis=0).tolist()}
    resumen["predicciones_semilla0"] = dict(zip(ids, [str(p) for p in pred0]))
    return resumen


# ---------------------------------------------------------------------------
# REGLA DE ELECCIÓN (corte.md, "Criterio de elección")
# ---------------------------------------------------------------------------

def aplicar_regla(res: dict) -> dict:
    """Banda de 0,02 contra el mejor en exactitud balanceada, luego en F1 macro,
    luego el más simple. Valores medios sin redondear."""
    ba = {m: res[m]["cuatro_clases"]["exactitud_balanceada"]["media"] for m in MODELOS}
    f1 = {m: res[m]["cuatro_clases"]["f1_macro"]["media"] for m in MODELOS}
    pasos = []

    mejor_ba = max(ba.values())
    siguen = [m for m in MODELOS if mejor_ba - ba[m] <= TOLERANCIA_EMPATE + EPS_NUMERICO]
    pasos.append({
        "paso": 1, "criterio": "exactitud balanceada media, banda de 0,02 contra la mayor",
        "valores": {m: round(ba[m], 6) for m in MODELOS},
        "mayor": round(mejor_ba, 6), "siguen": siguen})
    decide = "exactitud balanceada"

    if len(siguen) > 1:
        mejor_f1 = max(f1[m] for m in siguen)
        siguen_f1 = [m for m in siguen if mejor_f1 - f1[m] <= TOLERANCIA_EMPATE + EPS_NUMERICO]
        pasos.append({
            "paso": 2, "criterio": "F1 macro medio entre los que siguen, banda de 0,02 contra el mayor",
            "valores": {m: round(f1[m], 6) for m in siguen},
            "mayor": round(mejor_f1, 6), "siguen": siguen_f1})
        siguen = siguen_f1
        decide = "F1 macro"

    if len(siguen) > 1:
        siguen_simple = sorted(siguen, key=ORDEN_SIMPLICIDAD.index)[:1]
        pasos.append({
            "paso": 3, "criterio": "el más simple (k-NN, luego SVM, luego Random Forest)",
            "candidatos": siguen, "siguen": siguen_simple})
        siguen = siguen_simple
        decide = "simplicidad"

    elegido = siguen[0]
    return {"elegido": elegido, "decidio": decide, "pasos": pasos}


def comparar_par(a: str, b: str, res: dict) -> dict:
    """Regla de dos modelos, solo como registro."""
    ba_a = res[a]["cuatro_clases"]["exactitud_balanceada"]["media"]
    ba_b = res[b]["cuatro_clases"]["exactitud_balanceada"]["media"]
    f1_a = res[a]["cuatro_clases"]["f1_macro"]["media"]
    f1_b = res[b]["cuatro_clases"]["f1_macro"]["media"]
    if abs(ba_a - ba_b) > TOLERANCIA_EMPATE + EPS_NUMERICO:
        gana, por = (a if ba_a > ba_b else b), "exactitud balanceada"
    elif abs(f1_a - f1_b) > TOLERANCIA_EMPATE + EPS_NUMERICO:
        gana, por = (a if f1_a > f1_b else b), "F1 macro"
    else:
        gana, por = sorted([a, b], key=ORDEN_SIMPLICIDAD.index)[0], "simplicidad"
    return {"par": [a, b], "dif_exactitud_balanceada": round(ba_a - ba_b, 6),
            "dif_f1_macro": round(f1_a - f1_b, 6), "gana": gana, "decide": por}


# ---------------------------------------------------------------------------
# PRUEBA DE PERMUTACIÓN
# ---------------------------------------------------------------------------

def ba_permutada(nombre: str, X: np.ndarray, y_perm: np.ndarray, grupos: np.ndarray) -> float:
    pred = predecir_logo(nombre, SEMILLA_PERMUTACION, X, y_perm, grupos)
    return float(balanced_accuracy_score(y_perm, pred))


def prueba_permutacion(nombre: str, X: np.ndarray, y: np.ndarray, grupos: np.ndarray,
                       observada: float) -> dict:
    rng = np.random.default_rng(SEMILLA_PERMUTACION)
    permutaciones = [rng.permutation(len(y)) for _ in range(N_PERMUTACIONES)]
    t0 = time.time()
    nulas = Parallel(n_jobs=-1)(
        delayed(ba_permutada)(nombre, X, y[idx], grupos) for idx in permutaciones)
    nulas = np.array(nulas)
    mayores_o_iguales = int((nulas >= observada - EPS_NUMERICO).sum())
    return {
        "modelo": nombre,
        "esquema": "cuatro clases",
        "validacion": "dejando un vehículo fuera a la vez",
        "semilla": SEMILLA_PERMUTACION,
        "permutaciones": N_PERMUTACIONES,
        "tipo": "permutación libre de las 25 etiquetas, numpy.random.default_rng(0)",
        "estadistico": "exactitud balanceada",
        "observada": observada,
        "nula_media": float(nulas.mean()),
        "nula_desv": float(nulas.std(ddof=1)),
        "nula_percentil_95": float(np.percentile(nulas, 95)),
        "nula_max": float(nulas.max()),
        "mayores_o_iguales": mayores_o_iguales,
        "p": (mayores_o_iguales + 1) / (N_PERMUTACIONES + 1),
        "segundos": round(time.time() - t0, 1),
        "nulas": [round(float(v), 6) for v in nulas],
    }


# ---------------------------------------------------------------------------
# PROCESO
# ---------------------------------------------------------------------------

def main() -> None:
    tabla = leer_tabla_corte()
    X = tabla[NOMBRES].to_numpy(dtype=np.float64)
    y4 = tabla["clase"].to_numpy(dtype=object)
    y2 = np.where(y4 == "C0", "C0", "falla").astype(object)
    grupos = tabla["id_vehiculo"].to_numpy(dtype=object)
    ids = tabla["id_atencion"].tolist()
    print(f"Corte: {len(tabla)} atenciones, {len(set(grupos))} vehículos "
          f"({len(set(grupos))} pliegues)\n")

    res = {}
    for m in MODELOS:
        t0 = time.time()
        res[m] = {
            "descripcion": DESCRIPCION_MODELO[m],
            "cuatro_clases": evaluar(m, X, y4, grupos, CLASES, ids),
            "binario": evaluar(m, X, y2, grupos, CLASES_BINARIO, ids),
        }
        c, b = res[m]["cuatro_clases"], res[m]["binario"]
        print(f"{NOMBRE_LARGO[m]:14s} 4 clases: EB {c['exactitud_balanceada']['media']:.3f} "
              f"± {c['exactitud_balanceada']['desv']:.3f}, F1m {c['f1_macro']['media']:.3f} "
              f"± {c['f1_macro']['desv']:.3f} | binario: EB "
              f"{b['exactitud_balanceada']['media']:.3f} ± {b['exactitud_balanceada']['desv']:.3f}, "
              f"F1m {b['f1_macro']['media']:.3f} ± {b['f1_macro']['desv']:.3f} "
              f"({time.time() - t0:.1f} s)")

    regla = aplicar_regla(res)
    elegido = regla["elegido"]
    pares = [comparar_par(a, b, res) for a, b in (("RF", "kNN"), ("RF", "SVM"), ("kNN", "SVM"))]
    print(f"\nRegla: elegido {NOMBRE_LARGO[elegido]}, decidió {regla['decidio']}")
    for p in regla["pasos"]:
        print(f"  paso {p['paso']}: {p['criterio']} -> siguen {p['siguen']}")

    observada = res[elegido]["cuatro_clases"]["exactitud_balanceada"]["por_semilla"][0]
    print(f"\nPermutación de {NOMBRE_LARGO[elegido]}: {N_PERMUTACIONES} permutaciones en "
          f"{os.cpu_count()} núcleos...")
    perm = prueba_permutacion(elegido, X, y4, grupos, observada)
    print(f"  observada {perm['observada']:.3f}, nula media {perm['nula_media']:.3f}, "
          f"p95 nula {perm['nula_percentil_95']:.3f}, p = {perm['p']:.4f} "
          f"({perm['segundos']} s)")

    informe = {
        "fecha": f"{date.today():%Y-%m-%d}",
        "fecha_corte": FECHA_CORTE,
        "criterio": "corte.md, sección 'Criterio de elección, fijado antes de ver resultados'",
        "corte": {"atenciones": int(len(tabla)), "vehiculos": int(len(set(grupos))),
                  "ids_atenciones": ids},
        "validacion": "LeaveOneGroupOut por id_vehiculo, cross_val_predict con predict()",
        "tratamiento": ("Pipeline de imblearn: StandardScaler -> RandomOverSampler(random_state="
                        "semilla) -> clasificador, ajustado dentro de cada pliegue"),
        "semillas": SEMILLAS,
        "desviacion": "desviación estándar muestral (ddof = 1) entre las cinco semillas",
        "matrices": ("matriz_confusion_semilla0 en conteos de atenciones; "
                     "matriz_confusion_suma_semillas suma las cinco semillas"),
        "azar": {"cuatro_clases": 0.25, "binario": 0.5},
        "versiones": {"scikit-learn": sklearn.__version__,
                      "imbalanced-learn": imblearn.__version__,
                      "numpy": np.__version__, "joblib": joblib.__version__},
        "modelos": res,
        "regla": regla,
        "elegido": elegido,
        "comparaciones_por_pares": pares,
        "permutacion": perm,
    }
    ruta = SALIDA / "comparacion.json"
    ruta.write_text(json.dumps(informe, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nEscrito: {ruta}")

    # Los procesos de joblib importan comun.py por su cuenta y dejan su
    # __pycache__ en scripts/. No hace falta para nada, así que se borra.
    shutil.rmtree(Path(__file__).resolve().parent / "__pycache__", ignore_errors=True)


if __name__ == "__main__":
    main()
