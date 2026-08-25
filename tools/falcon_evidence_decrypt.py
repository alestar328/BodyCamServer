#!/usr/bin/env python3
"""
Descifrado y verificacion de evidencia FalconOne — formato FEVD v1.

Contrapartida del cifrado que hacen las dos apps Android del proyecto
(BodyCamServer en la unidad bodycam, AeriaNexusPrototype en el telefono). El
formato esta especificado en docs/CRYPTO-FORMAT.md; este script es la
implementacion de referencia del lado servidor.

POR QUE NO ES aes-256Sha256.py
------------------------------
Aquel script cifra el fichero entero con UN nonce y UN tag, en streaming sobre
encryptor.update(). Es correcto en Python/OpenSSL y no es implementable en
Android: Conscrypt acumula todo lo que se le pasa por Cipher.update() en un
buffer interno y solo cifra en doFinal(). Verificado en la unidad, 256 MB de una
vez revientan con OutOfMemoryError. Es inherente a los modos AEAD.

Por eso FEVD trocea: cada bloque de 1 MB es un mensaje GCM independiente, con su
nonce derivado y su tag. Los parametros criptograficos son los mismos que pedia
el script original: AES-256-GCM, nonce de 96 bits, tag de 128 bits, nonce fresco
que jamas se repite con la misma clave.

CLAVES
------
Cada fichero lleva una DEK aleatoria propia, nunca guardada en claro. La DEK va
"envuelta" en la cabecera, una vez por destinatario:

    srv:<kid>   envuelta con la publica de Nexus (RSA-OAEP/SHA-256)  -> --key
    ks:<alias>  envuelta con AndroidKeyStore, no sale del dispositivo -> no
                se puede abrir desde aqui, por diseno

USO
---
    # con la privada del servidor
    python falcon_evidence_decrypt.py INC_000001.mp4.fev -o salida.mp4 \
        --key nexus_private.pem

    # con una DEK conocida (pruebas de interoperabilidad)
    python falcon_evidence_decrypt.py video.fev -o salida.mp4 --dek <64 hex>

    # solo inspeccionar la cabecera, sin ninguna clave
    python falcon_evidence_decrypt.py video.fev --info

Requiere:  pip install cryptography
"""

import argparse
import hashlib
import os
import struct
import sys
import time

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"FEVD"
VERSION = 1
FIXED_HEADER = 29
TAG_BYTES = 16
PREFIX_BYTES = 8


class Header:
    """Cabecera FEVD ya parseada. `raw` son los bytes crudos, que son el AAD."""

    def __init__(self, raw, chunk, plain_size, nonce_prefix, wraps):
        self.raw = raw
        self.chunk = chunk
        self.plain_size = plain_size
        self.nonce_prefix = nonce_prefix
        self.wraps = wraps  # lista de (id, blob)

    @property
    def length(self):
        return len(self.raw)

    @property
    def blocks(self):
        if self.plain_size == 0:
            return 0
        return (self.plain_size + self.chunk - 1) // self.chunk

    @property
    def expected_size(self):
        return self.length + self.plain_size + TAG_BYTES * self.blocks


def read_header(f):
    fixed = f.read(FIXED_HEADER)
    if len(fixed) < FIXED_HEADER:
        raise ValueError("fichero demasiado corto para ser FEVD")
    if fixed[:4] != MAGIC:
        raise ValueError("no es un fichero FEVD (magic incorrecto)")
    if fixed[4] != VERSION:
        raise ValueError("version %d no soportada por este script" % fixed[4])

    header_len = struct.unpack(">H", fixed[6:8])[0]
    if header_len < FIXED_HEADER:
        raise ValueError("header_len %d invalido" % header_len)

    rest = f.read(header_len - FIXED_HEADER)
    if len(rest) != header_len - FIXED_HEADER:
        raise ValueError("cabecera truncada")

    chunk = struct.unpack(">I", fixed[8:12])[0]
    plain_size = struct.unpack(">Q", fixed[12:20])[0]
    nonce_prefix = fixed[20:20 + PREFIX_BYTES]
    wrap_count = fixed[28]

    wraps = []
    at = 0
    for _ in range(wrap_count):
        id_len = rest[at]
        at += 1
        wrap_id = rest[at:at + id_len].decode("ascii")
        at += id_len
        blob_len = struct.unpack(">H", rest[at:at + 2])[0]
        at += 2
        wraps.append((wrap_id, rest[at:at + blob_len]))
        at += blob_len

    return Header(fixed + rest, chunk, plain_size, nonce_prefix, wraps)


def unwrap_dek(header, private_key_path):
    """Recupera la DEK deshaciendo el envoltorio `srv:` con la privada de Nexus."""
    blob = next((b for (i, b) in header.wraps if i.startswith("srv:")), None)
    if blob is None:
        raise ValueError(
            "este fichero no lleva envoltorio de servidor; destinatarios: %s"
            % ", ".join(i for (i, _) in header.wraps)
        )

    with open(private_key_path, "rb") as kf:
        private_key = serialization.load_pem_private_key(kf.read(), password=None)

    # OAEP con SHA-256 tambien en MGF1: tiene que coincidir exactamente con lo que
    # hace NexusKeyWrapper en Android, o el descifrado falla sin decir por que.
    return private_key.decrypt(
        blob,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )


