# DEVLOG — BodyCamServer (unidad bodycam)

Bitácora de desarrollo de la app de la unidad. Se actualiza cada día de trabajo.
Regla: cada entrada nueva va ARRIBA, con fecha, qué se hizo, decisiones tomadas y
próximo paso. Es la gemela del `DEVLOG.md` de AeriaNexusPrototype, y sirve para lo
mismo: retomar el desarrollo en cualquier sesión.

Empieza el 2026-09-09. Lo anterior a esa fecha no está aquí: las horas y las entregas
están en `SEGUIMIENTO.md` y el detalle técnico, en los mensajes de commit.

---

## 2026-09-11 — Original a 1080p sin recodificar, proxy de 720p/15 y aviso del SOS al backend

### Por qué

Petición del manager: dos vídeos, el original sin comprimir y una copia ligera que el
backend pasa por un LLM. Además, el SOS lo graba el backend con Agora Cloud Recording,
porque mientras emite la unidad no puede grabar. El contrato está en
`docs/BACKEND-PROXY-AND-SOS.md` del repo de Nexus.

### Hecho

- **`RecordingProfile.kt`** (nuevo): 1080p si la cámara lo ofrece al grabador, y si no
  720p. La W1 da **1920x1088**, no 1920x1080; se acepta tal cual, porque recortar
  obligaría a recodificar. 8 Mbps. `EvidenceStore.segmentBytes(bitrate)` sustituye a los
  8 MB fijos, para que cada segmento siga durando unos 15 s.
- **El original ya no se recodifica.** Hasta hoy `VideoStamper` quemaba el rótulo en la
  evidencia, que salía como segunda generación. Ahora `IncidentAssembler` solo hace
  remux, y con un único segmento el fichero es el que escribió la cámara.
- **`IncidentProxy.kt`** (nuevo) + `VideoStamper.makeProxy`: 720 de lado corto
  (múltiplo de 16), 15 fps con `FrameDropper` por calendario, 1,5 Mbps y **el rótulo
  solo aquí**. Va en `incidents/<id>/proxy/` para que ni el manifest, ni el servidor
  HTTP, ni la subida lo tomen por un segmento. Bloque `proxy` en el manifest.
- **`UploadService`**: manifest, proxy y original, en ese orden. El proxy se borra al
  entregarse, y se reintenta aunque el original ya haya llegado.
- **`SosNotifier.kt`** (nuevo) + `api_url` en `upload.conf`: start, latido cada 10 s y
  stop, desde `LivestreamService`. Una sesión solo de PTT no avisa.

### Estado: VERIFICADO EN LA W1 contra los stubs

- INC_000016 e INC_000017: el proxy llegó antes que el original, con el hash verificado,
  y se borró de la unidad. `proxy_of` coincide con el sha256 del MP4 que hay en la
  unidad: **lo subido es lo que escribió la cámara**.
- Proxy de 1264x720 a 14,9 fps: unos 9 MB frente a 39 MB del original, hecho más rápido
  que el tiempo real.
- SOS: start, tres latidos y stop, con uid 9001 y `source: bodycam`.

### Dos cosas medidas que conviene no volver a discutir

- **La W1 graba a unos 24-25 fps a cualquier resolución.** Su grabación a 720p del
  08-sep ya daba 23,9. Fijar la exposición automática a [30,30] solo la subió a 24,9, y se
  revirtió: no había mejora demostrada y sí un probable coste con poca luz. No es algo
  que haya causado el cambio a 1080p.
- **Por eso el `FrameDropper` va por calendario.** La primera versión contaba desde el
  último frame guardado y, con 24 fps de entrada, se quedaba uno de cada dos: el proxy
  salía a 12 fps.

### Próximo paso

Nada commiteado. El rótulo no se ha vuelto a mirar a ojo en el proxy: su código no
cambia, pero ahora se dibuja sobre 720 líneas (texto de unos 30 px).

---

## 2026-09-09 — El PTT suena: la unidad deja de transmitir en silencio

### Hecho

