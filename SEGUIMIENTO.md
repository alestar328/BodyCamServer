# Seguimiento de horas y desarrollo — Proyecto BodyCam / AeriaNexus

Registro único de horas y entregas para las **dos aplicaciones del proyecto**.

| App | Rol | Ruta local | Repo |
|---|---|---|---|
| **BodyCamServer** | Servidor / dispositivo bodycam (Android) | `C:\Users\newge\Desktop\Variedades\BodyCam\BodyCamServer` | [alestar328/BodyCamServer](https://github.com/alestar328/BodyCamServer) |
| **AeriaNexusPrototype** | App cliente / control (Android) | `C:\Users\newge\AndroidStudioProjects\AeriaNexusPrototype` | [alestar328/AeriaNexusPrototype](https://github.com/alestar328/AeriaNexusPrototype) |

**Inicio del proyecto:** 2026-04-23 (primer commit de BodyCamServer)
**Último pago recibido:** 2026-07-24
**Tarifa:** 20 €/h
**Última actualización de este archivo:** 2026-08-23 18:10

---

## 0. Trazabilidad desde el último pago (2026-07-24 → 2026-08-23, 30 días)

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

### Bloque `BC-3` — Pantalla en reposo + cronómetro · 2026-08-15 · ⚠️ sin commitear en `develop`
> `RecordingActivity.kt` — al empezar a grabar la pantalla pasa a reposo; un toque alterna
> reposo ↔ preview y muestra el tiempo transcurrido.

Reposo = brillo a 0 más capa opaca, no dormir el panel: Android no lo permite sin permisos de
administrador de dispositivo, y por esa vía el despertar pasaría por la pantalla de bloqueo.
El `TextureView` se deja visible bajo la capa para no dejar sin frames al monitor remoto.
Contador sobre `elapsedRealtime()`, inmune a que la unidad sincronice la hora a mitad de
grabación. Compilado y desplegado en la unidad.

### Subtotal BodyCamServer

| Bloque | Fecha | Estado | Volumen real de código | Horas | Importe |
|---|---|---|---|---|---|
| `BC-1` Grabación continua / EvidenceStore + "Subir a servidor" | 2026-08-09, 08-16 | ✅ 08-09 entregado (`main`) · ⚠️ 08-16 sin commitear | ~1027 añadidas / ~438 borradas · 12 archivos | 8.0 | 160 € |
| `BC-2` Cifrado (medición + determinación) | 2026-08-14, 08-15 | ⚠️ Sin commitear (`develop`) | ~2803 añadidas · 7 archivos | 9.0 | 180 € |
| `BC-3` Pantalla en reposo + cronómetro | 2026-08-15 | ✅ En `develop` | ~90 añadidas · 1 archivo | 0.0 ¹ | 0 € |
| `BC-4` Panel de control (UX + Compose) | 2026-08-23 | ✅ En `develop` | ~978 añadidas / ~329 borradas · 16 archivos | 1.8 | 36 € |
| Análisis de ciberseguridad + administración | 2026-08-15, 08-23 | ✅ En `develop` | — | 0.4 | 8 € |
| Reuniones de equipo (semana 17–21 ago) | 2026-08-16 | — | — | 2.0 | 40 € |
| **Subtotal app** | | **5 días de actividad** | **~4900 añadidas · 36 archivos** | **21.2** | **424 €** |

> ¹ `BC-3` queda a 0,0 h porque sus horas están imputadas dentro de la sesión del **2026-08-16**
> (bloque `BC-1`, "visualización de tiempo de grabación"). El bloque se mantiene en la tabla para
> no perder el rastro del trabajo, no porque no se hiciera.

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

| Aplicación | Bloques | Días de actividad | Código añadido | Horas | Importe |
|---|---|---|---|---|---|
| BodyCamServer | `BC-1`, `BC-2`, `BC-3`, `BC-4` | 5 | ~4900 líneas · 36 archivos | 21.2 | 424 € |
| AeriaNexusPrototype | — | 0 | 0 | 0.0 | 0 € |
| **TOTAL PROYECTO** | **4** | **5** | **~4900 líneas · 36 archivos** | **21.2** | **424 €** |

**Días con actividad desde el pago: 5** (2026-08-09, 08-14, 08-15, 08-16 y 08-23), todos en BodyCamServer.

**Sobre las horas:** las del **09 y 14 de agosto** se han reconstruido a posteriori (6,0 h cada
una) a partir de las marcas de tiempo de los archivos y del volumen de los commits — son
estimaciones documentadas, no un cronómetro. Las del **15 y 16 de agosto** están registradas.
Total pendiente de facturar: **21,2 h = 424 €**.

### Desglose del 2026-08-15 (reloj del PC)

| Hora | Hito | Evidencia |
|---|---|---|
| ~12:45 | Inicio de sesión | Primeras consultas al historial |
| 12:56 | `SEGUIMIENTO.md` creado | mtime |
| 13:02 | `SEGUIMIENTO.xlsx` generado | mtime |
| 13:09 | Análisis de `RecordingActivity` de `main` | Copia en scratchpad |
| 13:39 | Feature `BC-3` compilada · APK instalado en la unidad | mtime + `lastUpdateTime` del paquete |
| 13:55–14:16 | Grabación de prueba de 20 min en la unidad | `VID_20260815_195544.mp4` |
| 14:32 | Informe de cifrado publicado | mtime del HTML |
| 15:09 | Cierre del cálculo | — |

**Ventana total: 12:45 → 15:09 ≈ 2 h 24 min → 2,4 h.**

Es la ventana entre el primer y el último rastro de trabajo en este PC, no horas de foco
medidas. Solo cubre lo que dejó huella aquí. Ajusta el número si la sesión tuvo pausas o si
hubo trabajo previo sin rastro.

> Aparte, la unidad estuvo grabando **de 23:12 del 14-ago a 00:51 del 15-ago** (1 h 39 min,
> el fichero de 2,2 GB) — prueba de grabación larga desatendida. Es tiempo de máquina, no de
> trabajo, y por eso no se suma.

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

> **Nota:** las horas del **09 y 14 de agosto** no se cronometraron en su momento; se han
> reconstruido a partir de las marcas de tiempo de los archivos y del volumen de los commits.
> Van marcadas como tal en la columna *Origen del dato*. A partir del 15-ago se registra en tiempo real.

| Fecha | App | Bloque | Horas | Qué se hizo | Commit(s) | Facturado | Origen del dato |
|---|---|---|---|---|---|---|---|
| 2026-08-09 | BC | BC-1 | 6.0 | Grabación continua: `EvidenceStore` (buffer en anillo, pre-roll 120 s, segmentos 8 MB), reescritura de `RecordingActivity`, retirada de `CameraController` | `d4a4709` | No | Reconstruido de git |
| 2026-08-14 | BC | BC-2 | 6.0 | Cifrado (medición): `CryptoBenchmark` (SHA-256 + AES-256-GCM por bloques), `RecorderWatch` (watchdog de grabaciones largas), endpoint `/benchmark`, fix de path traversal, 2 scripts Python, testing con vídeos reales | _(sin commitear)_ | No | Marcas de tiempo 15:47–22:56 |
| 2026-08-15 | BC | — | 0.0 | Alta del sistema de seguimiento de horas (cubre las dos apps) | _(pendiente)_ | No | Registrado |
| 2026-08-15 | BC | BC-2 | 3.0 | Cifrado (medición): `CryptoBenchmark` (SHA-256 + AES-256-GCM por bloques) — **continuación** | _(sin commitear)_ | No | Marcas de tiempo 15:47–22:56 |
| 2026-08-16 | BC | BC-1 | 2.0 | Funcionalidad "Subir a servidor ¿sí/no?", visualización del tiempo de grabación | _(sin commitear)_ | No | Registrado |
| 2026-08-16 | BC | — | 2.0 | Reuniones de equipo, semana del 17 al 21 de agosto | — | No | Registrado |
| 2026-08-23 | BC | — | 0.4 | Análisis del *Security Feature List* (34 features: 15 viables sin backend, 12 parciales, 7 bloqueadas), ruta de desarrollo hasta fin de septiembre y actualización del seguimiento | `db04e55` | No | Marcas de tiempo 15:52–16:16 |
| 2026-08-23 | BC | BC-4 | 1.8 | Panel de control: 3 bugs de la pregunta de envío (`finish()` en paradas redundantes, rebote de F2, arranque de grabación con la pregunta abierta), rediseño para pantalla de 3 cm con iconos vectoriales, migración de las dos pantallas a Compose con 8 previews, fullscreen real (tema, inmersivo, insets del decor) | `db04e55`, `a599da9` | No | Marcas de tiempo 16:16–18:04 |
|  |  | **TOTAL** | **21.2** |  |  |  | **424 €** |
|  |  |  |  |  |  |  |  |

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

## 5. Trabajo en curso

**Árbol limpio en las dos apps a 2026-08-23 18:10.** Todo lo trabajado está commiteado
en `develop` de BodyCamServer:

| Commit | Hora | Contenido | Bloque |
|---|---|---|---|
| `db04e55` | 16:20 | Arreglos de la pregunta de envío, documentos de cifrado y seguridad, seguimiento | BC-2, BC-4 |
| `a599da9` | 18:02 | Panel de control rediseñado, migración a Compose, fullscreen, iconos vectoriales | BC-4 |

### ⚠️ Las ramas han divergido

Ya no es que `develop` esté detrás de `main`: ahora van por caminos distintos.

| | Commits exclusivos |
|---|---|
| Solo en `main` | `d4a4709` (grabación continua / `EvidenceStore`) |
| Solo en `develop` | `420590e`, `db04e55`, `a599da9` |

Sigue en pie el conflicto de §0.A: `d4a4709` **borró** `CameraController.kt`, que es de los
archivos que toca el trabajo de `develop`. Y ahora se suma que `MainActivity.kt` y
`RecordingActivity.kt` están reescritos a fondo en `develop` (Compose) mientras `main` tiene
la versión de Views.

**Cuanto más se tarde en integrar, más caro sale el merge.** Es la tarea que abre la semana 1
del plan y conviene no moverla de ahí.

---

## 6. Totales

Las horas se llevan **separadas por aplicación** y el total del proyecto es su suma.

**Tarifa aplicada: 20 €/h.**

### Desde el último pago (2026-07-24)

| Aplicación | Horas | Importe | Nota |
|---|---|---|---|
| BodyCamServer | **21.2** | **424 €** | 09 y 14-ago reconstruidos; del 15-ago en adelante registrados — ver §0.A |
| AeriaNexusPrototype | **0.0** | 0 € | Sin actividad — ver §0.B |
| **TOTAL** | **21.2** | **424 €** | Pendiente de facturar |

### Desde la última entrega (2026-08-09)

| Aplicación | Horas | Importe | Nota |
|---|---|---|---|
| BodyCamServer | **15.2** | **304 €** | 14, 15, 16 y 23 de agosto (el 09-ago está dentro de la entrega `d4a4709`) |
| AeriaNexusPrototype | **0.0** | 0 € | Sin actividad |
| **TOTAL** | **15.2** | **304 €** | |

### Acumulado del proyecto

| Aplicación | Horas registradas | Importe | Horas reales |
|---|---|---|---|
| BodyCamServer | **21.2** | **424 €** | _mayor — el histórico previo al 09-ago no se registró, ver §7_ |
| AeriaNexusPrototype | **0.0** | 0 € | _desconocido — ver §7_ |
| **TOTAL PROYECTO** | **21.2** | **424 €** | _mayor que lo registrado_ |

### Objetivo de facturación — cierre de septiembre 2026

| Concepto | Horas | Importe |
|---|---|---|
| Registrado a 2026-08-23 | 21.2 | 424 € |
| Objetivo mínimo | 100.0 | **2000 €** |
| **Pendiente de generar** | **78.8** | **1576 €** |

Con 30 h/semana comprometidas, las 81 h restantes se cubren en **2,7 semanas**: el umbral de
los 2000 € se cruza alrededor del **jueves 10 de septiembre de 2026**. La ventana completa
(24-ago → 30-sep = 5 semanas + 3 días) da capacidad para **168 h**, es decir hasta 187 h
acumuladas = **3740 €** si se llena por completo.

---

## 7. Histórico de commits (reconstruido de git, horas no registradas)

### BodyCamServer

| Fecha | Commit | Descripción | Horas |
|---|---|---|---|
| 2026-08-23 | `a599da9` | botones en pantalla quitados, prebuffer mejorado, ux modo grabacion y enviar a servidor mejorado | 1.8 |
| 2026-08-23 | `db04e55` | botones de subir a server | 0.4 |
| 2026-08-15 | `420590e` | no segmentacion de video, pantalla cerrada al grabar, cronometro | 0.7 |
| 2026-08-09 | `d4a4709` | avance grabacion continua | 6.0 |
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
