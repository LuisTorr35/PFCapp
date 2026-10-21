# PFCapp

Aplicación Android de diagnóstico asistido de fallas de motor en vehículos ligeros de gasolina, mediante OBD-II y aprendizaje automático. Proyecto de Fin de Carrera de Luis Enrique Torres Quispe.

La aplicación se conecta por Bluetooth clásico (perfil de puerto serie) a un adaptador ELM327 y captura la atención con el protocolo de medición: lectura inicial de códigos y del modo 06, y los regímenes R1, R2 y R3. Después valida la atención con las reglas del protocolo, calcula la fila de características igual que `consolidar_lecturas.py` y ejecuta el modelo ONNX incluido. Muestra el sistema afectado más probable, los parámetros desviados con su referencia sin falla y los códigos leídos. Todo ocurre en el teléfono, sin internet. Hay además un modo demostración que reproduce atenciones grabadas.

## Estructura

| Ruta | Contenido |
|---|---|
| `app/src/main/java/com/example/pfc_app/` | Código de la app, organizado según el Capítulo 5 del documento: `ui`, `adquisicion`, `validacion`, `procesamiento`, `inferencia`, `datos` y `modelos`, más `cuenta` (inicio de sesión con Google) |
| `app/src/test/` | Pruebas unitarias en JVM: equivalencia con el consolidador, reglas de validación, inferencia, protocolo ELM327 y servicio de captura |
| `app/src/androidTest/` | Pruebas instrumentadas: base de datos, pantallas, flujo de demostración e inferencia con ONNX Runtime para Android |
| `modelo/scripts/` | Corte de datos, comparación de Random Forest, k-NN y SVM, exportación a ONNX y verificación |
| `herramientas/preparar_recursos.py` | Copia a la app los archivos que no se versionan |
| `app/schemas/` | Esquema de la base local que exporta Room (`<versión>.json`) y `schema.sql`, generado a partir de él |
| `herramientas/esquema_desde_room.py` | Escribe `app/schemas/schema.sql` desde el JSON de Room y lo compara con la base real |

## Archivos que no están en el repositorio

El repositorio solo guarda código. No incluye el modelo (`*.onnx` y su contrato), las atenciones grabadas (CSV), las salidas del modelo ni la documentación. Para compilar y probar hay que regenerarlos desde el repositorio de la tesis.

### 1. Regenerar el modelo (opcional, si no se tiene `modelo/salida`)

Requiere Python 3.13. Desde `modelo/`:

```
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
```

1. Copiar `Tes/datos/crudo` (sin `_descartes`) a `modelo/datos/crudo` y `Tes/datos/ficha` a `modelo/datos/ficha`.
2. Copiar `Tes/scripts/consolidar_lecturas.py` a `modelo/referencia/`.
3. Ejecutar en orden:

```
.venv\Scripts\python -I -X utf8 referencia\consolidar_lecturas.py datos\crudo procesado
.venv\Scripts\python -I -X utf8 scripts\preparar_corte.py
.venv\Scripts\python -I -X utf8 scripts\comparar_modelos.py
.venv\Scripts\python -I -X utf8 scripts\exportar_modelo.py
.venv\Scripts\python -I -X utf8 scripts\verificar_onnx.py
```

Los scripts dejan en `modelo/salida/` el modelo `modelo_pfc.onnx`, el `contrato_modelo.json` y `casos_prueba_inferencia.csv`.

### 2. Copiar el modelo y las atenciones a la app

Desde la raíz del proyecto:

```
python herramientas/preparar_recursos.py --tes C:/Users/Luis/Documents/Tes
```

El script copia:
- el modelo y su contrato a `app/src/main/assets/modelo/`;
- las atenciones de demostración que lista `app/src/main/assets/demo/catalogo.json` a `assets/demo/`;
- las atenciones de `Tes/datos/crudo` y la tabla del consolidador a `app/src/test/resources/`;
- los casos de prueba a las pruebas unitarias y a las instrumentadas.

Solo lee del repositorio de la tesis.

### 3. Configuración de Firebase (`google-services.json`)

La app inicia sesión con Google mediante Firebase Authentication y Credential Manager. El archivo `app/google-services.json` tiene la configuración del proyecto de Firebase y no se versiona (`.gitignore`). Sin él, la compilación falla en la tarea `processDebugGoogleServices`. Para obtenerlo:

1. En la [consola de Firebase](https://console.firebase.google.com), abrir el proyecto y entrar a *Configuración del proyecto › General › Tus apps*. Elegir la app Android `com.example.pfc_app` o registrarla con ese nombre de paquete.
2. Agregar en esa app la huella SHA-1 del certificado que firma el APK. Para la variante debug se obtiene con `./gradlew signingReport`. Sin la huella, Google rechaza el inicio de sesión.
3. Descargar `google-services.json` y copiarlo en `app/`, junto a `app/build.gradle.kts`.
4. En *Authentication › Método de acceso*, activar el proveedor Google.

El plugin `com.google.gms.google-services` lee el archivo y genera `default_web_client_id`, el id de cliente web que usa la opción "Continuar con Google". Solo el primer inicio de sesión necesita internet. Firebase guarda la sesión en el teléfono, y después la app abre directo en Vehículos, también sin conexión.

## Compilar, probar e instalar

```
./gradlew assembleDebug testDebugUnitTest
./gradlew connectedDebugAndroidTest        # con un emulador o teléfono conectado
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **SDK:** minSdk 29 (Android 10), targetSdk 37.
- **Room y KSP:** Room 2.8.5 con KSP 2.2.10-2.0.2. Esa versión de KSP agrega las fuentes generadas con `kotlin.sourceSets`, que el Kotlin integrado de AGP 9 rechaza por defecto; por eso `gradle.properties` lleva `android.disallowKotlinSourceSets=false`.
- **ONNX Runtime en las pruebas unitarias:** usan la versión de escritorio 1.17.3. La JBR de Android Studio trae un `msvcp140.dll` antiguo, incompatible con versiones más nuevas.
- **Firebase y googleid:** BoM de Firebase 34.19.0 y googleid 1.1.1. La BoM 35 (firebase-auth 25) y googleid 1.2 están compiladas con Kotlin 2.4, y el Kotlin 2.2 integrado en AGP 9.4 no lee esos metadatos.

## Esquema de la base de datos

La base local (`pfc_app.db`) usa Room. Al compilar, Room exporta el esquema a `app/schemas/com.example.pfc_app.datos.BaseDatosLocal/<versión>.json`. Room no admite CHECK en las entidades: cada CHECK se impone con dos triggers, `BEFORE INSERT` y `BEFORE UPDATE`, definidos en `datos/RestriccionesCheck.kt`.

Para regenerar `app/schemas/schema.sql` y compararlo con la base de un dispositivo donde ya se abrió la app:

```
python herramientas/esquema_desde_room.py
adb shell run-as com.example.pfc_app sqlite3 databases/pfc_app.db .schema > esquema_dispositivo.sql
python herramientas/esquema_desde_room.py --comparar esquema_dispositivo.sql
```

## Registros para depurar

Cada módulo escribe en Logcat con su propia etiqueta:

```
adb logcat -s PFC.BT PFC.Captura PFC.Validacion PFC.Procesamiento PFC.Inferencia PFC.Datos PFC.UI PFC.Nube
```

## Ramas

- `main`: versiones entregables.
- `develop`: integración.
- `feature/...`: una rama por componente o corrección, que se integra a `develop`.
