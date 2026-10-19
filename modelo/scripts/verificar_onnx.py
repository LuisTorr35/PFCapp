#!/usr/bin/env python3
"""
Comprueba que modelo_pfc.onnx da las mismas clases y probabilidades que el
modelo de scikit-learn del que salió, y que el contrato describe bien al .onnx.

Compara sobre dos conjuntos:
  1. las 25 atenciones reales del corte (salida/tabla_corte.csv). Aquí se
     EXIGE la misma clase en todas las filas y una diferencia máxima de
     probabilidad de 1e-5 o menos
  2. 5000 filas sintéticas al azar dentro del rango observado de cada
     característica, ampliado un 50 % por cada lado. Aquí se REPORTA la
     diferencia máxima y las filas con clase distinta, y se diagnostica la
     causa de cada discrepancia

El .onnx trabaja en float32 y scikit-learn en float64, así que se reportan dos
diferencias: contra scikit-learn con la entrada original y contra scikit-learn
con la misma entrada ya redondeada a float32. La tolerancia es absoluta.

Diagnóstico de las discrepancias en las sintéticas:
  - empate: en scikit-learn las dos clases más probables están empatadas (o a
    menos de 1e-6), y el desempate en float32 cae del otro lado
  - umbral: (solo árboles) en algún árbol el valor escalado de la fila está a
    menos de 1e-5 del umbral de un nodo de su recorrido, y el escalado y el
    umbral en float32 del .onnx la mandan por la otra rama
  - otra: ninguna de las dos anteriores

Salidas en salida/:
    verificacion_onnx.json          resumen con las diferencias máximas y el veredicto
    casos_prueba_inferencia.csv     fila de cada atención y probabilidades esperadas,
                                    para la prueba de inferencia de la aplicación
    filas_consolidador_corte.csv    las 14 características tal como las calculó el
                                    consolidador para las 25 del corte, para que la
                                    aplicación compare su propio cálculo

Uso, desde PFCapp/modelo:
    ..\\modelo_provisional\\.venv\\Scripts\\python -I -X utf8 scripts\\verificar_onnx.py
"""

import hashlib
import json
import pickle
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import numpy as np  # noqa: E402
import onnx  # noqa: E402
import onnxruntime as ort  # noqa: E402
import pandas as pd  # noqa: E402

from comun import ARCHIVO_NUCLEO, ARCHIVO_TABLA_CORTE, SALIDA  # noqa: E402

TOLERANCIA = 1e-5
N_SINTETICAS = 5000
SEMILLA = 0
EPS_EMPATE = 1e-6
EPS_UMBRAL = 1e-5


def distancia_minima_umbral(modelo, X: np.ndarray) -> np.ndarray:
    """Para cada fila, la menor distancia entre su valor escalado y el umbral de
    algún nodo de su recorrido, en todos los árboles. Solo para bosques."""
    clf = modelo.named_steps["clasificador"]
    Xs = modelo.named_steps["escalado"].transform(X)
    Xs32 = Xs.astype(np.float32)
    minimo = np.full(len(X), np.inf)
    for arbol in clf.estimators_:
        t = arbol.tree_
        camino = arbol.decision_path(Xs32).tocsr()
        for i in range(len(X)):
            nodos = camino.indices[camino.indptr[i]:camino.indptr[i + 1]]
            nodos = nodos[t.children_left[nodos] != -1]
            if len(nodos):
                d = np.abs(Xs[i, t.feature[nodos]] - t.threshold[nodos]).min()
                minimo[i] = min(minimo[i], d)
    return minimo


