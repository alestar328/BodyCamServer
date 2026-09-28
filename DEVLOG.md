# DEVLOG — BodyCamServer (unidad bodycam)

Bitácora de desarrollo de la app de la unidad. Se actualiza cada día de trabajo.
Regla: cada entrada nueva va ARRIBA, con fecha, qué se hizo, decisiones tomadas y
próximo paso. Es la gemela del `DEVLOG.md` de AeriaNexusPrototype, y sirve para lo
mismo: retomar el desarrollo en cualquier sesión.

Empieza el 2026-09-09. Lo anterior a esa fecha no está aquí: las horas y las entregas
están en `SEGUIMIENTO.md` y el detalle técnico, en los mensajes de commit.

---

## 2026-09-29 — El BWC sale del serial; contestar al SOS por voz

### BWC derivado del serial (decidido por el usuario, antes del alta en el IAM)

- **`BodycamIdentity.bwcId`**: `BWC-` + 4 cifras hex del SHA-256 de `Build.getSerial()`.
  La W1 conocida (serial `30393016471440`) pasará a ser **`BWC-7D6F`**, uid de Agora
  **42111** (antes `BWC-896E` / 45182, aleatorio).
  - Qué arregla: reinstalar o borrar datos ya **no cambia el id** ni deja la credencial
    del IAM huérfana.
  - Qué NO arregla: el choque entre unidades sigue siendo de 16 bits (~0,7 % con 30).
    Alargarlo cambiaría el uid de Agora en las dos apps; se descartó.
  - Seriales genéricos (`unknown`, `0123456789ABCDEF`, todo el mismo carácter) o sin
    permiso: se sigue con el id guardado, o uno aleatorio si no hay ninguno.
  - Un id guardado solo se cambia por el del serial si **no hay certificado emitido**
    (emisor ≠ sujeto en el Keystore). Tras el alta, el id no se mueve.
- **`READ_PHONE_STATE`** en el manifiesto (solo para el serial). El device owner se lo
  concede solo en `aplicarPoliticas`; sin kiosco lo concede el guion.
- **`tools/alta-bodycam-iam.sh`**: concede el permiso y reinicia la app antes del CSR,
  y **para el alta si el BWC ya lo tiene otra unidad** de la tanda o del registro.
- El formato `BWC-XXXX` y la regla del uid no cambian: el móvil no tiene que tocar nada.
- Compila (`compileDebugKotlin`). **Sin probar en la W1**: no había ninguna conectada.
  Comprobar en la unidad que `Build.getSerial()` devuelve lo mismo que el serial de adb.

### Contestar al SOS: voz por PTT en el directo (decidido por el usuario)

Pregunta: el agente A emite SOS (vídeo + micro) y los demás le ven y le oyen, pero no
pueden contestarle. **Es posible y casi todo existe ya:**
- Todos los teléfonos están en el canal como broadcaster; el PTT del móvil publica el
  micro y anuncia `ptt_on`, y quien lo recibe (también el emisor del SOS) abre ese audio.
- La W1 escucha todo el canal siempre (`autoSubscribeAudio = true`, verificado el 15-sep).
- **Falta en el móvil:** el PTT solo está en Operations y se corta al salir de ella
  (`onDispose`); en la pantalla del directo del SOS no hay forma de hablar.

Encargado a la sesión AN (botón PTT de mantener en el directo, quién habla, audio en el
emisor). **Tarea BC:** probar en la W1 que durante su SOS, con micro abierto y altavoz,
la voz entrante no se acople.

---

## 2026-09-29 (PLAN) — Reparto del día: dos sesiones en paralelo, workflows sin backend

Escrito el 28-sep por la noche. **El mismo texto está en el DEVLOG de los dos repos.**
Objetivo de 2 días: el código de 6-8 workflows más y 3-4 verificados en aparatos. Lo
que solo compile se apunta como "compila, sin probar", no como terminado.

### 0 · Antes de paralelizar (el usuario, ~30 min)

1. **Commit en los dos repos.** Hoy hay trabajo de dos sesiones sin commitear mezclado
   en los mismos ficheros: BodyCamServer `dev_device_owner` (token prestado, agente
   atado, workflow 31 v2) y Aeria Nexus `dev_back_connection` (sesión, token, workflow
   31 v2). Trabajar en paralelo encima de eso es arriesgado.
2. **Anclas de la W1: opción B** (ver abajo), salvo que se decida otra cosa. Sin esto
   no se prueba nada que pase por el emparejamiento.
3. Tener conectados **la W1 y un teléfono dado de alta contra el backend** (Samsung).

**Las anclas, en corto.** La W1 valida al teléfono con `files/identity/ca.pem` (y la
atadura con `user-ca.pem`), que son de la CA de pruebas; el backend firma los teléfonos
con `qpd-device-ca`/`qpd-user-ca`. Y la W1 presenta un certificado de la CA de pruebas,
que un teléfono con las anclas del backend tampoco aceptaría. Tres salidas:
- **A · Solo las anclas del backend en la W1, por adb.** La W1 aceptaría al teléfono,
  pero su propio certificado seguiría siendo de la CA de pruebas: media solución.
- **B · Alta de la W1 contra el IAM (recomendada).** El backend ya lo soporta sin
  tocarlo: `POST /api/iam/devices/enroll` con `device_kind` bodycam respeta el
  `BWC-xxxx` y devuelve el certificado y las **dos** anclas. En la W1 ya existen las
  órdenes por adb (`bwc_enroll`, `bwc_cert`, `bwc_anchor`, `bwc_user_anchor`). Falta
  llevar el CSR al endpoint con el secreto de enrolamiento. Aprox. 1 h.
- **C · Que el backend firme con la CA de pruebas.** Toca el backend y rebaja su PKI. No.

**Añadido tras revisarlo la sesión AN (28-sep, noche).** Con la opción B la W1
presentará un certificado de `qpd-device-ca`, pero el teléfono la valida con su ancla de
periféricos (`perifericos.ca.pem`), que hoy solo se instala por adb con la CA de pruebas:
**no guarda el `device_ca_pem` que le devuelve su propio alta**. Sin arreglarlo, el
teléfono rechazaría a la W1 ya dada de alta. Es la tarea **AN-0**, que va antes de BC-1:
guardar esa ancla al darse de alta y conseguirla para el Samsung, ya dado de alta, sin
repetir el alta o por adb. La sesión AN avisa cuando esté.

