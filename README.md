# PFCapp

Aplicación Android de diagnóstico asistido de fallas de motor en vehículos ligeros de gasolina, mediante OBD-II y aprendizaje automático. Proyecto de Fin de Carrera de Luis Enrique Torres Quispe.

La aplicación se conecta por Bluetooth clásico (perfil de puerto serie) a un adaptador ELM327 y captura la atención con el protocolo de medición: lectura inicial de códigos y del modo 06, y los regímenes R1, R2 y R3. Después valida la atención con las reglas del protocolo, calcula la fila de características igual que `consolidar_lecturas.py` y ejecuta el modelo ONNX incluido. Muestra el sistema afectado más probable, los parámetros desviados con su referencia sin falla y los códigos leídos. Todo ocurre en el teléfono, sin internet. Hay además un modo demostración que reproduce atenciones grabadas.

## Estructura

| Ruta | Contenido |
|---|---|
| `app/src/main/java/com/example/pfc_app/` | Código de la app, organizado según el Capítulo 5 del documento: `ui`, `adquisicion`, `validacion`, `procesamiento`, `inferencia`, `datos` y `modelos`, más `cuenta` (inicio de sesión con Google) y `respaldo` (respaldo en Cloud Firestore) |
| `app/src/test/` | Pruebas unitarias en JVM: equivalencia con el consolidador, reglas de validación, inferencia, protocolo ELM327 y servicio de captura |
| `app/src/androidTest/` | Pruebas instrumentadas: base de datos, pantallas, flujo de demostración e inferencia con ONNX Runtime para Android |
| `modelo/scripts/` | Corte de datos, comparación de Random Forest, k-NN y SVM, exportación a ONNX y verificación |
| `herramientas/preparar_recursos.py` | Copia a la app los archivos que no se versionan |
| `app/schemas/` | Esquema de la base local que exporta Room (`<versión>.json`) y `schema.sql`, generado a partir de él |
| `herramientas/esquema_desde_room.py` | Escribe `app/schemas/schema.sql` desde el JSON de Room y lo compara con la base real |
| `firestore.rules`, `firebase.json` | Reglas de seguridad de Cloud Firestore: cada cuenta solo lee y escribe en `usuarios/{su uid}` |
| `app/src/debug/` | Solo en debug: órdenes por adb para verificar el respaldo (`depuracion/ReceptorDepuracion.kt`) |

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

## Respaldo en Cloud Firestore

Room sigue siendo la base principal: la captura, el diagnóstico y el historial no dependen de la red. Funciona en el plan Spark gratuito, con Authentication y Firestore, sin Cloud Storage.

- **Al guardar:** cada atención guarda el uid de la cuenta con sesión abierta (`atencion.uid_respaldo`, versión 3 de la base) y queda pendiente (`respaldada = 0`).
- **Trabajo de respaldo:** `respaldo/TrabajoRespaldo.kt` es un trabajo único de WorkManager (`respaldo_atenciones`). Necesita red y reintenta con espera exponencial (30 s al principio). Se encola al guardar una atención, al abrir la app y al iniciar sesión. Sube las pendientes de la cuenta con sesión abierta y marca cada una como respaldada (`respaldada_en`) cuando Firestore confirma la escritura.
- **Documento:** cada atención es un solo documento en `usuarios/{uid}/atenciones/{idInstalación}-{id_atencion}`, con vehículo, dictamen, diagnóstico, parámetros desviados, códigos, modo 06 y regímenes con sus lecturas (`respaldo/DocumentoAtencion.kt`). El id es fijo y la escritura es un `set`, así que subirla dos veces no la duplica. El id de instalación evita que otro teléfono o una reinstalación pisen documentos, porque los `id_atencion` de Room vuelven a empezar en 1 con cada base nueva. El documento más grande del corte ocupa unos 52 KB; el límite de Firestore es 1 MiB y la app exige menos de 900 KB.
- **Varias cuentas:** la base es del teléfono, no de la cuenta. Las pendientes de otra cuenta no se suben a la actual: esperan a que esa cuenta vuelva a iniciar sesión. Las atenciones guardadas antes de la versión 3 no tienen cuenta y pasan a la primera que inicia sesión.
- **Historial:** cada atención lleva una marca discreta, "✓ Respaldada" o "Pendiente de respaldo".
- **Cerrar sesión:** está en el menú "⋮" de Vehículos. Si la cuenta tiene atenciones sin respaldar, avisa antes de cerrar la sesión.
- **Caché de Firestore:** es en memoria. En el disco del teléfono no queda otra copia de las atenciones ni una cola de escrituras.

### Reglas de seguridad

`firestore.rules` permite a cada cuenta leer y escribir solo bajo `usuarios/{su uid}` y niega todo lo demás. Para publicarlas con la [Firebase CLI](https://firebase.google.com/docs/cli):

```
firebase login
firebase deploy --only firestore:rules --project <id-del-proyecto>
```

También se pueden pegar a mano. En la consola de Firebase, abrir *Firestore Database › Reglas*, reemplazar el contenido por el de `firestore.rules` y pulsar *Publicar*.

### Verificar el respaldo por adb (solo debug)

```
adb shell am broadcast -n com.example.pfc_app/.depuracion.ReceptorDepuracion -a com.example.pfc_app.ESTADO_RESPALDO
adb shell am broadcast -n com.example.pfc_app/.depuracion.ReceptorDepuracion -a com.example.pfc_app.LISTAR_NUBE
adb logcat -s PFC.Nube
```

`ESTADO_RESPALDO` muestra en Logcat cada atención local con su estado y su cuenta. `LISTAR_NUBE` lee del servidor los ids de `usuarios/{uid}/atenciones` de la sesión abierta. Con `--es uid <uid>` lee los de otra cuenta, y las reglas deben negar esa lectura.

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
