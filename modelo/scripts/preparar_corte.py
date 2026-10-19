#!/usr/bin/env python3
"""
Arma el corte de atenciones del 18/10/2026 con el que se comparan y entrenan
los modelos.

Regla del corte, en este orden:
  1. atenciones válidas del consolidador, es decir, las de
     procesado/caracteristicas_nucleo.csv, que ya vienen sin las de
     datos/crudo/atenciones_excluidas.csv
  2. con clase C0, C1, C2 o C3 en la hoja Atenciones de la ficha
  3. sin las segundas variantes ATN-003, ATN-017 y ATN-039 (decisión de Luis)
  4. con las 14 características del núcleo universal completas (sin imputar)

Entradas (todas dentro de PFCapp/modelo/):
    procesado/caracteristicas_nucleo.csv   salida de consolidar_lecturas.py
    datos/ficha/ficha_registro_obd2.xlsx   hoja Atenciones
    datos/crudo/atenciones_excluidas.csv   motivos de las excluidas

Salida:
    salida/tabla_corte.csv   id_atencion, id_vehiculo, clase, confianza y las
                             14 características tal como las escribió el
                             consolidador (se copian como texto, sin reformatear)

Imprime además las tablas que van en corte.md.

Uso, desde PFCapp/modelo:
    ..\\modelo_provisional\\.venv\\Scripts\\python -I -X utf8 scripts\\preparar_corte.py
"""

import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import pandas as pd  # noqa: E402

from comun import (ARCHIVO_EXCLUIDAS, ARCHIVO_NUCLEO, ARCHIVO_TABLA_CORTE,  # noqa: E402
                   CLASES, CORTE_ESPERADO, NOMBRES, SALIDA, SEGUNDAS_VARIANTES,
                   leer_ficha)


def main() -> int:
    SALIDA.mkdir(parents=True, exist_ok=True)

    # Se lee como texto para copiar los valores exactos del consolidador.
    nucleo = pd.read_csv(ARCHIVO_NUCLEO, encoding="utf-8-sig", dtype=str)
    columnas = [c for c in nucleo.columns if c != "id_atencion"]
    if columnas != NOMBRES:
        raise SystemExit(f"Las columnas del núcleo no coinciden:\n  csv: {columnas}\n"
                         f"  esperado: {NOMBRES}")
    ficha = leer_ficha()
    excluidas = pd.read_csv(ARCHIVO_EXCLUIDAS, encoding="utf-8-sig")
    excluidas["id_atencion"] = excluidas["id_atencion"].str.upper().str.strip()

    fuera = []  # (id_atencion, motivo)

    # 0. Las que están en la ficha pero no entre las válidas del consolidador.
    validas = set(nucleo["id_atencion"])
    for _, r in excluidas.iterrows():
        fuera.append((r["id_atencion"], f"atenciones_excluidas.csv: {r['motivo']}"))
    for a in sorted(set(ficha["id_atencion"]) - validas - set(excluidas["id_atencion"])):
        fuera.append((a, "está en la ficha pero el consolidador no le calculó características"))

    # 1 y 2. Válidas con clase C0 a C3 en la ficha.
    tabla = nucleo.merge(ficha, on="id_atencion", how="left")
    sin_clase = tabla[~tabla["clase"].isin(CLASES)]
    for _, r in sin_clase.iterrows():
        fuera.append((r["id_atencion"], f"sin clase C0 a C3 en la ficha (clase = {r['clase']})"))
    tabla = tabla[tabla["clase"].isin(CLASES)]

    # 3. Segundas variantes.
    for a in sorted(set(tabla["id_atencion"]) & set(SEGUNDAS_VARIANTES)):
        fuera.append((a, SEGUNDAS_VARIANTES[a]))
    tabla = tabla[~tabla["id_atencion"].isin(SEGUNDAS_VARIANTES)]

    # 4. Núcleo universal completo. Sin imputación, igual que en la aplicación.
    vacias = tabla[NOMBRES].isna()
    for i in tabla.index[vacias.any(axis=1)]:
        faltan = [n for n in NOMBRES if vacias.loc[i, n]]
        fuera.append((tabla.loc[i, "id_atencion"],
                      f"núcleo universal incompleto, sin {', '.join(faltan)} (no se imputa)"))
    tabla = tabla[~vacias.any(axis=1)].sort_values("id_atencion").reset_index(drop=True)

    # Comprobación contra el corte esperado.
    obtenido = {c: sorted(tabla.loc[tabla["clase"] == c, "id_atencion"]) for c in CLASES}
    if obtenido != CORTE_ESPERADO:
        print("EL CORTE NO COINCIDE CON EL ESPERADO")
        for c in CLASES:
            sobran = sorted(set(obtenido[c]) - set(CORTE_ESPERADO[c]))
            faltan = sorted(set(CORTE_ESPERADO[c]) - set(obtenido[c]))
            if sobran or faltan:
                print(f"  {c}: sobran {sobran}, faltan {faltan}")
        return 1

    salida = tabla[["id_atencion", "id_vehiculo", "clase", "confianza_etiqueta"] + NOMBRES]
    salida = salida.rename(columns={"confianza_etiqueta": "confianza"})
    salida.to_csv(ARCHIVO_TABLA_CORTE, index=False, encoding="utf-8")

    # Tablas para corte.md.
    print(f"Corte: {len(tabla)} atenciones de {tabla['id_vehiculo'].nunique()} vehículos\n")
    print("| Atención | Clase | Vehículo | Marca y modelo | Año | Confianza de la etiqueta | Falla confirmada |")
    print("|---|---|---|---|---|---|---|")
    for _, r in tabla.sort_values(["clase", "id_atencion"]).iterrows():
        anio = "s. d." if pd.isna(r["anio"]) else str(int(r["anio"]))
        print(f"| {r['id_atencion']} | {r['clase']} | {r['id_vehiculo']} | {r['marca']} "
              f"{r['modelo']} | {anio} | {r['confianza_etiqueta']} | {r['componente_confirmado']} |")
    print("\n| Clase | Atenciones | Vehículos distintos |")
    print("|---|---|---|")
    for c in CLASES:
        g = tabla[tabla["clase"] == c]
        print(f"| {c} | {len(g)} | {g['id_vehiculo'].nunique()} |")
    print(f"| Total | {len(tabla)} | {tabla['id_vehiculo'].nunique()} |")
    print("\n| Vehículo | Marca y modelo | Atenciones | Clases |")
    print("|---|---|---|---|")
    for v, g in tabla.groupby("id_vehiculo"):
        print(f"| {v} | {g['marca'].iloc[0]} {g['modelo'].iloc[0]} | {len(g)} | "
              f"{', '.join(sorted(g['clase']))} |")
    print("\n| Atención | Clase en la ficha | Motivo |")
    print("|---|---|---|")
    clase_ficha = dict(zip(ficha["id_atencion"], ficha["clase"]))
    for a, motivo in sorted(fuera):
        c = clase_ficha.get(a)
        c = "sin clase" if c is None or pd.isna(c) else c
        print(f"| {a} | {c} | {motivo} |")
    print(f"\nEscrito: {ARCHIVO_TABLA_CORTE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