### Sesión BC — BodyCamServer (dueña del adb de la W1)

1. **Alta de la W1 contra el IAM (opción B)** con `tools/alta-bodycam-iam.sh` (hecho el
   28-sep, sin probar; ver la entrada 2026-09-28 (2)) y prueba de punta a punta con el teléfono:
   emparejamiento v2 y canal cifrado (wf 31), atadura con nombre real (33/34), token
   prestado y subida al backend (40). Coordinar con la sesión AN el turno del teléfono.
   Si todo sale, proponer al usuario `EXIGIR_CANAL_CIFRADO = true`.
2. **Wf 61 en la unidad: diario de auditoría local.** Portar `AuditoriaLocal` del móvil
   (HMAC encadenado con clave propia del Keystore). Eventos: emparejamiento, atadura,
   token, grabar/parar, SOS, subida, cancelación, apagado. Hoy la unidad no tiene ninguno.
3. **Wf 30 en la unidad: fin de turno.** Hoy `FIN_DE_TURNO` solo se registra. Que desate,
   retire el token y lo apunte en el diario del punto 2.

### Sesión AN — Aeria Nexus (dueña del teléfono)

1. Ayudar a la prueba del punto BC-1 con el teléfono cuando la sesión BC lo pida.
2. **Wf 53: certificado que caduca en plena grabación.** Política local con periodo de
   gracia mientras se graba.
3. **Wf 51: grabación sin red, gobernada.** Conjunto cerrado de lo que se permite sin red,
   en vez de "todo funciona offline sin control".
4. **Wf 66: detectar el reseteo de fábrica** y exigir alta nueva en vez de seguir con
   carpetas huérfanas.
5. Si sobra tiempo, **wf 46**: el evento de auditoría ya existe desde el 15-sep
   (`EVIDENCIA_VISUALIZADA` y los de bóveda); lo que falta es el permiso de visionado
   por política.

### Compartidos: una sola sesión hace las dos puntas

- **Wf 37 + 36: firma y procedencia de la evidencia.** Toca el formato que leerá el
  backend en el wf 41, así que va en una sola sesión (la BC, tras su punto 1) y con
  entrada nueva en `docs/CRYPTO-FORMAT.md`. **Decisión previa del usuario:** qué clave
  firma. Propuesta: cada aparato con su clave de identidad (la BWC en la bodycam, la del
  terminal en el móvil) y el agente dentro de lo firmado, por la atadura.
- **Wf 21 (cadena de build: SAST, SCA, secretos)** si queda hueco: no toca código de las
  apps; puede ir a un subagente en su propio worktree.

### Reglas del día

- Cada sesión escribe solo en su repo. Si un cambio toca el protocolo entre las dos, lo
  hace una sola sesión en los dos lados y avisa a la otra antes (como el 28-sep).
- La W1 por adb es de una sesión a la vez: la BC. Las pruebas en aparatos van por turnos.
- Al cerrar: agente `coherencia-bodycam-movil` (ocho puntos), DEVLOG de cada repo y horas
  al libro del manager (filas libres 57-65, una fila por app y bloque).

---

## 2026-09-28 (2) — Alta de las W1 contra el IAM (opción B) y lo que rompía con varias unidades

### Por qué

El usuario eligió la opción B de las anclas: que las W1 pidan su certificado al mismo
backend que los teléfonos. Y pidió tener en cuenta que en pruebas habrá **varias bodycams,
varios agentes y varios teléfonos** a la vez.

### Hecho

- **`tools/alta-bodycam-iam.sh`**. Sin seriales, da de alta todas las bodycams conectadas.
  Por unidad: la W1 deja su CSR (sin regenerar la clave si ya existe), el CSR va a
  `POST /api/iam/devices/enroll` como `device_kind=bodycam`, y el certificado y las **dos
  anclas del tenant** vuelven a la unidad, que comprueba la posesión de la clave.
  - Es **idempotente**: una unidad cuya ancla ya es la del backend se salta.
  - Distingue el 409 (ese BWC ya está dado de alta) y el 401 (falta `IAM_ENROLLMENT_TOKEN`).
  - `--telefonos S1,S2` instala el ancla del backend en teléfonos ya dados de alta, como
    el Samsung.
  - Deja un registro serial ↔ BWC en `tools/altas-iam/`, que no va a git.
- **Los incidentes llevan la unidad en la subida: `incident_id = BWC-896E/INC_000038`**
  y `unit_id` nuevo.
  - Por qué: el backend junta las piezas por (dispositivo de la sesión, `incident_id`), y
    el dispositivo de la sesión es el **teléfono** que presta el token. Con varias unidades
    en rotación, el `INC_000005` de dos bodycams usadas por el mismo agente se fusionaba
    en un solo incidente.
  - En disco, en UPLOADS y en el teléfono el id sigue siendo el corto.
  - Documentado en `docs/UPLOAD-PROTOCOL.md`. No hace falta tocar el backend.

### Revisado para varias unidades, y ya correcto

- **Uid de Agora:** uno por unidad (14-sep).
- **Qué bodycam usa cada teléfono:** la elige el teléfono por MAC (14-sep).
- **Clientes Bluetooth:** la W1 atiende uno a la vez.
- **A quién sirve la cámara:** a quien la ata con su firma, y la atadura muere al caer el
  enlace.
- **Confianza entre aparatos:** con la opción B todos los teléfonos y bodycams del tenant se
  fían entre sí; a quién sirve cada cámara lo decide la atadura.

### Pendiente y riesgos

- **Sin probar**: no había ninguna W1 conectada ni el backend levantado. Probado sin aparato:
  la sintaxis del guion, la lectura del CSR, el JSON de la petición y de la respuesta, y
  las rutas en Git Bash (se corrigió un fallo: python, openssl y curl no entendían `/tmp/...`).
- **El `BWC-xxxx` es aleatorio de 16 bits** (`BodycamIdentity.bwcId`), no sale del serial
  como cree el backend.
  - Con 20 unidades, la probabilidad de que dos coincidan es del 0,3 %. El alta lo detecta
    (409), pero no lo arregla.
  - Desinstalar la app (o borrar sus datos) cambia el BWC y deja la credencial vieja
    huérfana en el backend.
  - `install -r` no le afecta.
- **Las órdenes de alta por adb solo existen en APK de depuración.** Si las pruebas van con
  release, hay que dar otra vía.
