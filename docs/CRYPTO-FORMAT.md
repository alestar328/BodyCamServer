# Formato de evidencia cifrada — FEVD v1

Contrato **compartido por las dos apps** del proyecto: `BodyCamServer` (unidad
bodycam) y `AeriaNexusPrototype` (teléfono). Las dos producen y consumen el mismo
fichero, con el mismo código, para que la evidencia se analice igual venga de donde
venga.

Extensión: `.fev` · Tipo MIME sugerido: `application/vnd.falconone.evidence`

---

## 1. Por qué no es el formato del script del cliente

`aes-256Sha256.py` (enviado por ciberseguridad) cifra con **un solo nonce y un solo
tag para todo el fichero**, en streaming sobre `encryptor.update()`. Es correcto en
Python/OpenSSL y **no es implementable en Android**: Conscrypt acumula todo lo que
se le pasa por `Cipher.update()` en un buffer interno y solo cifra en `doFinal()`.
Verificado en la unidad: 256 MB de una vez revientan con `OutOfMemoryError` en
`OpenSSLCipher$EVP_AEAD.expand`. Es inherente a los modos AEAD — el tag cubre todo
el mensaje, así que no hay salida parcial que emitir.

Lo que **sí** se toma del script, sin cambios: AES-256-GCM, nonce de 96 bits, tag de
128 bits, nonce fresco por operación y nunca reutilizado con la misma clave.

La diferencia es el troceado: cada bloque de 1 MB es un mensaje GCM independiente.
Coste 16 bytes por bloque (0,0015 %) y a cambio la memoria queda acotada al tamaño de
bloque en vez de al del fichero. Como efecto secundario útil, permite descifrar y
reproducir desde un offset sin recorrer todo el fichero.

Al cliente se le entrega `tools/falcon_evidence_decrypt.py`, que lee este formato.

---

## 2. Cabecera

Todos los enteros **big-endian**. La cabecera es texto claro y va **autenticada como
AAD en todos los bloques**: cambiar un byte de la cabecera invalida el fichero entero.

| off | tam | campo | valor |
|----:|----:|---|---|
| 0 | 4 | `magic` | ASCII `FEVD` |
| 4 | 1 | `version` | `0x01` |
| 5 | 1 | `flags` | `0x00` (reservado) |
| 6 | 2 | `header_len` | longitud total de la cabecera, envoltorios incluidos |
| 8 | 4 | `chunk_size` | bytes de texto claro por bloque (**1 048 576**) |
| 12 | 8 | `plain_size` | tamaño del fichero original |
| 20 | 8 | `nonce_prefix` | 8 bytes aleatorios, **únicos por fichero** |
| 28 | 1 | `wrap_count` | número de envoltorios de la DEK (≥ 1) |
| 29 | … | `wraps[]` | ver §3 |

Cada envoltorio: `id_len` (1) · `id` (ASCII) · `blob_len` (2) · `blob`.

## 3. Claves — envelope encryption

Cada fichero se cifra con una **DEK** aleatoria de 32 bytes (`SecureRandom`), usada
para ese fichero y nada más. La DEK nunca se guarda en claro: viaja **envuelta** en
la cabecera, una vez por cada destinatario que deba poder abrirla.

| `id` | Quién descifra | Estado |
|---|---|---|
| `ks:<alias>` | La propia unidad. DEK envuelta con una clave AES-256 de AndroidKeyStore que no sale del chip. `blob` = IV(12) ‖ ciphertext ‖ tag(16). | **Implementado.** Permite reproducción local. |
| `srv:<kid>` | El servidor Nexus. DEK envuelta con su **clave pública** RSA-OAEP(SHA-256). Una clave pública en el APK no es una filtración: es su sitio natural. | **Activo con clave de desarrollo** (`kid` = `dev-2026-08`) para poder subir y abrir el `.fev` de extremo a extremo. La pública real sigue pendiente de la decisión de custodia; sustituirla es cambiar dos constantes en `EvidenceKeys.kt`. Cualquier fichero cifrado para un `kid` que empiece por `dev-` es material de pruebas. |

Con los dos envoltorios presentes esto es la opción **C (híbrido)** de
`Seguridad-Claves-Bodycam.md` §3.5. Con solo `srv:` es la **B**. Añadir o quitar
destinatarios **no cambia el formato de los bloques**.

## 4. Bloques

Tras la cabecera, `ceil(plain_size / chunk_size)` bloques consecutivos. El bloque `i`
(0-indexado) contiene `min(chunk_size, plain_size - i*chunk_size)` bytes de
ciphertext seguidos de su **tag de 16 bytes**.

```
nonce_i = nonce_prefix (8) ‖ uint32be(i)        # nunca se repite dentro del fichero
aad_i   = cabecera completa ‖ uint32be(i)       # ata el bloque a su posición
```

El índice dentro del AAD impide reordenar bloques; `plain_size` en la cabecera —que
también es AAD— impide truncar el fichero sin que se note.

Tamaño total esperado: `header_len + plain_size + 16 * n_bloques`. El descifrador lo
comprueba antes de empezar.

## 5. Hashes

Se calculan **dos** SHA-256 y ambos se persisten en el `manifest.json`:

- `sha256_plain` — del MP4 original. Es el de la cadena de custodia: identifica el
  vídeo con independencia de cómo se haya cifrado o re-envuelto.
- `sha256_cipher` — del `.fev` tal cual sale de la unidad. Sirve para verificar la
  subida sin tener ninguna clave.

El hash del claro se calcula **en la misma pasada** que el cifrado, no en una lectura
aparte: el fichero se lee una sola vez.

## 6. Dónde se dispara

**Siempre al cerrar**, nunca en paralelo a la grabación — la unidad ya se calienta
grabando y el cifrado compite por CPU e I/O con el grabador.

| App | Punto de enganche |
|---|---|
| BodyCamServer | `RecordingActivity.finalizeIncidentAsync`, después de `IncidentAssembler.assemble` y antes de `writeManifest`. |
| AeriaNexusPrototype | al cerrar el destino en `LocalEvidenceRepository`, antes de `publish()`. |

**Aviso específico del móvil:** hoy los vídeos se publican en `DCIM/localIncidents`
vía MediaStore, o sea la galería pública. Un `.fev` no pinta nada ahí — la evidencia
cifrada va a almacenamiento **privado** de la app; en la galería, como mucho, queda
el claro mientras la política de retención lo permita.

## 7. Retención del claro

El borrado del MP4 en claro tras cifrar está **detrás de una constante, hoy en
`false`** (`EvidenceCrypto.DELETE_PLAINTEXT`), porque EVD-007 sigue pendiente de
decisión y porque la galería local sigue leyendo el claro. Es un cambio de una línea
cuando se decida.

La subida ya **no** lee el claro: desde que el destinatario `srv:` está activo,
`UploadService` sube el `.fev` y solo cae al MP4 si el cifrado falló al cerrar el
incidente (ver `docs/UPLOAD-PROTOCOL.md`). La condición para poder borrar el claro
con seguridad es la verificación del servidor: hasta que confirma `sha256_cipher`,
la evidencia no está entregada.

## 8. Coste medido (unidad real, grabación de 20 min / 473 MB)

SHA-256 1,36 s (349 MB/s) · AES-256-GCM a fichero 3,71 s (128 MB/s) · **total ~5,1 s**,
frente a ~3 min de subida a 20 Mbps. Sobrecoste de tamaño 0,0015 %.
