-- Esquema de la base local pfc_app.db, versión 3.
-- Generado por herramientas/esquema_desde_room.py a partir de app/schemas/com.example.pfc_app.datos.BaseDatosLocal/3.json,
-- que exporta Room (exportSchema = true). No editar a mano: volver a generarlo.
-- identityHash de Room: 813fcc93c481b4e48472375115e538e0
-- Además, Room crea room_master_table y SQLite crea sqlite_sequence (AUTOINCREMENT).

-- Tablas (9)

CREATE TABLE IF NOT EXISTS `vehiculo` (`id_vehiculo` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `codigo` TEXT NOT NULL, `marca` TEXT NOT NULL, `modelo` TEXT NOT NULL, `anio` INTEGER NOT NULL, `cilindros` INTEGER NOT NULL, `familia_gestion` TEXT);

CREATE TABLE IF NOT EXISTS `atencion` (`id_atencion` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id_vehiculo` INTEGER NOT NULL, `fecha` TEXT NOT NULL, `baro_kpa` REAL, `respaldada` INTEGER NOT NULL DEFAULT 0, `respaldada_en` TEXT, `uid_respaldo` TEXT, FOREIGN KEY(`id_vehiculo`) REFERENCES `vehiculo`(`id_vehiculo`) ON UPDATE NO ACTION ON DELETE NO ACTION );

CREATE TABLE IF NOT EXISTS `regimen` (`id_regimen` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id_atencion` INTEGER NOT NULL, `tipo` TEXT NOT NULL, `muestras_utiles` INTEGER NOT NULL DEFAULT 0, `descartadas_transitorio` INTEGER NOT NULL DEFAULT 0, `descartadas_techo` INTEGER NOT NULL DEFAULT 0, `frecuencia_hz` REAL, FOREIGN KEY(`id_atencion`) REFERENCES `atencion`(`id_atencion`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE IF NOT EXISTS `lectura` (`id_lectura` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id_regimen` INTEGER NOT NULL, `instante` REAL NOT NULL, `rpm` REAL, `temp_refrigerante` REAL, `stft` REAL, `ltft` REAL, `carga_calculada` REAL, `avance_encendido` REAL, `maf` REAL, `map` REAL, `velocidad` INTEGER, FOREIGN KEY(`id_regimen`) REFERENCES `regimen`(`id_regimen`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE IF NOT EXISTS `codigo_dtc` (`id_codigo` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id_atencion` INTEGER NOT NULL, `codigo` TEXT NOT NULL, `tipo` TEXT NOT NULL, FOREIGN KEY(`id_atencion`) REFERENCES `atencion`(`id_atencion`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE IF NOT EXISTS `contador_modo06` (`id_contador` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id_atencion` INTEGER NOT NULL, `cilindro` INTEGER NOT NULL, `conteo_fallo` INTEGER, `conteo_promedio` INTEGER, FOREIGN KEY(`id_atencion`) REFERENCES `atencion`(`id_atencion`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE IF NOT EXISTS `dictamen` (`id_dictamen` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id_atencion` INTEGER NOT NULL, `resultado` TEXT NOT NULL, `observacion` TEXT, FOREIGN KEY(`id_atencion`) REFERENCES `atencion`(`id_atencion`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE IF NOT EXISTS `diagnostico` (`id_diagnostico` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id_atencion` INTEGER NOT NULL, `clase` TEXT NOT NULL, `probabilidad` REAL NOT NULL, `version_modelo` TEXT NOT NULL, FOREIGN KEY(`id_atencion`) REFERENCES `atencion`(`id_atencion`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE IF NOT EXISTS `parametro_desviado` (`id_parametro` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id_diagnostico` INTEGER NOT NULL, `parametro` TEXT NOT NULL, `valor` REAL NOT NULL, `referencia` REAL, `direccion` TEXT NOT NULL, FOREIGN KEY(`id_diagnostico`) REFERENCES `diagnostico`(`id_diagnostico`) ON UPDATE NO ACTION ON DELETE CASCADE );

-- Índices (9)

CREATE UNIQUE INDEX IF NOT EXISTS `idx_vehiculo_codigo` ON `vehiculo` (`codigo`);
CREATE INDEX IF NOT EXISTS `idx_atencion_vehiculo_fecha` ON `atencion` (`id_vehiculo`, `fecha`);
CREATE UNIQUE INDEX IF NOT EXISTS `idx_regimen_atencion_tipo` ON `regimen` (`id_atencion`, `tipo`);
CREATE INDEX IF NOT EXISTS `idx_lectura_regimen_instante` ON `lectura` (`id_regimen`, `instante`);
CREATE INDEX IF NOT EXISTS `idx_codigo_dtc_atencion` ON `codigo_dtc` (`id_atencion`);
CREATE INDEX IF NOT EXISTS `idx_contador_modo06_atencion` ON `contador_modo06` (`id_atencion`);
CREATE UNIQUE INDEX IF NOT EXISTS `idx_dictamen_atencion` ON `dictamen` (`id_atencion`);
CREATE UNIQUE INDEX IF NOT EXISTS `idx_diagnostico_atencion` ON `diagnostico` (`id_atencion`);
CREATE INDEX IF NOT EXISTS `idx_parametro_desviado_diagnostico` ON `parametro_desviado` (`id_diagnostico`);

-- Restricciones CHECK como triggers (18 CHECK, 36 triggers)
--
-- Room no admite CHECK en las entidades (@Entity). Cada CHECK del esquema se impone con
-- un trigger BEFORE INSERT y otro BEFORE UPDATE OF su columna, que abortan la sentencia
-- con RAISE(ABORT) si la condición es falsa (si es NULL pasa, como en un CHECK). La app
-- los crea en RoomDatabase.Callback, al crear la base y al abrirla (RestriccionesCheck.kt).

-- CHECK (codigo GLOB 'VEH-[0-9][0-9][0-9]*')
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_codigo_ins BEFORE INSERT ON vehiculo
WHEN NOT (NEW.codigo GLOB 'VEH-[0-9][0-9][0-9]*')
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.codigo'); END;
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_codigo_upd BEFORE UPDATE OF codigo ON vehiculo
WHEN NOT (NEW.codigo GLOB 'VEH-[0-9][0-9][0-9]*')
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.codigo'); END;

-- CHECK (length(trim(marca)) > 0)
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_marca_ins BEFORE INSERT ON vehiculo
WHEN NOT (length(trim(NEW.marca)) > 0)
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.marca'); END;
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_marca_upd BEFORE UPDATE OF marca ON vehiculo
WHEN NOT (length(trim(NEW.marca)) > 0)
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.marca'); END;

-- CHECK (length(trim(modelo)) > 0)
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_modelo_ins BEFORE INSERT ON vehiculo
WHEN NOT (length(trim(NEW.modelo)) > 0)
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.modelo'); END;
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_modelo_upd BEFORE UPDATE OF modelo ON vehiculo
WHEN NOT (length(trim(NEW.modelo)) > 0)
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.modelo'); END;

-- CHECK (anio BETWEEN 1900 AND 2100)
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_anio_ins BEFORE INSERT ON vehiculo
WHEN NOT (NEW.anio BETWEEN 1900 AND 2100)
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.anio'); END;
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_anio_upd BEFORE UPDATE OF anio ON vehiculo
WHEN NOT (NEW.anio BETWEEN 1900 AND 2100)
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.anio'); END;

-- CHECK (cilindros BETWEEN 1 AND 16)
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_cilindros_ins BEFORE INSERT ON vehiculo
WHEN NOT (NEW.cilindros BETWEEN 1 AND 16)
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.cilindros'); END;
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_cilindros_upd BEFORE UPDATE OF cilindros ON vehiculo
WHEN NOT (NEW.cilindros BETWEEN 1 AND 16)
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.cilindros'); END;

-- CHECK (familia_gestion IN ('MAF', 'MAP', 'ambos'))
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_familia_gestion_ins BEFORE INSERT ON vehiculo
WHEN NOT (NEW.familia_gestion IN ('MAF', 'MAP', 'ambos'))
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.familia_gestion'); END;
CREATE TRIGGER IF NOT EXISTS chk_vehiculo_familia_gestion_upd BEFORE UPDATE OF familia_gestion ON vehiculo
WHEN NOT (NEW.familia_gestion IN ('MAF', 'MAP', 'ambos'))
BEGIN SELECT RAISE(ABORT, 'CHECK vehiculo.familia_gestion'); END;

-- CHECK (tipo IN ('R1', 'R2', 'R3'))
CREATE TRIGGER IF NOT EXISTS chk_regimen_tipo_ins BEFORE INSERT ON regimen
WHEN NOT (NEW.tipo IN ('R1', 'R2', 'R3'))
BEGIN SELECT RAISE(ABORT, 'CHECK regimen.tipo'); END;
CREATE TRIGGER IF NOT EXISTS chk_regimen_tipo_upd BEFORE UPDATE OF tipo ON regimen
WHEN NOT (NEW.tipo IN ('R1', 'R2', 'R3'))
BEGIN SELECT RAISE(ABORT, 'CHECK regimen.tipo'); END;

-- CHECK (muestras_utiles >= 0)
CREATE TRIGGER IF NOT EXISTS chk_regimen_muestras_utiles_ins BEFORE INSERT ON regimen
WHEN NOT (NEW.muestras_utiles >= 0)
BEGIN SELECT RAISE(ABORT, 'CHECK regimen.muestras_utiles'); END;
CREATE TRIGGER IF NOT EXISTS chk_regimen_muestras_utiles_upd BEFORE UPDATE OF muestras_utiles ON regimen
WHEN NOT (NEW.muestras_utiles >= 0)
BEGIN SELECT RAISE(ABORT, 'CHECK regimen.muestras_utiles'); END;

-- CHECK (descartadas_transitorio >= 0)
CREATE TRIGGER IF NOT EXISTS chk_regimen_descartadas_transitorio_ins BEFORE INSERT ON regimen
WHEN NOT (NEW.descartadas_transitorio >= 0)
BEGIN SELECT RAISE(ABORT, 'CHECK regimen.descartadas_transitorio'); END;
CREATE TRIGGER IF NOT EXISTS chk_regimen_descartadas_transitorio_upd BEFORE UPDATE OF descartadas_transitorio ON regimen
WHEN NOT (NEW.descartadas_transitorio >= 0)
BEGIN SELECT RAISE(ABORT, 'CHECK regimen.descartadas_transitorio'); END;

-- CHECK (descartadas_techo >= 0)
CREATE TRIGGER IF NOT EXISTS chk_regimen_descartadas_techo_ins BEFORE INSERT ON regimen
WHEN NOT (NEW.descartadas_techo >= 0)
BEGIN SELECT RAISE(ABORT, 'CHECK regimen.descartadas_techo'); END;
CREATE TRIGGER IF NOT EXISTS chk_regimen_descartadas_techo_upd BEFORE UPDATE OF descartadas_techo ON regimen
WHEN NOT (NEW.descartadas_techo >= 0)
BEGIN SELECT RAISE(ABORT, 'CHECK regimen.descartadas_techo'); END;

-- CHECK (length(codigo) > 0)
CREATE TRIGGER IF NOT EXISTS chk_codigo_dtc_codigo_ins BEFORE INSERT ON codigo_dtc
WHEN NOT (length(NEW.codigo) > 0)
BEGIN SELECT RAISE(ABORT, 'CHECK codigo_dtc.codigo'); END;
CREATE TRIGGER IF NOT EXISTS chk_codigo_dtc_codigo_upd BEFORE UPDATE OF codigo ON codigo_dtc
WHEN NOT (length(NEW.codigo) > 0)
BEGIN SELECT RAISE(ABORT, 'CHECK codigo_dtc.codigo'); END;

-- CHECK (tipo IN ('almacenado', 'pendiente'))
CREATE TRIGGER IF NOT EXISTS chk_codigo_dtc_tipo_ins BEFORE INSERT ON codigo_dtc
WHEN NOT (NEW.tipo IN ('almacenado', 'pendiente'))
BEGIN SELECT RAISE(ABORT, 'CHECK codigo_dtc.tipo'); END;
CREATE TRIGGER IF NOT EXISTS chk_codigo_dtc_tipo_upd BEFORE UPDATE OF tipo ON codigo_dtc
WHEN NOT (NEW.tipo IN ('almacenado', 'pendiente'))
BEGIN SELECT RAISE(ABORT, 'CHECK codigo_dtc.tipo'); END;

-- CHECK (cilindro >= 1)
CREATE TRIGGER IF NOT EXISTS chk_contador_modo06_cilindro_ins BEFORE INSERT ON contador_modo06
WHEN NOT (NEW.cilindro >= 1)
BEGIN SELECT RAISE(ABORT, 'CHECK contador_modo06.cilindro'); END;
CREATE TRIGGER IF NOT EXISTS chk_contador_modo06_cilindro_upd BEFORE UPDATE OF cilindro ON contador_modo06
WHEN NOT (NEW.cilindro >= 1)
BEGIN SELECT RAISE(ABORT, 'CHECK contador_modo06.cilindro'); END;

-- CHECK (conteo_fallo >= 0)
CREATE TRIGGER IF NOT EXISTS chk_contador_modo06_conteo_fallo_ins BEFORE INSERT ON contador_modo06
WHEN NOT (NEW.conteo_fallo >= 0)
BEGIN SELECT RAISE(ABORT, 'CHECK contador_modo06.conteo_fallo'); END;
CREATE TRIGGER IF NOT EXISTS chk_contador_modo06_conteo_fallo_upd BEFORE UPDATE OF conteo_fallo ON contador_modo06
WHEN NOT (NEW.conteo_fallo >= 0)
BEGIN SELECT RAISE(ABORT, 'CHECK contador_modo06.conteo_fallo'); END;

-- CHECK (conteo_promedio >= 0)
CREATE TRIGGER IF NOT EXISTS chk_contador_modo06_conteo_promedio_ins BEFORE INSERT ON contador_modo06
WHEN NOT (NEW.conteo_promedio >= 0)
BEGIN SELECT RAISE(ABORT, 'CHECK contador_modo06.conteo_promedio'); END;
CREATE TRIGGER IF NOT EXISTS chk_contador_modo06_conteo_promedio_upd BEFORE UPDATE OF conteo_promedio ON contador_modo06
WHEN NOT (NEW.conteo_promedio >= 0)
BEGIN SELECT RAISE(ABORT, 'CHECK contador_modo06.conteo_promedio'); END;

-- CHECK (resultado IN ('completa', 'parcial'))
CREATE TRIGGER IF NOT EXISTS chk_dictamen_resultado_ins BEFORE INSERT ON dictamen
WHEN NOT (NEW.resultado IN ('completa', 'parcial'))
BEGIN SELECT RAISE(ABORT, 'CHECK dictamen.resultado'); END;
CREATE TRIGGER IF NOT EXISTS chk_dictamen_resultado_upd BEFORE UPDATE OF resultado ON dictamen
WHEN NOT (NEW.resultado IN ('completa', 'parcial'))
BEGIN SELECT RAISE(ABORT, 'CHECK dictamen.resultado'); END;

-- CHECK (probabilidad BETWEEN 0 AND 1)
CREATE TRIGGER IF NOT EXISTS chk_diagnostico_probabilidad_ins BEFORE INSERT ON diagnostico
WHEN NOT (NEW.probabilidad BETWEEN 0 AND 1)
BEGIN SELECT RAISE(ABORT, 'CHECK diagnostico.probabilidad'); END;
CREATE TRIGGER IF NOT EXISTS chk_diagnostico_probabilidad_upd BEFORE UPDATE OF probabilidad ON diagnostico
WHEN NOT (NEW.probabilidad BETWEEN 0 AND 1)
BEGIN SELECT RAISE(ABORT, 'CHECK diagnostico.probabilidad'); END;

-- CHECK (direccion IN ('mayor', 'menor'))
CREATE TRIGGER IF NOT EXISTS chk_parametro_desviado_direccion_ins BEFORE INSERT ON parametro_desviado
WHEN NOT (NEW.direccion IN ('mayor', 'menor'))
BEGIN SELECT RAISE(ABORT, 'CHECK parametro_desviado.direccion'); END;
CREATE TRIGGER IF NOT EXISTS chk_parametro_desviado_direccion_upd BEFORE UPDATE OF direccion ON parametro_desviado
WHEN NOT (NEW.direccion IN ('mayor', 'menor'))
BEGIN SELECT RAISE(ABORT, 'CHECK parametro_desviado.direccion'); END;