- **Cada W1 necesita su `upload.conf`** apuntando al backend. Hoy se pone a mano por adb.
- La tarea **AN-0** del teléfono sigue haciendo falta para los teléfonos que se den de alta
  a partir de ahora.

---

## 2026-09-28 — El canal Bluetooth con el teléfono va cifrado (workflow 31, v2)

### Por qué

El RFCOMM entre el teléfono y la W1 iba en claro. Desde el 25-sep por él viaja el
token de la sesión del agente (`TOKEN:<jwt>:<segundos>`), que vale para subir a
AeriaOne con todos los alcances del agente, y cualquiera con un receptor Bluetooth
cerca podía leerlo. Es el paso 6 del workflow 31, el siguiente de la lista
«Sin-backend-YA»: las dos puntas son nuestras.

### Hecho (en las dos apps, que tienen que ir juntas)

- **Emparejamiento v2 (`AERIA-BWC-2`).** `AUTH_HELLO` y `AUTH_ID` llevan un quinto
  campo: una clave pública ECDH P-256 **efímera**, de una sola conexión y fuera del
  Keystore (las claves de identidad son de firma y no sirven para acordar secretos).
  Las dos efímeras entran en la transcripción que firma cada extremo: quien estuviera
  en medio no puede cambiarlas por las suyas sin romper las dos firmas.
- **`CanalCifrado.kt`, idéntico en los dos repositorios** (solo cambia el `package`).
  Tras `AUTH_OK` cada línea viaja como `S:<base64>` con AES-256-GCM. Una clave por
  sentido (HKDF-SHA256 del secreto ECDH, con los dos nonces de sal) y el IV es un
  contador implícito: una trama repetida, quitada, reordenada o devuelta a quien la
  mandó no descifra, y **un solo fallo rompe el canal** y se corta la conexión.
- **Modo transición (decisión del usuario, 28-sep).** La bodycam sigue aceptando v1
  (autentica sin cifrar) y comandos en claro de un teléfono que no se empareja,
  porque sus anclas son las de la CA de pruebas y el backend firma los teléfonos con
  otras: exigirlo ya dejaría la W1 sin nadie que la maneje. El interruptor es
  `EXIGIR_CANAL_CIFRADO` en `BtServerService.kt`, hoy `false`.
- **Lo que sí se exige ya:** `TOKEN` solo por el canal cifrado (`TOKEN_FAIL:hace
  falta el canal cifrado`). Con el canal puesto, una línea en claro se rechaza en los
  dos extremos.

### Detalle de este lado

- `Emparejamiento.kt`: acepta v1 (4 campos) y v2 (5 campos); en v2 acuerda el canal
  (`canalAcordado`) al acreditar al teléfono y olvida la privada efímera.
- `BtServerService.kt`: `AUTH_OK` sale en claro y el canal se activa en el mismo
  cerrojo que `send()`, para que ningún `BTN_*` de otro hilo se cuele en medio;
  `abrir()` descifra cada línea entrante; `send()` cifra línea a línea. Un segundo
  emparejamiento dentro de un canal ya acordado se rechaza.

### Pendiente

- **Sin probar con hardware.** Las 13 pruebas JVM del lado del teléfono pasan
  (`EmparejamientoBodycamTest`, con una bodycam de referencia que es espejo de este
  `Emparejamiento.kt`), pero el código de ESTA app no tiene pruebas propias: la
  coincidencia byte a byte con el teléfono se confirma en el primer emparejamiento real.
- No se podrá probar de punta a punta hasta **alinear las anclas** de la W1 con las
  CA del backend (ver 25-sep (3)). Después, pasar `EXIGIR_CANAL_CIFRADO` a `true`.

---

## 2026-09-25 (3) — Prueba en la W1 sin teléfono: lo de "sin agente" funciona, y un fallo arreglado

Rama `dev_device_owner` (los cambios de hoy se replicaron aquí desde `dev_install_assistant`;
el asistente de instalación queda aparcado). APK debug 1.4 instalado con `install -r`: la
identidad de la unidad (`files/identity`) sobrevive.

### Verificado en la W1 (sin teléfono, así que sin atadura)

- Grabar con el botón simulado (`SIDE_KEY_INTENT 134`) y parar: `INC_000038` e `INC_000039`.
- `officer.json` = UNASSIGNED / - / NOAGENT; ficheros `NOAGENT_<fecha>_<hora>.mp4`.
- Manifiesto con `officer_name/rank/badge` de `officer.json`.
- **Rótulo quemado en el proxy**, descifrado con `tools/dev-keys`: "Officer: UNASSIGNED /
  Rank: - Badge: NOAGENT". Ya no sale John Smith.

### Fallo encontrado y arreglado

El manifiesto de `INC_000038` llevaba `"officer_user_id": "null"` (**texto**). `Officer.fromJson`
usaba `optString`, que con un null de JSON devuelve la cadena "null". Arreglado con `isNull`;
`INC_000039`, grabado con el arreglo, lleva `null` de verdad.

### Lo que NO se ha podido probar (hace falta un teléfono)

Emparejamiento, atadura con nombre real, token prestado, vigencia por duración. Y hay un
bloqueo previo, **confirmado hoy en la unidad**: sus anclas (`files/identity/ca.pem` y
`user-ca.pem`) son las de la CA de pruebas, y el backend firma con otras (`qpd-device-ca` /
`qpd-user-ca`, claves públicas distintas). Un teléfono dado de alta contra el backend será
rechazado por la W1 en el emparejamiento. Pendiente de decidir cómo se alinean.

### Observaciones de la prueba

- El reloj de la W1 está bien: 21:50 CST (UTC+8) es la misma hora que 15:50 CEST. El
  "desfase de 6 h" era la zona horaria.
- Tras reinstalar, la primera pulsación simulada se perdió: llegó mientras la app se armaba.
- `enabled_accessibility_services` sale `null` en la unidad. No se ha tocado.
- En el incidente quedan el `.mp4` **en claro** junto al `.fev`. No es de hoy, pero es
  evidencia sin cifrar en la tarjeta: revisar cuándo se borra el claro.
- Los dos incidentes de prueba reintentan subir al stub `192.168.0.14:1080` del `upload.conf`,
  que no está levantado. Se pueden cancelar desde el teléfono (pantalla UPLOADS).

---

## 2026-09-25 (2) — Los vídeos van a nombre del agente atado; adiós a "John Smith"

### Por qué

El rótulo del proxy, el overlay, el manifiesto, los nombres de fichero, la subida y el aviso
de SOS salían de `HardcodedOfficer` (John Smith, Corporal, 36975), fuera quien fuese el agente.

