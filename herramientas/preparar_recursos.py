#!/usr/bin/env python3
"""
Copia a la app los archivos que no se versionan: el modelo ONNX con su contrato,
los casos de prueba de inferencia, las atenciones de demostración y las atenciones
que usan las pruebas unitarias.

Origen:
  --tes      repositorio de la tesis (datos/crudo con las atenciones grabadas)
  --modelo   salida de modelo/scripts/exportar_modelo.py (modelo/salida)
  --procesado salida de consolidar_lecturas.py (modelo/procesado)

Uso, desde la raíz del proyecto:
    python herramientas/preparar_recursos.py --tes C:/Users/Luis/Documents/Tes

Solo lee del repositorio de la tesis; escribe únicamente dentro de app/src.
"""

import argparse
import json
import shutil
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
APP = RAIZ / "app" / "src"

# Archivos de una atención que lee el adaptador de demostración.
SUFIJOS_DEMO = ("_R1.csv", "_R2.csv", "_R3.csv", "_DTC.csv", "_modo06.csv", "_contacto.csv", "_pids.csv")


def copiar(origen: Path, destino: Path) -> None:
    destino.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(origen, destino)


def archivos_de_atencion(crudo: Path, id_atencion: str) -> list[Path]:
    """Todos los archivos de una atención (CSV y bitácora de sesión), sin _descartes."""
    return sorted(p for p in crudo.glob(f"{id_atencion}_*")
                  if p.is_file() and p.suffix in (".csv", ".txt"))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tes", required=True, type=Path)
    ap.add_argument("--modelo", type=Path, default=RAIZ / "modelo" / "salida")
    ap.add_argument("--procesado", type=Path, default=RAIZ / "modelo" / "procesado")
    args = ap.parse_args()

    crudo = args.tes / "datos" / "crudo"
    if not crudo.is_dir():
        print(f"No existe {crudo}")
        return 1

    # 1. Modelo y contrato (assets) y casos de prueba (JVM e instrumentadas).
    contrato_json = args.modelo / "contrato_modelo.json"
    contrato = json.loads(contrato_json.read_text(encoding="utf-8"))
    onnx = args.modelo / contrato["archivo_onnx"]
    casos = args.modelo / "casos_prueba_inferencia.csv"
    destino_modelo = APP / "main" / "assets" / "modelo"
    if destino_modelo.exists():
        shutil.rmtree(destino_modelo)
    copiar(contrato_json, destino_modelo / "contrato_modelo.json")
    copiar(onnx, destino_modelo / onnx.name)
    copiar(casos, APP / "test" / "resources" / "modelo" / casos.name)
    copiar(casos, APP / "androidTest" / "assets" / casos.name)
    print(f"Modelo {contrato['version_modelo']}: {onnx.name}, contrato y casos de prueba")

    # 2. Atenciones de demostración: las que lista assets/demo/catalogo.json.
    catalogo = json.loads((APP / "main" / "assets" / "demo" / "catalogo.json").read_text(encoding="utf-8"))
    for g in catalogo["grabaciones"]:
        archivos = [p for p in archivos_de_atencion(crudo, g["id"]) if p.name.endswith(SUFIJOS_DEMO)]
        destino_demo = APP / "main" / "assets" / "demo" / g["id"]
        if destino_demo.exists():
            shutil.rmtree(destino_demo)
        for p in archivos:
            copiar(p, APP / "main" / "assets" / "demo" / g["id"] / p.name)
        print(f"Demostración {g['id']}: {len(archivos)} archivos")

    # 3. Atenciones para las pruebas unitarias y la tabla del consolidador.
    destino_crudo = APP / "test" / "resources" / "crudo"
    n = 0
    for p in sorted(crudo.iterdir()):
        if p.is_file() and (p.name.startswith("ATN-") and p.suffix in (".csv", ".txt")
                            or p.name == "atenciones_excluidas.csv"):
            copiar(p, destino_crudo / p.name)
            n += 1
    copiar(args.procesado / "caracteristicas_nucleo.csv",
           APP / "test" / "resources" / "consolidador" / "caracteristicas_nucleo.csv")
    print(f"Pruebas unitarias: {n} archivos de atenciones y la tabla del consolidador")
    return 0


if __name__ == "__main__":
    sys.exit(main())
