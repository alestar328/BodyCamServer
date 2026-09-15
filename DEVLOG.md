# DEVLOG — BodyCamServer (unidad bodycam)

Bitácora de desarrollo de la app de la unidad. Se actualiza cada día de trabajo.
Regla: cada entrada nueva va ARRIBA, con fecha, qué se hizo, decisiones tomadas y
próximo paso. Es la gemela del `DEVLOG.md` de AeriaNexusPrototype, y sirve para lo
mismo: retomar el desarrollo en cualquier sesión.

Empieza el 2026-09-09. Lo anterior a esa fecha no está aquí: las horas y las entregas
están en `SEGUIMIENTO.md` y el detalle técnico, en los mensajes de commit.

---

## 2026-09-15 — La unidad escucha el canal todo el tiempo (punto 1 del PTT hacia la bodycam)

### Por qué

Primer paso del PTT del teléfono sonando en la W1: la unidad tiene que estar dentro del
canal para oír a nadie. Hasta hoy solo entraba mientras emitía (SOS o su propio PTT), con
`autoSubscribeAudio = false`, y salía con `RtcEngine.destroy()`.

### Hecho

- **`LivestreamService`**: un solo motor, creado al arrancar y destruido solo al cerrar el
  servicio (`escuchar()` / `salirDelCanal()`).
  - Entra como **audiencia**, con `enableLocalAudio(false)` antes del join,
    `autoSubscribeAudio = true` y salida por el altavoz. Es lo que midió la sonda.
  - **El SOS y el PTT ya no entran ni salen:** suben a broadcaster con
    `updateChannelMediaOptions` y bajan a audiencia al terminar. Solo `aplicarPublicacion()`
    decide el rol, a partir de `isStreaming` y del micro.
  - El PTT abre y cierra el micro con `enableLocalAudio(true/false)`. Antes usaba
    `enableAudio/disableAudio`, que ahora cortaría también la escucha.
  - Desaparecen `openPttSession`, `closePttSession` y `upgradePttSessionToVideo`: sin
    sesiones propias no hay nada que ascender.
  - **SOS sin red:** queda pedido (`sosActivo`) y sale al volver a entrar al canal. Los
    tres botones de SOS (F3, panel y buffer) miran `sosActivo` para poder cortarlo mientras
    espera.
  - **Conexión perdida** (`CONNECTION_STATE_FAILED`): reentra cada 10 s. Si el PTT estaba
    abierto, se cierra y se avisa, porque ya no llega a nadie. Los cortes de red normales
    los recupera Agora solo.
- **`BtServerService`**: llama a `escuchar()` en `onCreate` y a `salirDelCanal()` en
  `onDestroy`.
- **`SondaEscuchaPtt`**: deja de tener motor propio, que chocaría con el compartido. Solo
  mide la batería, sacando a la unidad del canal y volviéndola a meter.

**Los teléfonos no notan el cambio:** la audiencia no les llega por `onUserJoined`, así que
siguen viendo a la unidad solo cuando emite.

### Estado: VERIFICADO EN LA W1 (sin teléfono)

- Al arrancar: `Joined falcon_group_channel uid=45182 en 318 ms (escuchando)`.
- **El micro sigue siendo del anillo:** en `audio_flinger` hay una sola pista de entrada (8 kHz,
  sin silenciar), creada antes de que Agora entrase.
- **PTT de la unidad** (broadcast 132 ×2): corta el anillo, captura sin que salte el
  vigilante, cierra y rearma el anillo al primer intento. Al acabar, otra vez una sola pista.
- **SOS** (broadcast 133 ×2): `SOS en el aire`, Agora abre la cámara y el codificador; al
  cortarlo `Livestream stopped (sigue escuchando: true)`, anillo rearmado y cámara devuelta.
  Tras el SOS entra al canal el uid 73777, que coincide con el aviso al backend.
- La unidad recibió eventos de audio de dos uids de teléfono que había en el canal
  (1940146813 y 1974208822), cuyo audio alternaba entre publicado y silenciado cada pocos
  segundos.

