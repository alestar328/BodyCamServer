# Seguimiento de horas y desarrollo — Proyecto BodyCam / AeriaNexus

Registro único de horas y entregas para las **dos aplicaciones del proyecto**.

| App | Rol | Ruta local | Repo |
|---|---|---|---|
| **BodyCamServer** | Servidor / dispositivo bodycam (Android) | `C:\Users\newge\Desktop\Variedades\BodyCam\BodyCamServer` | [alestar328/BodyCamServer](https://github.com/alestar328/BodyCamServer) |
| **AeriaNexusPrototype** | App cliente / control (Android) | `C:\Users\newge\AndroidStudioProjects\AeriaNexusPrototype` | [alestar328/AeriaNexusPrototype](https://github.com/alestar328/AeriaNexusPrototype) |

**Inicio del proyecto:** 2026-04-23 (primer commit de BodyCamServer)
**Último pago recibido:** 2026-07-24
**Última actualización de este archivo:** 2026-08-15

---

## 0. Trazabilidad desde el último pago (2026-07-24 → 2026-08-15, 22 días)

El desglose es **por aplicación**, y dentro de cada aplicación por bloques de trabajo.
Cada bloque lleva un identificador con el prefijo de su app (`BC-n` para BodyCamServer,
`AN-n` para AeriaNexusPrototype) para que las horas se sumen al total del proyecto sin
perder de vista a qué app pertenecen.

---

## 0.A · Aplicación **BodyCamServer**

> Servidor / dispositivo bodycam · repo `alestar328/BodyCamServer`
> **2 bloques · 2 días de actividad · horas sin registrar**

### Bloque `BC-1` — Grabación continua · 2026-08-09 · ✅ entregado en `main`
> commit `d4a4709` "avance grabacion continua" — 13 archivos, +1027 / −2378

De las 2378 líneas borradas, **1940 son `.idea/caches/deviceStreaming.xml`** (ruido de IDE,
no cuenta como trabajo). El borrado real de código son ~438 líneas.

| Archivo | Δ | Nota |
|---|---|---|
| `EvidenceStore.kt` | **+290 (nuevo)** | Pieza central: buffer en anillo, pre-roll de 120 s, segmentos de 8 MB, promoción de incidentes por `renameTo()` (sin copiar bytes) |
| `RecordingActivity.kt` | +485 | Reescritura para grabación continua |
| `CameraController.kt` | **−257 (eliminado)** | Sustituido por el nuevo modelo |
| `FileServerService.kt` | +145 | |
| `UploadService.kt` | +106 | |
| `MainActivity.kt` | +74 | |
| `LivestreamService.kt` | +48 | |
| `BtServerService.kt` | +38 | |
| `Protocol.kt`, `PreviewController.kt`, `TorchController.kt`, `PhotoController.kt` | +22 | Ajustes menores |

### Bloque `BC-2` — Cifrado (medición) · 2026-08-14 · ⚠️ sin commitear en `develop`
> 4 archivos nuevos + 73 líneas en 3 existentes

| Archivo | Líneas | Qué es |
|---|---|---|
| `aes-256Sha256.py` | 1280 (nuevo) | Herramienta de cifrado AES-256 / SHA-256 |
| `SampleMP4-Generator.py` | 1086 (nuevo) | Generador de MP4 de prueba |
| `CryptoBenchmark.kt` | 230 (nuevo) | Coste real de hashear y cifrar en la unidad. Cifra por bloques porque Conscrypt acumula todo en `Cipher.update()` y revienta con OOM al pedirle 256 MB de una vez |
| `RecorderWatch.kt` | 134 (nuevo) | Watchdog de grabaciones largas. Detecta la *parada silenciosa* (encoder muerto sin callback) midiendo si el fichero deja de crecer |
| `FileServerService.kt` | +48 | Endpoint `GET /benchmark` + `resolve()` que corta el path traversal por `../` |
| `CameraController.kt` | +13 | Enganche del watch |
| `RecordingActivity.kt` | +12 | Enganche del watch |

Marcas de tiempo de los archivos: sesión del **2026-08-14, de 15:47 a 22:56**.

> **Naturaleza del bloque:** es trabajo de **medición previa al cifrado**, no una
> funcionalidad terminada. Los propios comentarios del código indican que es
> instrumentación y cómo retirarla. Sirve para decidir si cifrar en el dispositivo es
> viable — conviene facturarlo como tal, no como feature entregada.

### ⚠️ Conflicto interno de BodyCamServer

El historial es lineal: `main` = `develop` + `d4a4709`. Pero **`BC-2` está escrito sobre
`develop`, que no incluye `BC-1`**. Y `BC-1` **eliminó `CameraController.kt`**, que es justo
uno de los archivos que `BC-2` modifica (+13 líneas). Además `FileServerService.kt` está
tocado por los dos bloques.

**Integrar `BC-2` en `main` no será un merge limpio.** Hay que decidir a dónde va el
enganche de `RecorderWatch` ahora que `CameraController` no existe.

### Subtotal BodyCamServer

| Bloque | Fecha | Estado | Volumen real de código | Horas |
|---|---|---|---|---|
| `BC-1` Grabación continua / EvidenceStore | 2026-08-09 | ✅ Entregado (`main`) | ~1027 añadidas / ~438 borradas · 12 archivos | — |
| `BC-2` Cifrado (medición) | 2026-08-14 | ⚠️ Sin commitear (`develop`) | ~2803 añadidas · 7 archivos | — |
| **Subtotal app** | | **2 días de actividad** | **~3830 añadidas · 19 archivos** | **—** |

---

## 0.B · Aplicación **AeriaNexusPrototype**

> App cliente / control · repo `alestar328/AeriaNexusPrototype`
> **0 bloques · 0 días de actividad**

**Sin cambios desde el pago.** Último commit `74cba54` del **2026-07-22**, dos días *antes*
del pago. Árbol de trabajo limpio, sin stashes, sin ramas adicionales.

**Todo el trabajo de esta app está cubierto por el pago del 24 de julio.** No hay nada
pendiente de facturar aquí.

### Subtotal AeriaNexusPrototype

| Bloque | Fecha | Estado | Volumen real de código | Horas |
|---|---|---|---|---|
| — | — | Sin actividad posterior al pago | — | **0.0** |
| **Subtotal app** | | **0 días de actividad** | **0** | **0.0** |

---

## 0.C · Total del proyecto desde el pago

| Aplicación | Bloques | Días de actividad | Código añadido | Horas |
|---|---|---|---|---|
| BodyCamServer | `BC-1`, `BC-2` | 2 | ~3830 líneas · 19 archivos | — |
| AeriaNexusPrototype | — | 0 | 0 | 0.0 |
| **TOTAL PROYECTO** | **2** | **2** | **~3830 líneas · 19 archivos** | **— (sin registrar)** |

**Días con actividad desde el pago: 2** (2026-08-09 y 2026-08-14), ambos en BodyCamServer.

**Horas: no registradas** — este archivo se creó el 2026-08-15. Los volúmenes de código y
las franjas horarias son la única evidencia objetiva disponible. En cuanto rellenes horas
en §3, súbelas a la columna *Horas* de cada bloque y las tablas de subtotal y total cuadran
solas.

---

## 1. Estado actual

| Concepto | BodyCamServer | AeriaNexusPrototype |
|---|---|---|
| Rama actual | `develop` | `main` |
| Último commit | `d4a4709` — 2026-08-09 (en `main`) | `74cba54` — 2026-07-22 |
| Último commit de la rama de trabajo | `98cea82` — 2026-07-12 (`develop`) | `74cba54` — 2026-07-22 |
| Trabajo sin commitear | **Sí** (ver §5) | No |

**Última entrega (push a `main`):** 2026-08-09 → BodyCamServer `d4a4709` "avance grabacion continua"
**Días desde la última entrega:** 6 días (a fecha 2026-08-15)

> ⚠️ `develop` de BodyCamServer está **detrás** de `main`: `d4a4709` (2026-08-09) no está integrado en `develop`. Conviene rebasar/mergear antes de seguir.

---

## 2. Cómo usar este archivo

**Regla base:** toda hora se imputa **a una aplicación concreta** y, dentro de ella, a un
**bloque**. Las horas del proyecto son la suma de las dos apps, pero nunca se anotan sin
decir a cuál pertenecen.

- **App:** `BC` = BodyCamServer · `AN` = AeriaNexusPrototype.
  Si una sesión toca las dos, **pártela en dos filas** con sus horas repartidas — no uses
  "AMBAS", porque entonces el subtotal por app deja de cuadrar.
- **Bloque:** identificador `BC-n` / `AN-n`. Si el trabajo continúa un bloque existente,
  reutiliza su id; si abre una línea nueva, crea el siguiente número de esa app.

Pasos:

1. **Al empezar una sesión de trabajo**, apunta la hora de inicio.
2. **Al terminar**, añade una fila en §3 (Registro de sesiones) con:
   `Fecha | App | Bloque | Horas | Qué se hizo | Commit(s)`
   - Horas en decimal (`1.5` = 1 h 30 min).
3. **Cuando hagas un push a `main`**, añade una fila en §4 (Entregas) y reinicia el contador de horas no facturadas.
4. Actualiza los subtotales por app y el total del proyecto en §6.

Comando útil para ver qué se ha hecho desde la última entrega:

```powershell
git log --since="2026-08-09" --date=short --pretty=format:"%ad %h %s"
```

---

## 3. Registro de sesiones

> **Nota:** las horas anteriores al 2026-08-15 **no se registraron** en su momento. Las filas del histórico (§7) están reconstruidas a partir de los commits de git y llevan las horas marcadas como `—` (desconocido). Rellénalas manualmente si las tienes anotadas en otro sitio; a partir de aquí se registra en tiempo real.

| Fecha | App | Bloque | Horas | Qué se hizo | Commit(s) |
|---|---|---|---|---|---|
| 2026-08-15 | BC | — | — | Alta del sistema de seguimiento de horas (cubre las dos apps) | _(pendiente)_ |
|  |  |  |  |  |  |

---

## 4. Entregas

| # | Fecha | App(s) | Contenido | Commit | Horas facturadas |
|---|---|---|---|---|---|
| 1 | 2026-04-23 | BC | Primera subida del servidor | `60448a3` | — |
| 2 | 2026-07-10 | AN | Initial commit del prototipo cliente | `6bfbd55` | — |
| 3 | 2026-07-22 | AN | Bloqueo de screenrecorder, incidencias locales, versionado auto de debug | `74cba54` | — |
| 4 | 2026-08-09 | BC | Avance de grabación continua (EvidenceStore, buffer en anillo) | `d4a4709` | — |
|  |  |  |  |  |  |

> **Pago recibido el 2026-07-24**, cubre hasta la entrega #3 (`74cba54`, 2026-07-22).
> La entrega #4 y el trabajo del 2026-08-14 son **posteriores al pago** → ver §0.

---

## 5. Trabajo en curso (sin commitear)

**BodyCamServer** (`develop`, desde 2026-07-12):

| Archivo | Estado |
|---|---|
| `app/src/main/kotlin/com/falconone/bodycamserver/CameraController.kt` | Modificado |
| `app/src/main/kotlin/com/falconone/bodycamserver/FileServerService.kt` | Modificado |
| `app/src/main/kotlin/com/falconone/bodycamserver/RecordingActivity.kt` | Modificado |
| `app/src/main/kotlin/com/falconone/bodycamserver/CryptoBenchmark.kt` | Nuevo |
| `app/src/main/kotlin/com/falconone/bodycamserver/RecorderWatch.kt` | Nuevo |
| `SampleMP4-Generator.py` | Nuevo |
| `aes-256Sha256.py` | Nuevo |

**AeriaNexusPrototype:** árbol limpio.

---

## 6. Totales

Las horas se llevan **separadas por aplicación** y el total del proyecto es su suma.

### Desde el último pago (2026-07-24)

| Aplicación | Horas | Nota |
|---|---|---|
| BodyCamServer | **0.0** | 2 días de actividad sin cuantificar (`BC-1`, `BC-2`) — ver §0.A |
| AeriaNexusPrototype | **0.0** | Sin actividad — ver §0.B |
| **TOTAL** | **0.0** | |

### Desde la última entrega (2026-08-09)

| Aplicación | Horas | Nota |
|---|---|---|
| BodyCamServer | **0.0** | 1 sesión sin cuantificar (2026-08-14, `BC-2`) |
| AeriaNexusPrototype | **0.0** | Sin actividad |
| **TOTAL** | **0.0** | |

### Acumulado del proyecto

| Aplicación | Horas registradas | Horas reales |
|---|---|---|
| BodyCamServer | **0.0** | _desconocido — ver §7_ |
| AeriaNexusPrototype | **0.0** | _desconocido — ver §7_ |
| **TOTAL PROYECTO** | **0.0** | _desconocido_ |

---

## 7. Histórico de commits (reconstruido de git, horas no registradas)

### BodyCamServer

| Fecha | Commit | Descripción | Horas |
|---|---|---|---|
| 2026-08-09 | `d4a4709` | avance grabacion continua | — |
| 2026-07-12 | `98cea82` | gitignore | — |
| 2026-07-12 | `116a4c7` | arreglos de grabacion desde telefono | — |
| 2026-07-12 | `b69cc42` | bluetooth arreglado y funcionalidad de camara | — |
| 2026-07-10 | `d8a50e8` | arreglado bug de bluetooth | — |
| 2026-06-17 | `560756c` | arreglo luces e icono | — |
| 2026-06-16 | `8210f65` | icono agregado | — |
| 2026-06-04 | `cf69bcd` | avanzada y arreglada | — |
| 2026-06-02 | `16f3d49` | arreglo blue y botones | — |
| 2026-04-27 | `eb63e85` | muy funcional, falta layout de pantalla de estado | — |
| 2026-04-25 | `4797b0a` | arreglo server | — |
| 2026-04-23 | `c99cd77` | new upload | — |
| 2026-04-23 | `60448a3` | first commit | — |

### AeriaNexusPrototype

| Fecha | Commit | Descripción | Horas |
|---|---|---|---|
| 2026-07-22 | `74cba54` | quitado screenrecorder bloqueo | — |
| 2026-07-20 | `327f4a6` | las versiones debug aumentan automáticamente | — |
| 2026-07-20 | `82adb88` | guardado local de incidencias | — |
| 2026-07-15 | `21c325e` | screenshot bloqueado, tooltip SOS, topbar no clickable, icono de mapa en SOS | — |
| 2026-07-12 | `46c1f9e` | camara video bodycam | — |
| 2026-07-12 | `a772f1c` | foto controlador desde el móvil SOS, tooltip | — |
| 2026-07-10 | `6bfbd55` | Initial commit | — |

**Días naturales con actividad en git:** 13 únicos (BC: 10 · AN: 5, con 2026-07-10 y 2026-07-12 compartidos)