def main() -> int:
    contrato = json.loads((SALIDA / "contrato_modelo.json").read_text(encoding="utf-8"))
    ruta_onnx = SALIDA / contrato["archivo_onnx"]
    with (SALIDA / "modelo_sklearn.pkl").open("rb") as f:
        modelo = pickle.load(f)

    nombres = [c["nombre"] for c in contrato["caracteristicas"]]
    clases = [c["codigo"] for c in contrato["clases"]]
    problemas = []

    # 1. El contrato describe al .onnx que está al lado.
    if hashlib.sha256(ruta_onnx.read_bytes()).hexdigest() != contrato["sha256_onnx"]:
        problemas.append("sha256_onnx del contrato no coincide con el archivo")
    if list(modelo.classes_) != clases:
        problemas.append(f"orden de clases distinto: modelo {list(modelo.classes_)}, contrato {clases}")
    if modelo.n_features_in_ != len(nombres):
        problemas.append("número de características distinto entre modelo y contrato")
    meta = {p.key: p.value for p in onnx.load(str(ruta_onnx)).metadata_props}
    if meta.get("version_modelo") != contrato["version_modelo"] or meta.get("provisional") != "true":
        problemas.append(f"metadatos del .onnx {meta} no coinciden con el contrato")

    sesion = ort.InferenceSession(str(ruta_onnx), providers=["CPUExecutionProvider"])
    entrada = sesion.get_inputs()[0]
    esperada = contrato["formato"]["entradas"][0]
    if entrada.name != esperada["nombre"] or entrada.shape[1] != len(nombres):
        problemas.append(f"entrada del .onnx {entrada.name} {entrada.shape} no coincide con el contrato")
    nombres_salida = [o.name for o in sesion.get_outputs()]
    if nombres_salida != [s["nombre"] for s in contrato["formato"]["salidas"]]:
        problemas.append(f"salidas del .onnx {nombres_salida} no coinciden con el contrato")
    if sesion.get_outputs()[1].shape[1] != len(clases):
        problemas.append("la salida probabilities no tiene una columna por clase")

    def comparar(X: np.ndarray) -> dict:
        etiqueta, prob_onnx = sesion.run(None, {entrada.name: X.astype(np.float32)})
        etiqueta = np.asarray(etiqueta)
        prob_64 = modelo.predict_proba(X)
        prob_32 = modelo.predict_proba(X.astype(np.float32).astype(np.float64))
        clase_sk = modelo.predict(X)
        dif_fila = np.abs(prob_onnx - prob_64).max(axis=1)
        orden = np.sort(prob_64, axis=1)
        brecha_top2 = orden[:, -1] - orden[:, -2]
        return {
            "filas": int(len(X)),
            "dif_max_vs_entrada_original": float(dif_fila.max()),
            "dif_max_vs_entrada_float32": float(np.abs(prob_onnx - prob_32).max()),
            "suma_prob_dif_max": float(np.abs(prob_onnx.sum(axis=1) - 1).max()),
            "clases_distintas": int((etiqueta != clase_sk).sum()),
            "filas_con_dif_mayor_que_tolerancia": int((dif_fila > TOLERANCIA).sum()),
            "etiqueta_onnx_distinta_de_su_prob_mayor": int(
                (etiqueta != np.array(clases)[prob_onnx.argmax(axis=1)]).sum()),
            "_prob_onnx": prob_onnx, "_etiqueta": etiqueta, "_prob_64": prob_64,
            "_clase_sk": clase_sk, "_dif_fila": dif_fila, "_brecha_top2": brecha_top2,
        }

    # 2. Atenciones reales del corte.
    tabla = pd.read_csv(ARCHIVO_TABLA_CORTE, encoding="utf-8")
    tabla_txt = pd.read_csv(ARCHIVO_TABLA_CORTE, encoding="utf-8", dtype=str)
    X_real = tabla[nombres].to_numpy(dtype=np.float64)
    real = comparar(X_real)
    if real["dif_max_vs_entrada_original"] > TOLERANCIA:
        problemas.append(f"atenciones reales: diferencia {real['dif_max_vs_entrada_original']:.2e} "
                         f"supera la tolerancia {TOLERANCIA:.0e}")
    if real["clases_distintas"]:
        problemas.append(f"atenciones reales: {real['clases_distintas']} filas con clase distinta")

    # 3. Filas sintéticas dentro del rango observado ampliado. No deciden el
    #    veredicto: se reportan y se diagnostican.
    rng = np.random.default_rng(SEMILLA)
    lo, hi = X_real.min(axis=0), X_real.max(axis=0)
    margen = 0.5 * (hi - lo)
    X_sint = rng.uniform(lo - margen, hi + margen, size=(N_SINTETICAS, len(nombres)))
    sint = comparar(X_sint)

    discrepantes = np.where((sint["_etiqueta"] != sint["_clase_sk"])
                            | (sint["_dif_fila"] > TOLERANCIA))[0]
    es_bosque = hasattr(modelo.named_steps["clasificador"], "estimators_")
    diagnostico = {"filas_discrepantes": int(len(discrepantes))}
    if es_bosque:
        d_umbral = distancia_minima_umbral(modelo, X_sint)
        diagnostico["distancia_minima_al_umbral"] = {
            "mediana_en_filas_sin_discrepancia": float(np.median(
                np.delete(d_umbral, discrepantes))),
            "en_filas_discrepantes": [float(d_umbral[i]) for i in discrepantes],
        }
    detalle = []
    for i in discrepantes:
        empate = bool(sint["_brecha_top2"][i] < EPS_EMPATE)
        umbral = bool(es_bosque and d_umbral[i] < EPS_UMBRAL)
        causa = "empate" if empate else ("umbral" if umbral else "otra")
        detalle.append({
            "fila": int(i),
            "clase_sklearn": str(sint["_clase_sk"][i]),
            "clase_onnx": str(sint["_etiqueta"][i]),
            "dif_prob_max": float(sint["_dif_fila"][i]),
            "brecha_entre_las_dos_mas_probables_sklearn": float(sint["_brecha_top2"][i]),
            "prob_sklearn": [round(float(v), 6) for v in sint["_prob_64"][i]],
            "prob_onnx": [round(float(v), 6) for v in sint["_prob_onnx"][i]],
            "causa": causa,
        })
    diagnostico["por_causa"] = {c: sum(d["causa"] == c for d in detalle)
                                for c in ("empate", "umbral", "otra")}
    diagnostico["detalle"] = detalle

    # Casos de prueba para la aplicación: la fila tal como la lee el modelo
    # (mismo texto que escribió el consolidador) y lo que debe devolver ONNX
    # Runtime con ella.
    casos = tabla_txt[["id_atencion", "id_vehiculo", "clase"]].rename(columns={"clase": "clase_real"})
    casos = pd.concat([casos, tabla_txt[nombres]], axis=1)
    for i, c in enumerate(clases):
        casos[f"prob_{c}"] = [f"{v:.7g}" for v in real["_prob_onnx"][:, i]]
    casos["clase_onnx"] = real["_etiqueta"]
    casos.to_csv(SALIDA / "casos_prueba_inferencia.csv", index=False, encoding="utf-8")

    # Filas de referencia del consolidador para las 25 del corte, sin reformatear.
    nucleo_txt = pd.read_csv(ARCHIVO_NUCLEO, encoding="utf-8-sig", dtype=str)
    filas = (nucleo_txt[nucleo_txt["id_atencion"].isin(tabla_txt["id_atencion"])]
             .sort_values("id_atencion")[["id_atencion"] + nombres])
    if len(filas) != len(tabla_txt) or not filas.reset_index(drop=True).equals(
            tabla_txt[["id_atencion"] + nombres].sort_values("id_atencion").reset_index(drop=True)):
        problemas.append("filas_consolidador_corte no coincide con tabla_corte.csv")
    filas.to_csv(SALIDA / "filas_consolidador_corte.csv", index=False, encoding="utf-8")

    limpio = lambda r: {k: v for k, v in r.items() if not k.startswith("_")}  # noqa: E731
    informe = {
        "version_modelo": contrato["version_modelo"],
        "archivo_onnx": contrato["archivo_onnx"],
        "onnxruntime": ort.__version__,
        "tolerancia_absoluta": TOLERANCIA,
        "exigencia": ("en las atenciones reales: misma clase en todas las filas y diferencia "
                      "máxima de probabilidad <= tolerancia. Las sintéticas se reportan y se "
                      "diagnostican, pero no deciden el veredicto"),
        "comparacion_de_clase": ("label del .onnx contra predict() de scikit-learn con la "
                                 "entrada original en float64"),
        "atenciones_reales": limpio(real),
        "filas_sinteticas": {**limpio(sint), "generacion": (
            f"{N_SINTETICAS} filas uniformes en [min - 0,5 rango, max + 0,5 rango] de cada "
            f"característica de las atenciones reales, numpy.random.default_rng({SEMILLA})"),
            "diagnostico": diagnostico},
        "problemas": problemas,
        "veredicto": "COINCIDE" if not problemas else "NO COINCIDE",
    }
    (SALIDA / "verificacion_onnx.json").write_text(
        json.dumps(informe, ensure_ascii=False, indent=2), encoding="utf-8")

    for nombre, r in (("Atenciones reales", real), ("Filas sintéticas", sint)):
        print(f"{nombre}: {r['filas']} filas, diferencia máxima {r['dif_max_vs_entrada_original']:.2e} "
              f"(contra entrada float32 {r['dif_max_vs_entrada_float32']:.2e}), "
              f"clases distintas {r['clases_distintas']}, "
              f"filas sobre la tolerancia {r['filas_con_dif_mayor_que_tolerancia']}")
    print(f"Discrepancias en sintéticas por causa: {diagnostico['por_causa']}")
    if es_bosque:
        print(f"  distancia mínima al umbral, mediana en filas sin discrepancia: "
              f"{diagnostico['distancia_minima_al_umbral']['mediana_en_filas_sin_discrepancia']:.2e}")
    for d in detalle[:10]:
        print(f"  fila {d['fila']}: sklearn {d['clase_sklearn']} onnx {d['clase_onnx']}, "
              f"dif {d['dif_prob_max']:.2e}, brecha top2 {d['brecha_entre_las_dos_mas_probables_sklearn']:.2e}, "
              f"causa {d['causa']}")
    for p in problemas:
        print(f"  PROBLEMA: {p}")
    print(f"Veredicto: {informe['veredicto']} (tolerancia {TOLERANCIA:.0e})")
    return 0 if not problemas else 1


if __name__ == "__main__":
    sys.exit(main())