### Hecho

- **`Officer.kt`:** fuera `HardcodedOfficer`. Nuevos `SinAgente` (UNASSIGNED / - / NOAGENT) y
  `AgenteDeServicio`, el agente al que sirve la cámara ahora: lo pone `BindingAgente` al
  aceptar un BIND, lo quita el UNBIND o la caída del enlace, y caduca solo al leerlo. Es estado
  de Compose, así que el overlay se repinta solo.
- **Lo rotulado sale de la declaración firmada** (`officer_name/rank/badge`, que el teléfono
  añade desde hoy). Un teléfono antiguo no los manda y se rotula el `user_id`.
- **El incidente congela su agente** al empezar a grabar, en `incidents/<id>/officer.json`
  (`EvidenceStore.guardarOficial` / `oficialDe`). Lo leen el manifiesto (con `officer_user_id`
  nuevo), los nombres de fichero, el proxy y la subida: la grabación sobrevive a un corte del
  Bluetooth y la atadura no.
- **Si empezó sin agente** (Bluetooth caído justo al pulsar) y se ata uno **mientras sigue
  grabando**, el incidente pasa a su nombre. Después de parar, no.
- **Sin agente se rotula UNASSIGNED**, no un nombre inventado. Los incidentes anteriores (sin
  `officer.json`) también salen así en la subida: no se les atribuye nadie.
- El SOS manda la placa del agente atado en ese momento.

### Pendiente

- **Sin probar con hardware**. Depende de la atadura (wf 33/34), que nunca se ha verificado
  en los aparatos: si no ata, todo sale UNASSIGNED.
- ~~Ojo con la hora~~ **Resuelto el mismo día:** la atadura llegaba con `expires_at` absoluto
  del teléfono y la W1 lo comparaba con su reloj (6 h de desfase ya visto → 6 h de vigencia,
  o rechazo si pasaba de 12 h). Ahora `BindingAgente` toma la duración firmada
  (`expires_at − issued_at`, las dos del reloj del teléfono) y la cuenta desde su propio
  reloj. Tope de 12 h, la que firma el teléfono. Lo viejo lo sigue rechazando el nonce de la
  sesión. Sin cambios en el teléfono. Compila; sin probar con hardware.

---

## 2026-09-25 — La unidad sube con el token de la sesión del agente, que le presta el teléfono

### Por qué

La unidad subía con `stub-token` y el backend real exige un JWT de sesión con alcance
`video.upload`: **ningún vídeo de la bodycam llegaba a AeriaOne**. El backend solo abre
sesión al teléfono. De las tres salidas (token reducido emitido por el backend, sesión propia
de la unidad, o usar el del teléfono) **el usuario eligió la tercera** para la demo: no toca
el backend.

### Hecho

- **Comando nuevo `TOKEN:<jwt>:<segundos de vida>`** y **`TOKEN_CLEAR`**; responde `TOKEN_OK`
  o `TOKEN_FAIL:<motivo>`. Solo se atiende con el teléfono **acreditado** (workflow 31), como
  la atadura. Se procesa antes del `Log.d("CMD")`: la línea lleva una credencial.
- **`UploadConfig.token()`** devuelve el prestado mientras viva; si no, la línea `token=` del
  `upload.conf` (solo para el stub). Solo en memoria. Lo usan la subida y el aviso de SOS.
- Segundos de vida y no hora de caducidad: el reloj de la unidad ha ido 6 h desfasado.
- Al recibirlo, `UploadService.resumePending`: lo que esperaba credencial sale en el acto.
- **No se retira al caerse el enlace**: un micro-corte del BT no debe parar un vídeo largo.
  Se retira con `TOKEN_CLEAR` (bloqueo o fin de turno en el teléfono) o al caducar.

### Lo que se acepta con esta decisión

- La unidad tiene **todos** los alcances del agente (no solo `video.upload`) mientras dure la
  sesión: un turno, 8 h.
- El token viaja por un RFCOMM **sin cifrar**.
- En el backend las subidas de la unidad figuran con el **terminal del agente** como
  dispositivo (la metadata sigue diciendo `source=bodycam`).
- La salida limpia es un token reducido ligado al binding, emitido por el backend.

### Pendiente

- **Sin probar con hardware**: no había W1 ni teléfono conectados. Compila.
- Va con la entrada del mismo día en el DEVLOG de Aeria Nexus: las dos apps tienen que ir juntas.

---

## 2026-09-21 (madrugada) — Apagar la unidad apaga también las luces

### Por qué

El usuario apagó la bodycam **mientras grababa** y los infrarrojos se quedaron
encendidos, alumbrando con la unidad apagada. La entrada de más abajo, de esa misma
tarde, arregló el caso de *parar la grabación*; este es otro: al apagar no pasa por
`BtServerService.onDestroy` ni por `ModoNoche.parar()`, y **lo escrito en sysfs no es
estado del proceso** — lo mantiene el kernel y sobrevive a la app. Aquella entrada daba
el caso por no arreglable desde dentro; lo es para el apagado ordenado, que es
justamente el que se hace en mano desde el menú de encendido.

### Hecho

`ApagadoReceiver` nuevo, registrado **en código** por `BtServerService` (con
`targetSdk 28`, un receiver de `ACTION_SHUTDOWN` declarado en el manifest no recibe
nada: es un broadcast implícito fuera de la lista de excepciones de Android 8). Escucha
`ACTION_SHUTDOWN` y `ACTION_REBOOT`, y su `apagarTodasLasLuces()` es ahora el único
apagado de la app: lo usan también `BtServerService.onDestroy` y, como red de seguridad
del corte seco, `BootReceiver`.

- **Dos vetos antes de tocar nada.** El de `ModoNoche` ya existía. El nuevo es
  `LedSignals.apagando`: `refresh()` corre en **cada** cambio de estado de captura, y al
  apagar la unidad el sistema para las activities — sin el veto, ese `refresh()` volvía
  a poner el LED verde justo después de haberlo bajado. Lo quita `onCreate` del
  servicio, que es quien vuelve a pintar al arrancar.
- **`HardwareController.apagarTodo()`**: infrarrojo, LED, sensor de luz y filtro, en una
  sola llamada al shell (`writeNodes`). `ModoNoche.parar(apagarNodos = false)` cede la
  escritura para no repetirla.

### Medido en la unidad (YIMAO W1, 2026-09-21)

