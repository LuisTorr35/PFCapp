#!/usr/bin/env python3
"""
Escribe app/schemas/schema.sql a partir del esquema que exporta Room (exportSchema = true):
  app/schemas/com.example.pfc_app.datos.BaseDatosLocal/<versión>.json

El archivo tiene, en este orden:
  1. el createSql de cada una de las tablas, con ${TABLE_NAME} reemplazado;
  2. el createSql de sus índices;
  3. los CREATE TRIGGER que imponen los CHECK, que Room no admite en @Entity. Se leen de la
     lista CHECKS de app/src/main/java/com/example/pfc_app/datos/RestriccionesCheck.kt y se
     escriben con el mismo formato que usa la app.

Uso, desde la raíz del proyecto (después de compilar, que exporta el JSON):
    python herramientas/esquema_desde_room.py                 # la versión más alta
    python herramientas/esquema_desde_room.py --version 2
    python herramientas/esquema_desde_room.py --comparar esquema_dispositivo.sql

--comparar recibe la salida de
    adb shell run-as com.example.pfc_app sqlite3 databases/pfc_app.db .schema
y compara las tablas, índices y triggers de schema.sql con los de la base real, tal como
quedan en sqlite_master (SQLite quita IF NOT EXISTS al guardarlos).

Solo usa la biblioteca estándar. Escribe únicamente app/schemas/schema.sql.
"""

import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
ESQUEMAS = RAIZ / "app" / "schemas"
BASE_ROOM = "com.example.pfc_app.datos.BaseDatosLocal"
RESTRICCIONES = RAIZ / "app" / "src" / "main" / "java" / "com" / "example" / "pfc_app" / "datos" / "RestriccionesCheck.kt"
SALIDA = ESQUEMAS / "schema.sql"

# Una entrada de RestriccionesCheck.CHECKS: Check("tabla", "columna", "condición con NEW.columna"),
PATRON_CHECK = re.compile(r'^\s*Check\("(\w+)", "(\w+)", "([^"\\$]*)"\),\s*$', re.MULTILINE)


def json_de_room(version: int | None) -> Path:
    carpeta = ESQUEMAS / BASE_ROOM
    versiones = sorted(int(p.stem) for p in carpeta.glob("*.json") if p.stem.isdigit())
    if not versiones:
        sys.exit(f"No hay esquemas exportados en {carpeta}: compile la app primero")
    elegida = versiones[-1] if version is None else version
    if elegida not in versiones:
        sys.exit(f"No existe la versión {elegida}; hay {versiones}")
    return carpeta / f"{elegida}.json"


def leer_checks() -> list[tuple[str, str, str]]:
    checks = PATRON_CHECK.findall(RESTRICCIONES.read_text(encoding="utf-8"))
    if not checks:
        sys.exit(f"No se encontró la lista CHECKS en {RESTRICCIONES}")
    return checks


def sentencias_trigger(tabla: str, columna: str, condicion: str) -> list[str]:
    """Igual que RestriccionesCheck.sentencia(): BEFORE INSERT y BEFORE UPDATE OF la columna."""
    return [
        f"CREATE TRIGGER IF NOT EXISTS chk_{tabla}_{columna}_{sufijo} BEFORE {evento} ON {tabla}\n"
        f"WHEN NOT ({condicion})\n"
        f"BEGIN SELECT RAISE(ABORT, 'CHECK {tabla}.{columna}'); END"
        for sufijo, evento in (("ins", "INSERT"), ("upd", f"UPDATE OF {columna}"))
    ]


def generar(ruta_json: Path) -> str:
    db = json.loads(ruta_json.read_text(encoding="utf-8"))["database"]
    entidades = db["entities"]
    columnas = {e["tableName"]: {f["columnName"] for f in e["fields"]} for e in entidades}
    checks = leer_checks()
    for tabla, columna, _ in checks:
        if columna not in columnas.get(tabla, ()):
            sys.exit(f"El CHECK de {tabla}.{columna} no corresponde a ninguna columna del esquema de Room")

    relativo = ruta_json.relative_to(RAIZ).as_posix()
    lineas = [
        f"-- Esquema de la base local pfc_app.db, versión {db['version']}.",
        f"-- Generado por herramientas/esquema_desde_room.py a partir de {relativo},",
        "-- que exporta Room (exportSchema = true). No editar a mano: volver a generarlo.",
        f"-- identityHash de Room: {db['identityHash']}",
        "-- Además, Room crea room_master_table y SQLite crea sqlite_sequence (AUTOINCREMENT).",
        "",
        f"-- Tablas ({len(entidades)})",
        "",
    ]
    for e in entidades:
        lineas.append(e["createSql"].replace("${TABLE_NAME}", e["tableName"]) + ";")
        lineas.append("")

    indices = [(e["tableName"], i) for e in entidades for i in e.get("indices", [])]
    lineas += [f"-- Índices ({len(indices)})", ""]
    for tabla, i in indices:
        lineas.append(i["createSql"].replace("${TABLE_NAME}", tabla) + ";")
    lineas.append("")

    lineas += [
        f"-- Restricciones CHECK como triggers ({len(checks)} CHECK, {2 * len(checks)} triggers)",
        "--",
        "-- Room no admite CHECK en las entidades (@Entity). Cada CHECK del esquema se impone con",
        "-- un trigger BEFORE INSERT y otro BEFORE UPDATE OF su columna, que abortan la sentencia",
        "-- con RAISE(ABORT) si la condición es falsa (si es NULL pasa, como en un CHECK). La app",
        "-- los crea en RoomDatabase.Callback, al crear la base y al abrirla (RestriccionesCheck.kt).",
        "",
    ]
    for tabla, columna, condicion in checks:
        lineas.append(f"-- CHECK ({condicion.replace('NEW.', '')})")
        for s in sentencias_trigger(tabla, columna, condicion):
            lineas.append(s + ";")
        lineas.append("")
    return "\n".join(lineas)