**PTT del Samsung → W1 (VERIFICADO):** con el PTT mantenido 8 s, la W1 registra
`Audio de 250828848: estado 1 → 2` (DECODING) y al soltar pasa a `estado 0 motivo 5`.
Mientras suena, la pista del anillo sigue siendo la única entrada y no está silenciada, y
Agora reproduce por una pista de salida a 48 kHz.

**Trampa encontrada en la prueba, del lado de Nexus:** los primeros intentos no llegaban
porque **el Samsung llevaba fuera del canal desde la 01:44**. Perdió la red a la 01:24,
Agora reintentó 20 min y dio `onConnectionFailure` (estado 5, motivo 4). Nexus no vuelve a
entrar y **sigue mostrando ONLINE**: el agente pulsa, oye sus tonos, ve ON AIR y no le oye
nadie. Se vio en `agoraapi.log` del teléfono, que va sin cifrar, al contrario que
`agorasdk.log`. Con la app reiniciada entró como 250828848 y funcionó. **Sin arreglar en
Nexus:** hace falta la misma reentrada que tiene hoy la unidad.

**Sin verificar:**
- Que la voz se oiga bien por el altavoz en esta prueba: el log confirma que se decodificó,
  pero falta que lo confirme alguien que estuviera escuchando.
- PTT del teléfono con un incidente grabando, y después de un SOS de la unidad. La sospecha
  de "no oye tras el SOS" se explica por la trampa de arriba.
- Un corte de red real y el `CONNECTION_STATE_FAILED` en la unidad.
- La batería.

### Próximo paso

- Nexus: volver a entrar al canal tras `CONNECTION_STATE_FAILED` y no pintar ONLINE sin
  estar dentro.
- Probar el PTT del teléfono con la W1 grabando un incidente y después de un SOS.
- **Punto 2:** que suene solo el PTT y no cualquier audio del canal. Hoy la unidad reproduce
  todo lo que se publique, y los dos uids que se vieron publicando y silenciando serían
  ruido.

---

## 2026-09-14 — La unidad se deja encontrar 5 minutos al arrancar

### Por qué

Nexus ya no lleva la MAC de la W1 metida al compilar: el agente busca su bodycam desde la
app. Pero la W1 está por defecto en `SCAN_MODE_CONNECTABLE`: acepta a quien ya sabe su
MAC y **no aparece en una búsqueda**. Un teléfono nuevo, como el del manager, no la
encontraba nunca.

### Hecho

- **`VisibilidadBluetooth.kt`** (nuevo): pone `SCAN_MODE_CONNECTABLE_DISCOVERABLE` durante
  300 s con el método oculto `BluetoothAdapter.setScanMode(int, int)`, por reflexión. La
  vía pública (`ACTION_REQUEST_DISCOVERABLE`) saca un diálogo del sistema que habría que
  aceptar en la pantalla de la unidad, y el panel se opera a ciegas.
- Se llama desde `BtServerService.onStartCommand`, **solo al arrancar el servidor** (se
  enciende la unidad o se abre la app). El tiempo es limitado a propósito: una unidad
  policial anunciándose todo el día se podría seguir por la calle.
- **La duración que se pasa a `setScanMode` no se cumple.** La pila la anota
  (`Discoverable Timeout:300`) pero en Android 9 el temporizador lo lleva la app de
  Ajustes: medido, la W1 seguía visible a los 8 minutos. La propia clase la vuelve a
  `SCAN_MODE_CONNECTABLE` con un `postDelayed`. Límite conocido: si el proceso muere
  dentro de esos 5 minutos, la unidad sigue visible hasta el siguiente arranque.

### Estado: VERIFICADO EN LA W1

- `setScanMode` devuelve true; la pila BT registra `Discoverable Timeout:300` y `Scan Mode:23`.
  El firmware no aplica las restricciones de API oculta (hasta la *dark greylist* enlaza).
- El Samsung, **desemparejado**, encuentra DSJ-ZXAN9A1 y conecta.
- Se oculta sola a los 5 minutos **con la pantalla apagada** (`Modo de escaneo 21: true`,
  vuelta a `SCAN_MODE_CONNECTABLE`). El temporizador corre porque el servicio tiene el
  `PARTIAL_WAKE_LOCK` `FalconOne::BtServer`: si algún día se quita, este `postDelayed`
  dejaría de contar en reposo.