| | |
|---|---|
| Ensayo completo | IR encendido = **1441 lux**, tras el apagado = **0** |
| Coste total | 4113 ms → **2069 ms** |
| Infrarrojo / LED / sensor | 27 / 44 / 29 ms |
| **Motor del filtro IR-CUT** | **2070 ms** — la escritura espera a que la pieza acabe de moverse |

El filtro va **el último** de la lista por eso: las luces están apagadas en el primer
décimo de segundo aunque el sistema corte el apagado por la mitad, y el filtro no
alumbra (y `ModoNoche.arrancar()` lo vuelve a poner en cada encendido). Los tres
tiempos se registran en logcat en cada apagado: son el presupuesto, y el sistema da
~10 s para **todos** los receptores, no para el nuestro.

### Decisiones que conviene no volver a discutir

- **El ensayo `APAGAR_LUCES` no es un capricho.** `ACTION_SHUTDOWN` es un broadcast
  protegido: `adb shell` (uid 2000) se lleva un `SecurityException`, la unidad es build
  `user` sin root y `svc power reboot` no hace nada. Sin la acción de depuración, la
  única forma de probar el apagado es apagar de verdad y perder el cable. Va bajo
  `BuildConfig.DEBUG`, como la sonda de batería del PTT.
- **Para comprobarlo hay que reactivar el sensor de luz.** El apagado lo para, y con el
  sensor parado el nodo `lux` devuelve un valor rancio que cae 180 por lectura (1441,
  1261, 1081…) y parece un infrarrojo apagándose despacio. Costó un rato; está escrito
  en el KDoc de `ENSAYO_APAGADO`.
- **La escritura directa al nodo no cuela** desde la app: siempre cae al `sh -c`. Por
  eso agrupar las escrituras importa, aunque el grueso resultara ser el motor.
- **Sigue sin cubrirse el corte seco** (batería fuera, `force-stop`, crash): no hay
  broadcast que escuchar. Lo tapa `BootReceiver` en el siguiente encendido.

### Estado: PROBADO EN LA UNIDAD, INCLUIDO EL APAGADO REAL

Ensayo de apagado con la unidad grabando y los infrarrojos puestos: se apagan (1441 → 0
lux) y no vuelven a encenderse cuando después cambia el estado de captura. **Y el usuario
lo confirmó apagando la bodycam con el botón: se apagó del todo, sin luces.**
`assembleDebug` limpio e instalado en la unidad.

### Próximo paso

1. El menú de apagado sale girado 90°, como toda la pantalla del sistema: lo dibuja
   SystemUI y la app no lo puede rotar. Decidir si se quita `GLOBAL_ACTIONS` de las
   funciones del anclaje (ver `DeviceOwner.aplicarPoliticas`) y se apaga de otra forma,
   o se deja tumbado.
2. Apagar grabando deja el incidente sin cerrar. No se ha tocado: sellar el vídeo no
   cabe en los ~10 s del apagado. Si importa, es trabajo aparte.
3. Sigue sin comprobarse el giro del panel de `MainActivity`, que es de la sesión de la
   tarde.

---

## 2026-09-21 — Los infrarrojos no se apagaban, y la pantalla de inicio salía girada

### Por qué

Dos cosas vistas en la unidad por el usuario: una a oscuras, la otra al abrir la app.

**Las linternas IR.** Se encendían bien al grabar de noche, pero al parar seguían puestas.
No era un fallo de escritura del nodo: el modo noche ataba los LEDs a `camaraEnUso()`, que
es `isHoldingCamera || isStreaming`, y `isHoldingCamera` vale `state != IDLE` — es decir,
**el anillo armado cuenta como cámara en uso**. Parar una grabación vuelve a `ARMED`, no a
`IDLE`, y abrir la app arma el anillo para toda la guardia (`MainActivity.startService`).
A oscuras eso significa los LEDs encendidos desde que anochece hasta que alguien cierre la
app.

**La pantalla girada.** El panel de la unidad va montado girado, y el giro de −90° lo
aplicaba **solo** el overlay de `RecordingActivity`. `MainActivity` dibujaba el mismo
`ControlPanel` sin girar, así que al abrir la app el panel salía tumbado hasta que `arm()`
ponía la pantalla de grabación encima y tapaba el problema.

### Hecho

**Infrarrojos** — separado *medir* de *iluminar* en `ModoNoche.kt`:

```kotlin
/** Hay imagen que mejorar: anillo armado, grabando o emitiendo. */
private fun camaraEnUso(): Boolean =
    RecordingActivity.isHoldingCamera || LivestreamService.isStreaming

/** Hay imagen que iluminar: la que va a evidencia o al teléfono. */
private fun irHaceFalta(): Boolean =
    esDeNoche && (RecordingActivity.isRecording || LivestreamService.isStreaming)
```

- Todas las escrituras del nodo pasan por `aplicarIr()`, idempotente y sin orden, igual que
  `LedSignals.refresh()` con el LED de color. **Escribe siempre**, aunque crea que no cambia
  nada: el nodo es de solo escritura y lo tocan también los comandos `IR_ON`/`IR_OFF` que
  llegan del teléfono, así que lo que vale de verdad no se puede dar por sabido.
- `sincronizarIr()` es la entrada pública. Cuelga de `RecordingActivity.notifyStateChanged()`
  —por donde pasan **todos** los cambios de estado de captura— y de las dos transiciones de
  emisión de `LivestreamService`. Antes el apagado esperaba al siguiente ciclo del modo
  noche: hasta 20 s con las linternas puestas.
- `parar()` pone `parando = true` **lo primero de todo**. Antes no interrumpía al hilo, así
  que el `irOn` de un ciclo a medio correr pisaba al `irOff` del cierre del servicio y los
  LEDs se quedaban encendidos con el objeto ya parado y nadie que los bajara.
- El parpadeo de 800 ms para medir y el intervalo largo de 20 s solo se pagan si los LEDs
  están dando luz (`irEncendido`). Con el anillo armado y los LEDs apagados la lectura ya
  sale limpia, se mide cada 3 s, y al pulsar grabar el modo noche ya está decidido: el IR
  entra al instante en vez de tardar ~12 s en detectar la noche desde cero.

**Pantalla** — `Rotated` pasa de `private` a `internal` en `RecordingOverlay.kt`,
`MainActivity` envuelve su `setContent` con `Rotated(OVERLAY_ROTATION_DEGREES)` y queda
fijada a `landscape` en el manifest, como `RecordingActivity`.