def sentencias(texto: str) -> list[str]:
    """Divide un guion SQL en sentencias completas (los ; dentro de BEGIN ... END no cortan)."""
    salida, actual = [], ""
    for linea in texto.splitlines(keepends=True):
        actual += linea
        if sqlite3.complete_statement(actual):
            salida.append(actual.strip())
            actual = ""
    # Lo que quede sin cerrar, salvo comentarios, se ejecuta igual para que falle a la vista.
    resto = "\n".join(l for l in actual.splitlines() if not l.strip().startswith("--")).strip()
    if resto:
        salida.append(resto)
    return salida


def objetos(texto: str) -> dict[tuple[str, str], str]:
    """Ejecuta el guion en una base en memoria y devuelve sqlite_master: (tipo, nombre) -> sql."""
    con = sqlite3.connect(":memory:")
    for s in sentencias(texto):
        # sqlite_sequence la crea SQLite sola; no se puede crear a mano.
        if re.match(r"^(--[^\n]*\n\s*)*CREATE TABLE\s+[`\"]?sqlite_", s, re.IGNORECASE):
            continue
        con.execute(s)
    filas = con.execute("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL").fetchall()
    con.close()
    return {(t, n): " ".join(sql.split()) for t, n, sql in filas}


def comparar(esquema: str, ruta_dispositivo: Path) -> int:
    crudo = ruta_dispositivo.read_bytes()
    # En Windows PowerShell 5.1, ">" guarda la salida de adb en UTF-16.
    codificacion = "utf-16" if crudo[:2] in (b"\xff\xfe", b"\xfe\xff") else "utf-8-sig"
    texto = crudo.decode(codificacion).replace("\r\n", "\n")
    propios, dispositivo = objetos(esquema), objetos(texto)
    solo_dispositivo = sorted(set(dispositivo) - set(propios))
    solo_esquema = sorted(set(propios) - set(dispositivo))
    distintos = sorted(k for k in set(propios) & set(dispositivo) if propios[k] != dispositivo[k])
    print(f"Objetos en schema.sql: {len(propios)}; en la base del dispositivo: {len(dispositivo)}")
    for t, n in solo_dispositivo:
        print(f"  solo en el dispositivo: {t} {n}")
    for t, n in solo_esquema:
        print(f"  solo en schema.sql: {t} {n}")
    for k in distintos:
        print(f"  distinto: {k[0]} {k[1]}\n    schema.sql:  {propios[k]}\n    dispositivo: {dispositivo[k]}")
    # Las tablas propias de Android y de Room no forman parte del esquema de la app.
    esperadas = {("table", "android_metadata"), ("table", "room_master_table")}
    diferencias = [k for k in solo_dispositivo if k not in esperadas] + solo_esquema + distintos
    print("Coinciden (en la base real sobran solo android_metadata y room_master_table)"
          if not diferencias else f"{len(diferencias)} diferencias")
    return 0 if not diferencias else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", type=int, help="versión del esquema de Room (por defecto, la más alta)")
    ap.add_argument("--comparar", type=Path, help="salida de sqlite3 .schema de la base real")
    args = ap.parse_args()

    ruta_json = json_de_room(args.version)
    esquema = generar(ruta_json)
    creados = objetos(esquema)
    tipos = {t: sum(1 for k in creados if k[0] == t and not k[1].startswith("sqlite_"))
             for t in ("table", "index", "trigger")}
    # Sin cambios no se reescribe: git en Windows lo daría por modificado solo por los fines de línea.
    anterior = SALIDA.read_text(encoding="utf-8").replace("\r\n", "\n") if SALIDA.exists() else None
    if anterior != esquema:
        SALIDA.write_text(esquema, encoding="utf-8", newline="\n")
    print(f"{SALIDA.relative_to(RAIZ).as_posix()} desde {ruta_json.name}"
          f"{' (sin cambios)' if anterior == esquema else ''}: "
          f"{tipos['table']} tablas, {tipos['index']} índices y {tipos['trigger']} triggers")
    return comparar(esquema, args.comparar) if args.comparar else 0


if __name__ == "__main__":
    sys.exit(main())