`PttTones.kt` (nuevo). Cinco tonos sintetizados en PCM con `AudioTrack` — no
`ToneGenerator`, cuyos tonos son de telefonía (DTMF y supervisión) y no permiten la
subida y la bajada que hacen reconocible el par abrir/cerrar.

| tono | forma | significado |
|---|---|---|
| `abrir()` | subida 880 → 1320 Hz | tu micrófono está abierto |
| `cerrar()` | bajada 1320 → 880 Hz | tu micrófono se ha cerrado |
| `denegado()` | doble 300 Hz | NO se abrió: nadie te oye |
| `entra()` | 1568 Hz suelto, más bajo | otro ha abierto el canal |
| `sale()` | 1046 Hz suelto, más bajo | el otro ha soltado |

Suena en los seis puntos donde el estado del PTT cambia de verdad
(`LivestreamService`, más el descarte por falta de WiFi en `BtServerService`):
conmutación con el canal ya abierto, apertura de la sesión solo-audio, incidente en
curso que se queda el micrófono, anillo que no lo suelta en 3 s, canal que no abre, y
la caída por captura no confirmada. Ese último es el que importaba: es el fallo del
2026-09-08, en el que el agente hablaba creyendo que le oían.

### Decisiones que conviene no volver a discutir

- **Por qué hacía falta.** El PTT de la unidad es un conmutador (el firmware solo avisa
  al soltar el botón) y su pantalla de 3 cm no se ve con el equipo puesto. Sin sonido,
  el agente no tiene ninguna forma de saber si le están oyendo.
- **Dos notas son tuyas, una nota es de otro.** Si el que recibe oyese la misma subida
  que el que transmite, confundiría "estoy al aire" con "hay alguien al aire".
- **El tono de abrir va ANTES de publicar el micro y el de cerrar DESPUÉS de apagarlo**,
  para que el pitido se quede en la unidad y no viaje por el canal.
- **Un tono no puede mentir.** Cada fallo suena `denegado()`, nunca `cerrar()`: decirle
  al agente que ha soltado cuando lo que ha pasado es que nunca llegó a abrir sería
  justo el error que esto viene a corregir.
- **`USAGE_ALARM`** y no sonificación: en la unidad los volúmenes de multimedia y
  notificación vienen a cero de fábrica y el pitido no se oiría. El volumen real se fija
  en la propia onda (`AMPLITUD`), así que si en campo resulta fuerte se baja ahí.
- **La unidad NO suena al recibir, y no es un olvido.** Entra al canal con
  `autoSubscribeAudio = false` y no oye a nadie: es una cámara, no una radio; el agente
  que la lleva escucha por su teléfono. `entra()` y `sale()` existen igualmente en esta
  copia para que las dos no divorcien, y por si algún día se decide que la unidad
  reproduzca por su altavoz — sería un cambio de producto, no una corrección.

### Lo que hay que respetar al tocarlo

`PttTones` está **duplicado** en las dos apps, como `EvidenceCrypto`/`EvidenceKeys`. El
agente que lleva la bodycam en el pecho y el teléfono en la mano oye las dos, así que
**cambiar una frecuencia o una duración aquí obliga a cambiarla igual en
AeriaNexusPrototype**. No lo caza el compilador: los repos son independientes. Lo único
que difiere a propósito es el `AudioAttributes` (ver arriba). Todavía no está entre los
siete puntos que vigila el agente de coherencia.

### Estado: COMPILA, SIN VERIFICAR EN LA UNIDAD

`compileDebugKotlin` limpio. **Ningún tono se ha oído**: no había unidad accesible por
adb en toda la sesión.

### Próximo paso

1. Oírlos en la unidad. `adb logcat -s FalconLive:D FalconTone:W`; si aparece "No se
   pudo emitir el tono del PTT", el altavoz no acepta `USAGE_ALARM` y hay que probar
   `USAGE_ASSISTANCE_SONIFICATION`.
2. Comprobar en el móvil que el pitido de apertura no se cuela en la transmisión (el
   cancelador de eco de Agora debería comérselo).
3. Decidir si el agente de coherencia pasa a vigilar también que los dos `PttTones`
   sigan cuadrando.

---