### Decisiones que conviene no volver a discutir

- **Los LEDs siguen a la grabación; el filtro IR-CUT sigue al modo noche entero.** Quitar y
  poner el filtro mueve un motor, y hacerlo en cada arranque y parada de grabación sería un
  clic-clac constante. **El precio, y hay que validarlo con el cliente: el pre-roll de 20 s
  nocturno se graba con el filtro fuera pero SIN LEDs**, así que tiene menos luz que el resto
  del incidente. Decisión del usuario el 20-sep: es prioritario que las linternas no se
  queden encendidas.
- **El `landscape` de MainActivity no es cosmético.** Si las dos activities no parten de la
  misma orientación base, el mismo giro de −90° deja cada pantalla mirando a un lado. Con las
  dos en `landscape`, MainActivity queda geométricamente idéntica a RecordingActivity, que es
  la configuración que ya se lee bien en la unidad.
- **El valor del IR vive en sysfs, no en el proceso.** Si la app muere sin pasar por
  `onDestroy` (force-stop, kill por memoria, crash), los LEDs se quedan encendidos y solo los
  baja `BootReceiver` o el siguiente `ModoNoche.arrancar()`. Eso no tiene arreglo desde
  dentro de la app; es el motivo de que `arrancar()` empiece siempre poniendo modo día.
- **El comentario de `applyPreviewTransform` mentía.** Decía «el montaje físico añade otros
  45°: 135° en preview» y el código hace `+90f`. Manda el código: el preview se ve derecho en
  la unidad. Comentario corregido, código intacto.

### Estado: COMPILA Y GENERA APK, SIN PROBAR EN LA UNIDAD

`assembleDebug` limpio. **No había bodycam accesible por adb** en toda la sesión, así que ni
el apagado de las linternas ni el giro del panel se han visto funcionar.

Sesión del 20-sep. La hora va imputada al **21-sep a las 22:00** por indicación del usuario,
en `SEGUIMIENTO.md` (bloque `BC-9`) y en el libro de facturación (bloque `BC-11`, que es el
del modo noche del 18-sep — las dos numeraciones son independientes).

### Próximo paso

1. En la unidad y a oscuras: `adb logcat -s FalconNoche`, grabar, parar, y comprobar que las
   linternas se apagan en la misma parada y no 20 s después.
2. Comprobar que el panel se lee derecho **desde el primer segundo** al abrir la app. Si sale
   girado 180° en vez de derecho, es que la base de MainActivity ya era `landscape` y sobra
   uno de los dos cambios.
3. Decidir con el cliente si el pre-roll nocturno puede ir sin infrarrojos, o si hay que
   volver a encenderlos con el anillo armado asumiendo que duren toda la guardia.

---

## 2026-09-20 — Cancelar la subida de un incidente desde el teléfono

### Por qué

Una unidad estaba subiendo `INC_000032` (la grabación de la prueba de modo noche del 18-sep) una y
otra vez. No es un fallo: **la reanudación no se rinde nunca por diseño**. `resumePending()` se
llama al abrir la app y al arrancar la unidad, y reencola todo incidente sin recibo con
`delivered: true`; dentro de cada intento, `ChunkedUploader` aguanta 6 fallos con espera creciente
hasta 30 s. Para una evidencia real en una furgoneta sin cobertura es justo lo que queremos. Para
una prueba vieja o un destino mal configurado, es un bucle infinito que gasta batería y red.

Faltaba la puerta de salida.

### Hecho

- **`UploadCancel` (nuevo):** la marca de cancelación es un fichero `upload.cancelled` **dentro del
  directorio del incidente**, no una lista central: sobrevive a reinstalar la app y viaja con el
  incidente si alguien lo copia.
- **`ChunkedUploader.upload`** acepta `cancelado: () -> Boolean`. Se mira **entre bloques**, nunca
  dentro de un PATCH —partirlo dejaría al servidor con un offset que no responde a nada—, y también
  en rodajas de 500 ms durante la espera entre reintentos: de una tacada, cancelar durante el
  backoff largo tardaba hasta 30 s en notarse y desde el teléfono parecía que el botón no hacía nada.
- **`UploadService`:** `resumePending` salta los cancelados, `uploadIncidentChunked` sale de
  inmediato si ya lo estaba (el intent puede llevar rato en la cola del IntentService) y el aviso
  final distingue "cancelado" de "pendiente — se reanudará".
- **Protocolo BT:** `UPLOAD_LIST`, `UPLOAD_CANCEL:<id>` y `UPLOAD_RESUME:<id>`, con respuesta
  `UPLOADS:[{id,delivered,cancelled,pending}]`. `UPLOAD_RESUME` reencola en el momento, sin esperar
  al siguiente arranque: quien lo pide está mirando el teléfono ahora.
- **Atajo por adb** para desatascar una unidad en banco sin emparejar nada:
  `--es upload_cancel INC_000032` / `--es upload_resume INC_000032` a `MainActivity`.

### Lo que cancelar NO hace

**No borra el vídeo.** Corta la transferencia y saca el incidente de la cola de reintentos; el MP4,
el `.fev`, el manifiesto y la sesión de subida se quedan intactos. `UPLOAD_RESUME` lo devuelve a la
cola sin repetir un solo byte, porque el servidor conserva los bloques que ya recibió
(`UploadSessions`). Una bodycam en la que un botón hace desaparecer un vídeo no es defendible en
cadena de custodia; descartar de verdad, si algún día hace falta, será otra operación con su rastro.

### Pendiente

- **Falta la parte del teléfono.** Los tres comandos no los manda nadie todavía: hay que añadir en
  AeriaNexusPrototype la lista de subidas y los botones. Es un cambio del contrato entre las dos
  apps — pasarle el agente de coherencia.
- Sin probar en hardware (no había unidad conectada). Para confirmar el diagnóstico de INC_000032
  con la unidad delante: `adb logcat -s FalconUpload FalconChunk`,
  `adb shell cat /sdcard/FalconOne/incidents/INC_000032/upload.json` y `.../FalconOne/upload.conf`.

### Próximo paso

La lista de subidas en el móvil.

---

## 2026-09-20 — Modo kiosco: la app como device owner de la unidad (rama `dev_device_owner`)

### Por qué