### Cada unidad con su uid de Agora

Hasta hoy todas entraban como `9001`: dos a la vez se echaban del canal, y el teléfono no
distinguía su bodycam de la de otro agente (silenciaba el SOS ajeno).

- **`BodycamIdentity.uidAgora`**: `10000 + sufijo hexadecimal del BWC`. Esta W1
  (`BWC-896E`) entra como **45182**. No es un segundo identificador: si las identidades son
  distintas, los números también. Rango 10000-75535, por debajo del grabador en la nube
  (90000-99999). Si el sufijo no tiene formato hexadecimal de 4 cifras, se reparte por
  hash y se registra un error.
- Lo usan los dos `joinChannel` de `LivestreamService`, el `stream_uid` del STATUS
  (`Rsp.status` pierde sus valores por defecto y recibe el uid) y `SosNotifier`, que además
  manda `bwc_id` al backend.
- Actualizado el contrato 5 de `.claude/agents/coherencia-bodycam-movil.md`.

**Verificado:** `Joined falcon_group_channel uid=45182`; SOS, PTT y visor funcionan contra
el Samsung con el Nexus nuevo (detalle en su DEVLOG). Un Nexus antiguo **no** reconoce este
uid: hay que actualizar las dos apps juntas.

### Medición: la unidad escuchando el canal mientras graba

**`SondaEscuchaPtt.kt`**, solo en debug. Se registra en `BtServerService` bajo
`BuildConfig.DEBUG` y se maneja por broadcast:

    adb shell am broadcast -a com.falconone.bodycamserver.SONDA_PTT --es orden escuchar|parar|bateria

Entra en el canal como **audiencia**, con `enableLocalAudio(false)` antes del join,
`autoSubscribeAudio = true` y salida por el altavoz. **No se puede usar a la vez que el SOS
o el PTT**: Agora admite un solo motor por proceso, y el `RtcEngine.destroy()` de
`LivestreamService` se llevaría también el de la sonda.

**Resultado (VERIFICADO):**
- En `dumpsys media.audio_flinger`, **la única entrada sigue siendo la del anillo**
  (sesión 281, `Sil n`), antes, durante y después de escuchar. Agora no abre el micro.
- El modo de audio sigue en NORMAL y la entrada tarda 293 ms.
- PTT del Samsung desde otra habitación, 05:34:05-27 (hora de la W1):
  - Agora lo reproduce, con volumen hasta 168.
  - El usuario lo oye bien por el altavoz.
  - En los segmentos del anillo queda grabado a -11/-20 dB, y vuelve a -54 dB al acabar.
- Salir del canal no tocó el anillo, al contrario que el incidente del 08-sep: aquella vez
  Agora sí capturaba.
- Para analizar se copiaron los segmentos con un bucle por adb que vuelve a copiar si cambia
  el tamaño. Copiando solo una vez, uno de cada tres salía ilegible: se copiaba mientras
  `MediaRecorder` cerraba el fichero. El análisis se hizo con PyAV y numpy en el PC.

**Sin medir:**
- La batería: la medición de 20 min se lanzó, pero se aparcó sin analizar. CSV en
  `Android/data/com.falconone.bodycamserver/files/sonda_ptt_bateria.csv`.
- La latencia de extremo a extremo.

**Hallazgo lateral, sin tocar:** el audio de la evidencia va a **8.000 Hz mono**. `buildRecorder` no
llama a `setAudioSamplingRate` ni a `setAudioEncodingBitRate`, y `MediaRecorder` cae en su
valor por defecto. Para evidencia policial conviene subirlo (44,1 o 48 kHz), pero afecta
al tamaño de los segmentos y hay que medirlo.

### Próximo paso

- **PTT del teléfono sonando en la unidad**: requisitos cerrados el 14-sep y la medición clave
  sale bien. Construirlo, con un motor de Agora compartido entre la escucha, el SOS y el PTT
  de la unidad.
- Decidir la frecuencia de audio de la evidencia (ver arriba).

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