def decrypt(path, out_path, dek, verbose=True):
    """Descifra bloque a bloque y devuelve (sha256_claro, sha256_cifrado)."""
    total = os.path.getsize(path)
    plain_digest = hashlib.sha256()
    cipher_digest = hashlib.sha256()
    started = time.time()

    with open(path, "rb") as f:
        header = read_header(f)
        if total != header.expected_size:
            raise ValueError(
                "tamano %d, esperado %d: el fichero esta truncado o alterado"
                % (total, header.expected_size)
            )
        cipher_digest.update(header.raw)

        aesgcm = AESGCM(dek)
        remaining = header.plain_size
        index = 0

        with open(out_path, "wb") as out:
            while remaining > 0:
                plain_len = min(header.chunk, remaining)
                block = f.read(plain_len + TAG_BYTES)
                if len(block) != plain_len + TAG_BYTES:
                    raise ValueError("bloque %d incompleto" % index)

                # nonce = prefijo del fichero || indice del bloque
                # aad   = cabecera completa   || indice del bloque
                nonce = header.nonce_prefix + struct.pack(">I", index)
                aad = header.raw + struct.pack(">I", index)

                # InvalidTag no lleva mensaje: sin este envoltorio el fallo mas
                # importante del script se imprimiria como un error en blanco.
                try:
                    plain = aesgcm.decrypt(nonce, block, aad)
                except InvalidTag:
                    raise ValueError(
                        "AUTENTICACION FALLIDA en el bloque %d de %d. El contenido "
                        "no es el que se cifro: fichero manipulado, bloques "
                        "reordenados, cabecera alterada o clave incorrecta."
                        % (index, header.blocks)
                    )

                out.write(plain)
                plain_digest.update(plain)
                cipher_digest.update(block)
                remaining -= plain_len
                index += 1

    if verbose:
        mb = header.plain_size / 1048576.0
        elapsed = time.time() - started
        print("  bloques descifrados : %d de %d bytes" % (index, header.chunk))
        print("  tiempo              : %.2f s (%.1f MB/s)"
              % (elapsed, mb / elapsed if elapsed else 0))

    return plain_digest.hexdigest(), cipher_digest.hexdigest()


def print_info(path):
    with open(path, "rb") as f:
        header = read_header(f)
    total = os.path.getsize(path)
    print("Fichero      : %s" % os.path.basename(path))
    print("Formato      : FEVD v%d  (AES-256-GCM, bloques de %d KB)"
          % (VERSION, header.chunk // 1024))
    print("Claro        : %d bytes (%.1f MB)"
          % (header.plain_size, header.plain_size / 1048576.0))
    print("Cifrado      : %d bytes  (+%.4f %%)"
          % (total, (total - header.plain_size) * 100.0 / max(header.plain_size, 1)))
    print("Bloques      : %d" % header.blocks)
    print("Integridad   : %s"
          % ("OK" if total == header.expected_size
             else "TAMANO INCORRECTO, esperado %d" % header.expected_size))
    print("Destinatarios:")
    for wrap_id, blob in header.wraps:
        quien = ("servidor Nexus (RSA-OAEP)" if wrap_id.startswith("srv:")
                 else "dispositivo, clave en AndroidKeyStore (no exportable)"
                 if wrap_id.startswith("ks:") else "desconocido")
        print("  - %-28s %s, %d bytes" % (wrap_id, quien, len(blob)))
    return header


def main():
    parser = argparse.ArgumentParser(
        description="Descifra y verifica evidencia FalconOne (FEVD v1)."
    )
    parser.add_argument("input", help="fichero .fev")
    parser.add_argument("-o", "--output", help="destino del video en claro")
    parser.add_argument("--key", help="clave privada del servidor, PEM")
    parser.add_argument("--dek", help="DEK en hexadecimal, 64 caracteres (pruebas)")
    parser.add_argument("--info", action="store_true",
                        help="solo mostrar la cabecera, sin descifrar")
    parser.add_argument("--expect-sha256",
                        help="SHA-256 del claro esperado (el del manifest)")
    args = parser.parse_args()

    try:
        header = print_info(args.input)
        if args.info:
            return 0

        if not args.output:
            parser.error("hace falta -o/--output para descifrar")

        if args.dek:
            dek = bytes.fromhex(args.dek)
            if len(dek) != 32:
                parser.error("la DEK debe tener 32 bytes (64 caracteres hex)")
        elif args.key:
            dek = unwrap_dek(header, args.key)
        else:
            parser.error("hace falta --key (privada del servidor) o --dek")

        print("")
        plain_sha, cipher_sha = decrypt(args.input, args.output, dek)
        print("  sha256 claro        : %s" % plain_sha)
        print("  sha256 cifrado      : %s" % cipher_sha)
        print("  escrito en          : %s" % args.output)

        if args.expect_sha256:
            if plain_sha == args.expect_sha256.lower().replace("sha256:", ""):
                print("\nCADENA DE CUSTODIA OK: el hash coincide con el del manifest.")
            else:
                print("\nHASH DISTINTO DEL MANIFEST — la evidencia no es la esperada.")
                return 2
        return 0

    except Exception as exc:
        print("ERROR: %s" % exc, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