El cliente pide "control total" de la bodycam, y sobre la mesa había tres caminos: kiosco con
device owner, pedir un firmware a medida al fabricante, o una ROM propia sobre AOSP. Se descarta
la ROM (haría falta el código del kernel y los componentes cerrados de Unisoc —cámara y módem—,
más desbloquear el bootloader, y complica defender la cadena de custodia) y el firmware a medida
queda como petición al fabricante, no como plan. **Device owner es lo único que podemos hacer
nosotros, con coste conocido y reversible sin borrar nada.**

### Hecho

- **`DeviceOwner` (nuevo):** todas las políticas en un sitio. Anclaje de pantalla (lock task) solo
  para nuestro paquete, barra de estado muerta, la app como lanzador
  (`addPersistentPreferredActivity` con `CATEGORY_HOME`), apps del fabricante escondidas
  (`com.wiite.camera`), y las restricciones de usuario: sin restablecimiento de fábrica, sin modo
  seguro, sin cambiar la hora, sin usuarios nuevos, sin ventanas superpuestas.
  Es idempotente y se llama en **cada arranque** de `MainActivity`: una unidad aprovisionada con
  una versión vieja recoge las políticas nuevas al actualizar la app, sin volver al cable.
- **`FalconDeviceAdmin` (nuevo):** el `DeviceAdminReceiver` al que apunta `dpm set-device-owner`.
  Sin lógica; solo registra en el log cuándo se activa y cuándo se pierde el rol.
- **`MainActivity`:** aplica políticas y ancla al crearse y al recuperar el foco; `singleTop` +
  `onNewIntent` para recibir las órdenes de mantenimiento con la app ya abierta.
- **`RecordingActivity`:** ancla también, junto a `goImmersive()`. Es la pantalla que el agente
  tiene delante casi todo el tiempo.
- **`tools/kiosco.sh` (nuevo):** aprovisionamiento y mantenimiento por adb —
  `estado`, `poner`, `on`/`off`, `politicas`, `hora`, `reboot`, `soltar`.

### Dos reglas que no se tocan

1. **`DISALLOW_DEBUGGING_FEATURES` no se pone nunca**, y `aplicarPoliticas` hace lo contrario:
   fuerza `ADB_ENABLED=1` por política y lo limpia si quedó puesto de una versión anterior.
   Con kiosco y sin adb, una app que no arranque deja la unidad muerta y solo la revive un borrado
   desde recovery. Por lo mismo se deja fuera `DISALLOW_INSTALL_APPS`: bloquearía `adb install`.
2. **Siempre hay salida.** `renunciar()` quita el rol **sin borrar nada** (`clearDeviceOwnerApp`),
   deshaciendo antes las restricciones y las apps escondidas, que si no se quedarían puestas sin
   nadie que pueda levantarlas. Se llega por adb incluso con el kiosco puesto: el anclaje bloquea
   las apps de terceros, no los intents a la nuestra.

### De regalo

- **Arranque automático.** Siendo el lanzador, la app abre sola al encender. Es lo que
  `BOOT_COMPLETED` no consigue en esta unidad (el firmware lo manda a la cola de background,
  verificado el 2026-08-26).
- **La hora.** `DevicePolicyManager.setTime` existe desde API 28, justo la de la W1:
  `tools/kiosco.sh hora` pone la del PC y quita las 6 h de desfase. Sin verificar si el firmware
  la repone al reiniciar.
- **El BACK del PTT.** Con la app anclada y siendo lanzador, el BACK que inyecta el firmware al
  mantener F2 no tiene a dónde ir. Falta comprobar si además deja de llegar a `RecordingActivity`.
- **La accesibilidad.** Ni un device owner puede encender un servicio de accesibilidad, así que
  sigue siendo cosa del aprovisionamiento: `kiosco.sh poner` lo activa con
  `settings put secure enabled_accessibility_services`, y las políticas dejan la lista de
  permitidos con la nuestra y nada más. Antes había que activarlo a mano en Ajustes, que en
  kiosco ya no se alcanza.

### La APK del fabricante, destripada

`MCP_NA20260109_276_ZXA_v286.apk` (146 MB), la que "reemplazó todo el interior del aparato".
**No reemplaza nada: es una app de usuario normal.**

- Paquete `com.smarteye.mcu`, `minSdk 21`, `targetSdk 29`.
- **Firmada con una clave de depuración**: `CN=Android Debug, OU=besovideo`. No es la clave de
  plataforma, así que no corre con permisos de sistema y los permisos privilegiados que pide
  (`READ_PRIVILEGED_PHONE_STATE`, `WRITE_MEDIA_STORAGE`, `MOUNT_UNMOUNT_FILESYSTEMS`) no se le
  conceden: Android los ignora en silencio.
- Declara `CATEGORY_HOME` (`com.smarteye.mcu.SplashActivity`) — **es un lanzador**, como el nuestro.
- Tiene receptor de administración (`com.smarteye.common.LockReceiver`), y en el dex solo aparecen
  `ADD_DEVICE_ADMIN`, `isAdminActive` y `lockNow`: eso es **device admin** (diálogo de
  consentimiento, sin cable), no device owner.
- **No usa** `startLockTask`, `setLockTaskPackages`, `setApplicationHidden`,
  `addPersistentPreferredActivity` ni `setStatusBarDisabled`. Comprobado sobre los tres dex.
- No declara servicio de accesibilidad: coge las teclas estando en primer plano y por
  `MEDIA_BUTTON`. Con la pantalla apagada no puede — nosotros sí.

O sea: la sensación de que se adueñó de la unidad es ser el lanzador por defecto + arrancar con el
sistema. El agente puede salirse, las apps del fabricante siguen ahí y el reset de fábrica no está
bloqueado.

### Decisión: el piloto va sin aprovisionamiento

Para el test con usuarios reales se usa el **modo piloto**, que es justo el nivel del fabricante:
la app como lanzador, sin device owner. Reversible, no toca cuentas y no puede dejar una unidad
inútil. El kiosco completo queda para las unidades que se entreguen a un cliente.

`tools/kiosco.sh piloto` hace la pasada por unidad —instalar, activar la accesibilidad, fijar el
lanzador con `cmd package set-home-activity` y abrir la app— y **recorre todas las unidades
conectadas**, para un despliegue con hub USB. `piloto-off` devuelve el lanzador del fabricante.

Sigue haciendo falta enchufar cada unidad una vez, porque alguien tiene que instalar la APK; lo que
desaparece es la ceremonia del rol, el requisito de no tener cuentas y el riesgo de dejarla tiesa.

