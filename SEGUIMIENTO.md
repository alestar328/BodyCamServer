# Seguimiento de horas y desarrollo — Proyecto BodyCam / AeriaNexus

Registro único de horas y entregas para las **dos aplicaciones del proyecto**.

| App | Rol | Ruta local | Repo |
|---|---|---|---|
| **BodyCamServer** | Servidor / dispositivo bodycam (Android) | `C:\Users\newge\Desktop\Variedades\BodyCam\BodyCamServer` | [alestar328/BodyCamServer](https://github.com/alestar328/BodyCamServer) |
| **AeriaNexusPrototype** | App cliente / control (Android) | `C:\Users\newge\AndroidStudioProjects\AeriaNexusPrototype` | [alestar328/AeriaNexusPrototype](https://github.com/alestar328/AeriaNexusPrototype) |

**Inicio del proyecto:** 2026-04-23 (primer commit de BodyCamServer)
**Último pago recibido:** 2026-07-24
**Tarifa:** 20 €/h
**Última actualización de este archivo:** 2026-09-08 18:10
**Versión BodyCamServer:** `1.3` (versionCode 4) en código, firmada. La unidad corre el
binario del cifrado y la subida por bloques, validado en hardware; el PTT del 08-sep está
probado en la unidad pero **sin commitear**.
**Versión AeriaNexusPrototype:** `1.6` (versionCode 7), con todo lo del 25-ago en adelante
**sin commitear**.

---

## 0. Trazabilidad desde el último pago (2026-07-24 → 2026-09-08, 46 días)

El desglose es **por aplicación**, y dentro de cada aplicación por bloques de trabajo.
Cada bloque lleva un identificador con el prefijo de su app (`BC-n` para BodyCamServer,
`AN-n` para AeriaNexusPrototype) para que las horas se sumen al total del proyecto sin
perder de vista a qué app pertenecen.

---

## 0.A · Aplicación **BodyCamServer**

> Servidor / dispositivo bodycam · repo `alestar328/BodyCamServer`
> **7 bloques · 9 días de actividad**

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
| `BC-1` Grabación continua / EvidenceStore + "Subir a servidor" + entrega y firma de release | 2026-08-09, 08-16, 08-23, 08-27 | ✅ Integrado, al estándar bodycam (vídeo único con rótulo), validado en la unidad · v1.3 firmada | ~1027 añadidas / ~438 borradas · 12 archivos + merge + ~880 líneas del 23-ago | 13.0 | 260 € |
| `BC-2` Cifrado (medición + determinación) | 2026-08-14, 08-15 | ⚠️ Sin commitear (`develop`) | ~2803 añadidas · 7 archivos | 9.0 | 180 € |
| `BC-3` Pantalla en reposo + cronómetro | 2026-08-15 | ✅ En `develop` | ~90 añadidas · 1 archivo | 1.5 | 30 € |
| `BC-4` Panel de control (UX + Compose) | 2026-08-23 | ✅ En `develop` | ~978 añadidas / ~329 borradas · 16 archivos | 2.0 | 40 € |
| Análisis de ciberseguridad + administración | 2026-08-15, 08-23 | ✅ En `develop` | — | 0.5 | 10 € |
| Reuniones de equipo (semana 17–21 ago) | 2026-08-16 | — | — | 3.0 | 60 € |
| `BC-5` Cifrado y hash de la evidencia (implementación) | 2026-08-25 | ✅ En `develop` (`81f0995`), **validado en la unidad** | ~1060 añadidas · 6 archivos | 3.0 | 60 € |
| `BC-6` Subida por bloques reanudable + servidor stub | 2026-08-26 | ⚠️ Sin commitear (`develop`), **validado en la unidad** | ~1521 añadidas · 11 archivos | 4.0 | 80 € |
| `BC-8` PTT por Agora (botón físico F2) | 2026-09-08 | ⚠️ Sin commitear (`develop`), **validado en la unidad** | 5 archivos | 2.8 | 56 € |
| **Subtotal app** | | **9 días de actividad** | **~8381 añadidas · 62 archivos** | **38.8** | **776 €** |

> El reparto de horas de esta tabla es el del Excel de seguimiento, que es el registro
> autoritativo. `BC-7` **no se usa aquí a propósito**: ese identificador está pedido por dos
> trabajos distintos en las notas del proyecto (firma de release del 27-ago y emparejamiento
> con la bodycam del 6-sep), así que el PTT abre `BC-8` en vez de añadir un tercer significado.
> Conviene cerrar esa colisión antes de facturar por bloques.

---

## 0.B · Aplicación **AeriaNexusPrototype**

> App cliente / control · repo `alestar328/AeriaNexusPrototype`
> **2 bloques · 6 días de actividad**

El último commit del repo sigue siendo antiguo, pero desde el **2026-08-25** esta app tiene
trabajo continuado: cifrado de evidencia portado desde BodyCamServer, subida por bloques,
firma de release, bóveda con contraseña, notas de audio y —el **2026-09-08**— el PTT en sus
dos sentidos. **Nada de esto está commiteado todavía.**

### Subtotal AeriaNexusPrototype

| Bloque | Fecha | Estado | Volumen real de código | Horas | Importe |
|---|---|---|---|---|---|
| `AN-1` Cifrado, subida por bloques, firma de release, bóveda y notas de audio | 2026-08-25 → 08-30 | ⚠️ Sin commitear · **validado en teléfono** (Redmi Note 8 Pro, Android 11) | ~821 añadidas / ~46 borradas · 7 archivos, más bóveda y subida | 14.0 | 280 € |
| `AN-4` PTT: recepción del de la bodycam + PTT propio del teléfono | 2026-09-08 | ⚠️ Sin commitear · recepción verificada en hardware, **PTT propio sin probar en aparatos** | 6 archivos | 2.0 | 40 € |
| **Subtotal app** | | **6 días de actividad** | | **16.0** | **320 €** |

---

## 0.C · Total del proyecto desde el pago

| Aplicación | Bloques | Días de actividad | Código añadido | Horas | Importe |
|---|---|---|---|---|---|
| BodyCamServer | `BC-1`, `BC-2`, `BC-3`, `BC-4`, `BC-5`, `BC-6`, `BC-8` | 9 | ~8381 líneas · 62 archivos | 38.8 | 776 € |
| AeriaNexusPrototype | `AN-1`, `AN-4` | 6 | ~821 líneas · 13 archivos | 16.0 | 320 € |
| **TOTAL PROYECTO** | **9** | **11** | **~9202 líneas · 75 archivos** | **54.8** | **1096 €** |

**Días con actividad desde el pago: 11** (2026-08-09, 08-14, 08-15, 08-16, 08-23, 08-25,
08-26, 08-27, 08-29, 08-30 y 09-08). El 2026-08-25 fue el primer día desde el pago con
trabajo en **las dos** aplicaciones; el 09-08 es el más reciente.

**Sobre las horas:** las del **09 y 14 de agosto** se reconstruyeron a posteriori (6,0 h cada
una) a partir de las marcas de tiempo de los archivos y del volumen de los commits — son
estimaciones documentadas, no un cronómetro. Las tres sesiones del **27 de agosto** también
van marcadas como estimadas. Las del **8 de septiembre** salen de marcas de fichero
(12:33–16:39 y 17:40–18:10), con el reparto entre las dos apps estimado sobre un total medido;
el hueco 16:39–17:40 de ese día **no está contado**.

⚠️ **Este total no incluye el 3, 4, 6 y 7 de septiembre**, que nunca se anotaron: son
**~10,4 h medidas más dos días sin medir**. Ver el aviso de §3. Por eso **54,8 h es un
suelo**, no la cifra real pendiente de facturar.

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
| Rama principal | **`develop`** (desde 2026-08-23) | `main` |
| Último commit | `c65bd1c` — 2026-09-06 | `8b7111d` — 2026-09-07 |
| Versión | **1.3** (versionCode 4), firmada | **1.6** (versionCode 7) |
| Relación con `main` | `develop` ⊇ `main` (merge `06008b2`) | — |
| Trabajo sin commitear | **Sí** — el PTT del 08-sep (5 archivos) | **Sí** — PTT (6 archivos), bóveda, subida por bloques y cifrado |

**Última entrega (push a `main`):** 2026-08-09 → `d4a4709` "avance grabacion continua"
**Estado funcional:** grabación continua con pre-roll de 20 s **validada en la unidad
física** el 2026-08-23, evidencia **cifrada y hasheada** desde el 2026-08-25 (formato FEVD v1,
`docs/CRYPTO-FORMAT.md`) y **subida por bloques reanudable** desde el 2026-08-26, todo
validado en hardware. Desde el **2026-09-08** el **PTT** funciona en los dos sentidos: la voz
de la bodycam (botón F2) llega a todos los teléfonos por Agora, y el teléfono tiene su propio
botón de mantener-para-hablar — este último **compila pero no se ha probado en aparatos**.

> ✅ La divergencia `develop`/`main` del aviso anterior quedó **cerrada** con el merge
> `06008b2` (2026-08-23). `develop` es la rama principal; `main` queda como rama de
> entregas.

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
| 2026-08-15 | BC | BC-3 | 1.5 | Pantalla en reposo al grabar + cronómetro en `RecordingActivity`. Compilado y desplegado en la unidad | _(sin commitear)_ | No | Registrado |
| 2026-08-15 | BC | BC-2 | 3.0 | Cifrado (medición): `CryptoBenchmark` (SHA-256 + AES-256-GCM por bloques) — **continuación** | _(sin commitear)_ | No | Marcas de tiempo 15:47–22:56 |
| 2026-08-16 | BC | BC-1 | 2.0 | Funcionalidad "Subir a servidor ¿sí/no?", visualización del tiempo de grabación | _(sin commitear)_ | No | Registrado |
| 2026-08-16 | BC | — | 3.0 | Reuniones de equipo, semana del 17 al 21 de agosto | — | No | Registrado |
| 2026-08-23 | BC | — | 0.5 | Análisis del *Security Feature List* (34 features: 15 viables sin backend, 12 parciales, 7 bloqueadas), ruta de desarrollo hasta fin de septiembre y actualización del seguimiento | `db04e55` | No | Marcas de tiempo 15:52–16:16 |
| 2026-08-23 | BC | BC-1 | 1.0 | Grabación continua integrada en `develop` (merge de `main`): anillo + incidentes + manifest sobre la UI Compose. Requisitos de producto: buffer automático al abrir, pre-roll de 20 s, LED azul en servicio (LedSignals centralizado). Depuración en la unidad: el parche de armado no había llegado al disco; verificado ciclo completo grabar→manifest→rearme en hardware | `06008b2`, `56d5478`, `99b42a6`, `f2e7e52` | No | Marcas de tiempo 18:04–19:06 |
| 2026-08-23 | BC | BC-4 | 2.0 | Panel de control: 3 bugs de la pregunta de envío (`finish()` en paradas redundantes, rebote de F2, arranque de grabación con la pregunta abierta), rediseño para pantalla de 3 cm con iconos vectoriales, migración de las dos pantallas a Compose con 8 previews, fullscreen real (tema, inmersivo, insets del decor) | `db04e55`, `a599da9` | No | Marcas de tiempo 16:16–18:04 |
| 2026-08-23 | BC | BC-1 | 2.0 | Evidencia al estándar bodycam, todo validado en la unidad: identidad del oficial en overlay + manifest (`Officer.kt`, hardcodeado con TODO), un solo MP4 por incidente (`IncidentAssembler`, remux sin recodificar), rótulo del oficial quemado en los frames (`VideoStamper`, decode→GL→encode por hardware; verificado que el firmware no trae watermark), nombres `placa_fecha_hora`, numeración secuencial `INC_000001` con contador auto-reparable, `.nomedia` en el anillo | `671cab7`, `48892dd`, `b00332f`, `4373116`, `a25c0c7` | No | Marcas de tiempo 19:06–21:05 |
| 2026-08-25 | BC | BC-5 | 3.0 | Cifrado y hash de la evidencia, **implementación**: formato FEVD v1 documentado como contrato de las dos apps (`docs/CRYPTO-FORMAT.md`), `EvidenceCrypto` (AES-256-GCM en bloques de 1 MB con nonce y AAD derivados del índice, más SHA-256 en la misma pasada), `EvidenceKeys` (DEK por fichero + envoltorios), hashes persistidos en el manifest y descifrador Python de referencia. Validado en la unidad: 20,3 MB en 397 ms, descifrado idéntico byte a byte | `81f0995` | No | Reparto por app decidido el 26-ago |
| 2026-08-25 | AN | AN-1 | 3.0 | Cifrado y hash de la evidencia, **port al teléfono**: `EvidenceCrypto` y `EvidenceKeys` idénticos a los de la bodycam salvo el alias de Keystore, `.fev` fuera de la galería y SHA-256 real en lugar de los hashes simulados. Validado en el Redmi Note 8 Pro: 21,6 MB en 303 ms, mismo descifrador. Retirados los 3 incidentes de ejemplo | _(sin commitear)_ | No | Reparto por app decidido el 26-ago |
| 2026-08-26 | BC | BC-6 | 4.0 | Subida por bloques reanudable: cliente tus 1.0.0 (4 operaciones, backoff, reanudación por offset del servidor), sesión persistida, config por fichero, servidor stub con corte inyectable y contrato para backend. Clave `srv:` de desarrollo activada — **la evidencia deja de subirse en claro**. Validado en la unidad: 9 incidentes, 61,9 MB, corte inyectado, corte real de WiFi y reanudación tras reinicio | _(sin commitear)_ | No | Marcas de tiempo 13:29–15:56 |
| 2026-08-26 | AN | AN-1 | 3.0 | Destinatario `srv:` del teléfono alineado con el de la unidad y validado en hardware: cifra para los dos, el servidor lo abre y el hash de Room es el real | _(sin commitear)_ | No | Marcas de tiempo 13:29–15:56 |
| 2026-08-26 | AN | AN-1 | 2.0 | Subida por bloques portada al teléfono: `data/upload/` completo, enganche en foto, vídeo y audio, reconciliación del estado de sincronización. Validado con un vídeo de 68 MB con corte y reanudación, más dos ficheros retomados solos al arrancar | _(sin commitear)_ | No | Marcas de tiempo 20:50–22:01 |
| 2026-08-27 | BC | BC-1 | 1.0 | Inventario de entrega a backend: revisión del contrato de subida, formato de cifrado y manifest. Detectados 4 huecos (esquema del `manifest.json` sin documentar, falta juego de muestra, endpoint HTTPS + CA, rechazo de `kid dev-*`) | — | No | **Estimado** |
| 2026-08-27 | BC | BC-1 | 1.0 | Firma de release: keystore único de proyecto (PKCS12, RSA 4096), `signingConfig` vía `local.properties`, versión 1.2→1.3 (code 4), APK firmado y verificado (v2) | _(sin commitear)_ | No | **Estimado** |
| 2026-08-27 | AN | AN-1 | 1.0 | Firma de release con el mismo keystore, versión 1.0→1.1 (code 2), APK firmado y verificado. R8 con minify | _(sin commitear)_ | No | **Estimado** |
| 2026-08-29 | AN | AN-1 | 3.0 | Bóveda con contraseña: `EvidenceVault`, RSA + PBKDF2 + Keystore, destinatarios de clave. Captura fuera de la galería: `LocalEvidenceRepository` sin MediaStore y borrado del claro. Pantalla Evidence Vault, `VaultRepository` y entrada desde Profile | _(sin commitear)_ | No | Del Excel |
| 2026-08-30 | AN | AN-1 | 2.0 | Reproductor de audio dentro de la app y nota de audio enlazada al cerrar el incidente. Clasificación de la nota + cierre encadenado; pruebas con adb | _(sin commitear)_ | No | Del Excel |
| 2026-09-08 | BC | BC-8 | 2.8 | **PTT por Agora en la bodycam.** Botón F2 medido por logcat: el broadcast solo llega al **soltar**, el firmware inyecta `KEYCODE_BACK` al segundo de mantenerlo y auto-repite con la Activity en foco — por eso es conmutador y no mantener-para-hablar. Una sola puerta en `BtServerService`, sesión **solo audio** que no toca la cámara ni dispara SOS, cesión del micrófono al anillo (conflicto de captura medido: warning 1033 / error 1165) y watchdog de captura para que nunca diga ON transmitiendo silencio. Contrato nuevo con el móvil: `BTN_PTT_ON`/`BTN_PTT_OFF` y campo `"ptt"` en `STATUS`. Validado en la unidad | _(sin commitear)_ | No | Marcas de fichero 12:33–16:39 · reparto por app estimado |
| 2026-09-08 | AN | AN-4 | 1.5 | **Recepción del PTT en el teléfono:** excepción de escucha al uid 9001 (con `autoSubscribeAudio = false` la bodycam publicaba y no la oía nadie), estado por `onRemoteAudioStateChanged` tratando FROZEN como voz viva, y banda de aviso `PttAvisoOverlay` por encima de la navegación pero por debajo del SOS. Verificado con la W1 y el Redmi a la vez, sin SOS fantasma | _(sin commitear)_ | No | Marcas de fichero 12:33–16:53 · reparto por app estimado |
| 2026-09-08 | AN | AN-4 | 0.5 | **PTT propio del teléfono:** botón mantener-para-hablar en Operations (descomentado y conectado), publicación del micrófono en caliente y anuncio `ptt_on`/`ptt_off` por el data stream — sin él la voz sale y no la oye nadie, porque el uid del teléfono es aleatorio y no se puede cablear como el 9001. Compila; **sin probar en aparatos**. `DEVLOG.md` y `docs/bodycam-contexto.md` actualizados | _(sin commitear)_ | No | Marcas de fichero 17:40–18:10 |
|  |  | **TOTAL** | **54.8** |  |  |  | **1096 €** |


> ### ⚠️ Días de septiembre SIN REGISTRAR — no facturar desde este archivo sin cerrarlos
>
> Entre el 30-ago y el 8-sep hubo trabajo que **no está en esta tabla ni en el Excel**, porque
> nunca se anotaron sus horas. Lo que se sabe de cada día:
>
> | Fecha | Trabajo | Horas |
> |---|---|---|
> | 2026-09-03 | Sistema de autenticación IAM | **4.0 estimadas** — sin confirmar |
> | 2026-09-04 | Workflow 12 (alta del teléfono) + adelantos de UX | ~2.0 de reloj (10:25–12:31) |
> | 2026-09-06 | Hito del 15 completo: workflows 3, 4, 27, 13, 31, 33 y 34 | **4.4 de reloj** |
> | 2026-09-07 | Gafas BleeqUp: enlace BT y análisis de cifrado del vídeo | sin anotar |
>
> Son **~10,4 h medidas más el 7-sep y la confirmación del 3-sep**. Están descritas en el
> `DEVLOG.md` de AeriaNexusPrototype y en la memoria `horas-septiembre-2026`, pero no
> imputadas. Hasta cerrarlas, el total de **54,8 h** de arriba es un **suelo**, no la cifra real.
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

**BodyCamServer commiteado en `develop`** (queda suelto solo el salto de versión a 1.2).
**AeriaNexusPrototype tiene el cifrado sin commitear.**

| Commit | Hora | Contenido |
|---|---|---|
| `db04e55` | 16:20 | Arreglos de la pregunta de envío, documentos de cifrado, seguimiento |
| `a599da9` | 18:02 | Panel de control rediseñado, migración a Compose, fullscreen |
| `06008b2` | 18:23 | **Merge de `main`**: grabación continua (anillo + incidentes + manifest) sobre la UI de `develop` |
| `56d5478` | 18:40 | Buffer automático al abrir, pre-roll 20 s, LED azul (parcial: ver `f2e7e52`) |
| `2ecc7d5` | 18:43 | Seguimiento (usuario) |
| `99b42a6` | 18:48 | LED por estado real (`LedSignals`) |
| `f2e7e52` | 19:09 | Armado al abrir — la pieza de `56d5478` que no llegó al disco |
| `16c498a` | 20:07 | Arreglos y seguimiento (usuario) |
| `671cab7` | 20:10 | Identidad del oficial en overlay y manifest (`Officer.kt`, TODO datos reales) |
| `48892dd` | 20:27 | Evidencia con nombre `placa_fecha_hora` al promoverse |
| `b00332f` | 20:37 | Un incidente = un único MP4 (`IncidentAssembler`, remux sin recodificar) |
| `4373116` | 20:50 | Rótulo del oficial quemado en los frames (`VideoStamper`) + `.nomedia` en el anillo |
| `a25c0c7` | 20:59 | Numeración secuencial `INC_000001` con contador auto-reparable |
| `81f0995` | **25-ago** 15:03 | **Cifrado y hash de la evidencia** (`EvidenceCrypto`, `EvidenceKeys`, formato FEVD v1, descifrador Python) |

### ✅ La divergencia con `main` está cerrada

El merge `06008b2` integró `d4a4709` en `develop`. **`develop` es la rama principal**
desde el 2026-08-23: contiene todo `main` más la UI nueva. `CameraController.kt` y
`RecorderWatch.kt` quedaron eliminados (sin llamantes).

**Validado en la unidad física** (sesión del 23-ago por la tarde-noche):
ciclo completo grabar → incidente → rearme ✓ · pre-roll completo tras buffer
largo (22,9 s y 23,5 s medidos en manifest) ✓ · vídeo único ensamblado ✓ ·
rótulo del oficial en los píxeles (frame extraído del MP4 real) ✓ · secuencia
de incidentes continua a través de un reinicio forzado ✓.

**Pendiente de validar:** sesión larga de rotación (>10 min en buffer), LED
azul confirmado visualmente, reproducción del vídeo ensamblado (fluidez en las
costuras y sincronía de audio, a ojo y oído).

### 🔐 Cifrado de evidencia — validado en la unidad el 2026-08-25

Incidente de prueba `INC_000005`, 20,3 MB de MP4:

| Comprobación | Resultado |
|---|---|
| Cifrado + hash en la unidad | **397 ms** (una sola pasada de lectura) |
| Sobrecoste de tamaño | 727 B sobre 20,3 MB = **0,0034 %** |
| Descifrado en PC con el Python de referencia | ✓ hash idéntico al del manifest |
| Comparación con el MP4 original | ✓ **idénticos byte a byte** |
| Descifrado en la propia unidad (Keystore) | ✓ 315 ms, mismo SHA-256 |
| Rechazo de manipulación (contenido, cabecera, truncado) | ✓ los tres |

La clave de Keystore **sobrevive a `adb install -r`**: se pierde al desinstalar, no al
actualizar. La interoperabilidad se probó con un par RSA de usar y tirar inyectado
temporalmente en `NexusKeyWrapper` y **revertido después** — con solo el destinatario
`ks:` el descifrador de PC no puede abrir nada, que es justo el diseño.

**Superado el 2026-08-26.** El compañero de backend pidió no bloquearse por lo que falta
(*"implement stub methods instead of real api calls and real keys"*), así que el
destinatario `srv:` se activó con un par RSA-2048 de desarrollo (`kid = dev-2026-08`,
privada en `tools/dev-keys/`, fuera de git) y **la evidencia ya sale cifrada de la unidad**.
Sigue bloqueada la decisión de fondo —la custodia de la pública real de Nexus, decisión 1
del plan semanal— pero ya no bloquea el desarrollo. El prefijo `dev-` del `kid` es la
salvaguarda: lo cifrado para un `dev-*` es material de pruebas por definición.

### 📤 Subida por bloques reanudable — validada en la unidad el 2026-08-26

Protocolo tus 1.0.0 (core + Creation) más una extensión propia de verificación, contra el
servidor stub de `tools/tus_stub_server.py`. Contrato para backend en
`docs/UPLOAD-PROTOCOL.md`.

| Comprobación | Resultado |
|---|---|
| Incidentes subidos y verificados por hash | **9 · 61,9 MB · 0 discrepancias** |
| Sube el `.fev` cifrado, no el MP4 | ✓ cabecera `FEVD` detectada en el servidor |
| Corte inyectado a mitad de bloque | ✓ reanuda desde el byte 1310720 del servidor, no desde su propia cuenta (1048576) |
| Corte real de WiFi | ✓ backoff 1/2/4 s, recupera sola al 4.º intento |
| Reanudación tras **reinicio de la unidad** | ✓ continúa desde el byte 1572864 exacto |
| Descifrado en PC de lo que recibió el servidor | ✓ SHA-256 idéntico al `sha256_plain` del manifest |

La reanudación tras un corte a mitad de bloque es el caso que importa: el servidor conserva
más bytes de los que el cliente vio confirmados, así que **su offset es la única verdad**.
Reanudar por la cuenta del cliente habría dejado un hueco y el fichero no descifraría.

**Hallazgo de hardware, previo a este trabajo:** en esta unidad `BootReceiver` **nunca se
dispara** — `BOOT_COMPLETED` entra en la cola de background del `PowerController.Guru` del
firmware Unisoc y no llega al receptor. Permisos concedidos, paquete no *stopped*, sin
crash: es bloqueo de autostart del fabricante. Por eso la reanudación se dispara también
desde `MainActivity.onCreate`. **Implicación fuera de este bloque: el servidor Bluetooth
tampoco arranca solo al encender la unidad**, que es lo que ese receptor dice hacer desde
siempre. Sin verificar por separado.

### 🔓 Cifrado — pendientes conocidos

Ordenados por quién los desbloquea:

| # | Pendiente | Depende de |
|---|---|---|
| 1 | ~~El teléfono no tenía destinatario `srv:`~~ | ✅ **resuelto el 26-ago** |
| 2 | Nadie llama a `EvidenceCrypto.open()`: no hay reproducción del `.fev` en la app. Hoy no se nota porque el claro se conserva; al activar EVD-007 la galería se queda sin nada que enseñar | Nosotros |
| 3 | `FileServerService` sirve el MP4 **en claro** al teléfono por la LAN. Ciframos para subir a Nexus y el mismo vídeo viaja sin cifrar entre las dos apps | Nosotros, previa decisión de diseño |
| 4 | Clave pública real de Nexus (SPKI + `kid`) | Ciberseguridad (política) + backend (ejecución) |
| 5 | EVD-007 retención local: `DELETE_PLAINTEXT` sigue en `false` | Manager |
| 6 | CRY-001 pide cifrar "durante la captura"; ciframos al cerrar, deliberadamente | Negociar con ciberseguridad |
| 7 | **EVD-004 firma digital: cero líneas.** El hash prueba integridad, no autoría — cualquiera puede fabricar un `.fev` con su hash correcto. Es el hueco más grande del bloque | Nosotros, sin planificar |
| 8 | *Security Feature List* con las 35 filas en `Planned` | Nosotros (tarea 6 del plan semanal) |

### ⚠️ AeriaNexusPrototype — sin commitear

7 archivos con el port del cifrado, **ya validados en teléfono** (Redmi Note 8 Pro,
Android 11) el 25-ago, más el destinatario `srv:` alineado con la unidad el 26-ago. Siguen
sin commitear. Quedan tres cosas anotadas y no resueltas a propósito: en el móvil el vídeo
se lee dos veces (MediaStore no da un `File`), `DELETE_PLAINTEXT` no borraría el original
sino la copia de caché, y solo se persiste uno de los dos hashes porque ampliar
`EvidenceRecord` exige migración de Room.

**Además, la subida por bloques está solo en la bodycam.** El móvil sigue sin subir nada:
el enganche es después de `sealAndPublish` en `LocalEvidenceRepository`, y el estado cabe
en el `SyncState` que `EvidenceEntity` ya tiene.

---

## 6. Totales

Las horas se llevan **separadas por aplicación** y el total del proyecto es su suma.

**Tarifa aplicada: 20 €/h.**

### Desde el último pago (2026-07-24)

| Aplicación | Horas | Importe | Nota |
|---|---|---|---|
| BodyCamServer | **38.8** | **776 €** | 09 y 14-ago reconstruidos, 27-ago estimado; el resto registrado — ver §0.A |
| AeriaNexusPrototype | **16.0** | **320 €** | Del 25-ago al 30-ago y el 08-sep — ver §0.B |
| **TOTAL** | **54.8** | **1096 €** | Pendiente de facturar · **suelo**: faltan el 3, 4, 6 y 7 de septiembre |

### Desde la última entrega (2026-08-09)

| Aplicación | Horas | Importe | Nota |
|---|---|---|---|
| BodyCamServer | **32.8** | **656 €** | 14, 15, 16, 23, 25, 26 y 27-ago y 08-sep (el 09-ago está dentro de la entrega `d4a4709`) |
| AeriaNexusPrototype | **16.0** | **320 €** | 25, 26, 27, 29 y 30-ago y 08-sep |
| **TOTAL** | **48.8** | **976 €** | |

### Acumulado del proyecto

| Aplicación | Horas registradas | Importe | Horas reales |
|---|---|---|---|
| BodyCamServer | **38.8** | **776 €** | _mayor — el histórico previo al 09-ago no se registró, ver §7_ |
| AeriaNexusPrototype | **16.0** | **320 €** | _mayor — ver §7_ |
| **TOTAL PROYECTO** | **54.8** | **1096 €** | _mayor que lo registrado_ |

### Objetivo de facturación — cierre de septiembre 2026

| Concepto | Horas | Importe |
|---|---|---|
| Registrado a 2026-09-08 | 54.8 | 1096 € |
| Objetivo mínimo | 100.0 | **2000 €** |
| **Pendiente de generar** | **45.2** | **904 €** |

Con 30 h/semana comprometidas, las **45,2 h** restantes se cubren en **~1,5 semanas**: el
umbral de los 2000 € se cruza alrededor del **viernes 18 de septiembre de 2026**. Y esa fecha
es **conservadora**: las horas del 3, 4, 6 y 7 de septiembre (~10,4 h medidas más dos días sin
medir) todavía no están imputadas, así que en cuanto se cierren el umbral se adelanta.

---

## 7. Histórico de commits (reconstruido de git, horas no registradas)

### BodyCamServer

| Fecha | Commit | Descripción | Horas |
|---|---|---|---|
| 2026-08-23 | `a25c0c7` | numeración secuencial INC_000001 con contador auto-reparable | 0.3 |
| 2026-08-23 | `4373116` | rótulo del oficial quemado en los frames + .nomedia en el anillo | 0.7 |
| 2026-08-23 | `b00332f` | un incidente = un único MP4, ensamblado sin recodificar | 0.5 |
| 2026-08-23 | `48892dd` | evidencia con nombre placa_fecha_hora al promoverse | 0.2 |
| 2026-08-23 | `671cab7` | identidad del oficial en overlay y manifest | 0.3 |
| 2026-08-23 | `16c498a` | arreglos (usuario) | — |
| 2026-08-23 | `f2e7e52` | armar al abrir la app — la pieza de 56d5478 que no llegó al disco | 0.2 |
| 2026-08-23 | `99b42a6` | LED por estado real: el azul de buffer ya no se pisa con verde | 0.2 |
| 2026-08-23 | `2ecc7d5` | pre buffer integrado, led azul y armar al abrir la app (seguimiento) | — |
| 2026-08-23 | `56d5478` | buffer automático al abrir, pre-roll de 20 s y LED azul en servicio | 0.2 |
| 2026-08-23 | `06008b2` | merge main: grabación continua con pre-roll sobre la UI nueva de develop | 0.4 |
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