Lo que el piloto **no** tiene, y conviene recordar al leer sus resultados: no se puede impedir que
el agente salga de la app, las apps del fabricante siguen compitiendo por la cámara, la hora sigue
6 h adelantada (`setTime` exige el rol) y los permisos hay que concederlos a mano en la pantalla
de 3 cm.

### Pendiente

- **Nada de esto se ha probado en la unidad: no había aparato conectado.** Compila y el APK sale.
  Prueba del piloto: `kiosco.sh piloto` → reiniciar y ver que arranca en FalconOne → comprobar que
  las teclas responden con la pantalla apagada (la accesibilidad quedó activada).
- Prueba del kiosco, en **una sola unidad**: `kiosco.sh estado` → `poner` → comprobar que se ancla,
  que el botón de inicio no saca de la app y que `adb` sigue vivo → reiniciar y ver que arranca
  sola → `kiosco.sh soltar` y confirmar que la unidad vuelve a la normalidad sin perder nada.
- El rol se rechaza si la unidad tiene **alguna cuenta configurada**; el script lo comprueba antes.
- Las órdenes van como extras de un intent a `MainActivity`, que está exportada: hoy los podría
  mandar cualquier app instalada. En kiosco no hay forma de instalar ni ejecutar otra app, pero no
  es autorización de verdad. **TODO:** moverlos al canal Bluetooth con el teléfono emparejado, que
  ya va autenticado con el certificado de la unidad.
- Ampliar `APPS_DEL_FABRICANTE` mirando `pm list packages` en la unidad — con cuidado: esconder un
  paquete de sistema equivocado deja la unidad sin llamadas.

### Próximo paso

Probar el modo piloto en una unidad y, si va, hacer la pasada del despliegue.

---

## 2026-09-18 — Modo noche automático: infrarrojo, filtro IR-CUT y blanco y negro

### Por qué (petición del usuario)

Le comentaron que la unidad "tenía grabación o sensor infrarrojo". Se miró por adb en la W1 y en
las apps del fabricante: el hardware estaba entero y **sin usar**. LEDs IR (`…/2-0064/ocp_regs`),
filtro IR-CUT motorizado (`wiite_con_ctrl/motor_enable`) y sensor de luz y proximidad ALPS
(`input0/driver/lux`, ~175 lux en interior), todos con permisos `rw-rw-rw-`. `IR_ON`/`IR_OFF`
existían por Bluetooth, pero el teléfono nunca los manda; el motor y el sensor no los llamaba
nadie. `com.wiite.camera` solo tiene una "escena nocturna" por software.

### Hecho

- **`ModoNoche` (nuevo):**
  - Hilo propio; lo arranca y lo para `BtServerService` (sustituye al `irOff()` suelto de antes).
  - **Solo con la cámara en uso** (anillo armado, grabando o SOS). Con la cámara parada vuelve a
    día y apaga el sensor, para no gastar batería con los IR en un cajón.
  - Histéresis: noche con **<10 lux en 3 lecturas** (cada 3 s); día con **>40 lux en 2 medidas**.
    Solo escribe en los cambios, así que no pisa un `IR_ON`/`IR_OFF` manual en cada lectura.
  - **De noche mide con el IR apagado** 0,8 s cada 20 s (ver abajo).
  - Avisa a la cámara con `alCambiar`.
- **`HardwareController`: el motor estaba documentado al revés.** Medido con fotogramas del visor
  (`/preview`): **`0` = filtro puesto (día)** y **`1` = filtro quitado** (imagen magenta en color).
  Renombrado a `filtroIrPuesto()` / `filtroIrQuitado()`, en vez de `motorForward`/`motorReverse`.
- **`RecordingActivity`:** guarda la petición de captura y la relanza con
  `CONTROL_EFFECT_MODE_MONO` de noche y `OFF` de día, sin rehacer la sesión (no corta el segmento
  del anillo). La W1 ofrece el efecto (`availableEffects [0 1 2 3 4 8]`); si otra cámara no lo
  ofreciera, se avisa en el log y se graba en color.
- **`MonocromoSos` (nuevo) + `LivestreamService`:** en el SOS la cámara la abre Agora, y la 4.3.0 no
  trae filtro por LUT. Un `IVideoFrameObserver` en lectura-escritura, I420, `POST_CAPTURER`, pone
  los planos U y V a 128 cuando es de noche. Se registra en `ponerSosEnElAire`, antes de `enableVideo`.

### El sensor ve el infrarrojo de sus propios LEDs

En la segunda prueba, a oscuras, el modo entraba y salía cada 11 s: **con el IR encendido el
sensor marca ~470 lux en plena oscuridad**, más que una habitación iluminada (175-257). Ningún
umbral lo separa. Midiendo apagado y encendido cada 50 ms, **el sensor promedia ~0,7 s** (de 463
a 0 en ~0,67 s). Por eso, de noche, cada 20 s se apaga el IR 0,8 s, se lee y se vuelve a encender.
El precio es un parpadeo oscuro breve cada 20 s en el vídeo nocturno y 20-40 s para volver a día.

### Verificado en la unidad

- Prueba 1 (sensor tapado con la mano): dos ciclos noche-día en el log y en el vídeo
  (`INC_000030`). Salía magenta: de ahí el monocromo.
- Prueba 2 (habitación a oscuras, `INC_000032`, 132 s): **blanco y negro con IR** durante toda la
  oscuridad y estable, sin bucle; parpadeos de medida cada ~20 s; vuelta a color ~39 s después de
  encender la luz.

### Pendiente

- **Probar el SOS en monocromo** (`MonocromoSos` compila y está instalado, sin probar):
  a oscuras y con la cámara armada, esperar el clic del filtro; pulsar SOS (F3); en el teléfono,
  el directo debe verse en blanco y negro; encender la luz y esperar ~45 s (vuelve a color);
  cortar el SOS y buscar en el log `no se pudo pasar el SOS a monocromo`.
- Tras cada medida hay ~1 s de imagen quemada al volver el IR (la exposición se adaptó a la
  oscuridad). Se podría suavizar con `AE_LOCK` durante la medida.
- Al volver a día hay ~0,5 s negros (13 fotogramas): parece el movimiento físico del filtro.
- Los umbrales (10 / 40 lux) son de partida; cada cambio deja sus lux en `adb logcat -s FalconNoche`.
- El búfer de log de la W1 es de 256 KB y Agora lo llena en minutos: leerlo justo después de probar.

### Próximo paso

La prueba del SOS en monocromo.

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
