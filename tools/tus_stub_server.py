#!/usr/bin/env python3
"""
Servidor stub de subida reanudable - tus 1.0.0 (core + Creation) mas la
extension de verificacion de FalconOne.

Para que existe
---------------
Nexus todavia no expone endpoint de subida por bloques. Backend pidio no
bloquearse por eso: se implementa la logica de cliente contra este stub y el
dia que exista el endpoint real se cambia la URL base y nada mas. El contrato
que implementa este fichero es el documentado en docs/UPLOAD-PROTOCOL.md, que
es lo que se le entrega a backend.

No es un servidor de produccion y no pretende serlo: sin TLS, sin auth real,
sin limites de tamano. Vive en tools/ junto a falcon_evidence_decrypt.py y se
ejecuta en el PC del desarrollador; la unidad sube contra el por WiFi.

Uso
---
    python tools/tus_stub_server.py --dir ./uploads --port 1080

    # corta la conexion a mitad de la primera subida que pase de 12 MB,
    # para comprobar que el cliente reanuda por el offset correcto:
    python tools/tus_stub_server.py --fail-at 12582912

La unidad tiene que apuntar a la IP del PC en la LAN, no a localhost:
    adb shell "echo base_url=http://192.168.1.40:1080/files/ > /sdcard/FalconOne/upload.conf"

Solo biblioteca estandar: se ejecuta con cualquier Python 3.8+ sin instalar nada.
"""

import argparse
import base64
import hashlib
import json
import os
import re
import sys
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from datetime import datetime

TUS_VERSION = "1.0.0"
TUS_EXTENSIONS = "creation"

# Lo que la extension de verificacion espera encontrar en Upload-Metadata.
SHA_KEY = "sha256_cipher"

# Bloque de lectura del cuerpo del PATCH. Independiente del tamano de bloque
# que use el cliente: aqui solo acota la memoria del stub.
READ_BLOCK = 256 * 1024

_lock = threading.Lock()


def log(msg):
    print("%s  %s" % (datetime.now().strftime("%H:%M:%S"), msg), flush=True)


def parse_metadata(raw):
    """Upload-Metadata de tus: clave y valor en base64, pares separados por
    comas. Las claves sin valor son legales en el spec y llegan como cadena
    vacia."""
    out = {}
    if not raw:
        return out
    for pair in raw.split(","):
        pair = pair.strip()
        if not pair:
            continue
        parts = pair.split(" ", 1)
        key = parts[0]
        if len(parts) == 1:
            out[key] = ""
            continue
        try:
            out[key] = base64.b64decode(parts[1]).decode("utf-8", "replace")
        except Exception:
            out[key] = "<base64 invalido>"
    return out


class Store:
    """Un fichero .bin con los bytes recibidos y un .json con el estado.

    El estado se persiste en disco a proposito: matar el stub y volver a
    levantarlo tiene que dejar las subidas a medias exactamente donde estaban,
    porque es la mitad de lo que se esta probando.
    """

    def __init__(self, root):
        self.root = root
        os.makedirs(root, exist_ok=True)

    def meta_path(self, uid):
        return os.path.join(self.root, uid + ".json")

    def data_path(self, uid):
        return os.path.join(self.root, uid + ".bin")

    def create(self, length, metadata):
        uid = uuid.uuid4().hex
        state = {
            "id": uid,
            "length": length,
            "offset": 0,
            "metadata": metadata,
            "created_at": datetime.now().isoformat(timespec="seconds"),
            "complete": False,
            "verified": None,
            "sha256_received": None,
            "failed_once": False,
        }
        open(self.data_path(uid), "wb").close()
        self.save(state)
        return state

    def load(self, uid):
        if not re.match(r"^[0-9a-f]{32}$", uid or ""):
            return None
        try:
            with open(self.meta_path(uid), "r", encoding="utf-8") as fh:
                return json.load(fh)
        except OSError:
            return None

    def save(self, state):
        tmp = self.meta_path(state["id"]) + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(state, fh, indent=2)
        os.replace(tmp, self.meta_path(state["id"]))

    def append(self, state, data):
        with open(self.data_path(state["id"]), "r+b") as fh:
            fh.seek(state["offset"])
            fh.write(data)
        state["offset"] += len(data)

    def finish(self, state):
        """Cierra la subida y ejecuta la verificacion: el hash que declaro el
        cliente en Upload-Metadata contra el de los bytes realmente recibidos.

        Esto es lo unico que el servidor puede comprobar sin tener ninguna
        clave -el .fev llega cifrado- y es justo lo que hace falta para la
        cadena de custodia: demuestra que lo almacenado es byte a byte lo que
        salio de la unidad.
        """
        digest = hashlib.sha256()
        with open(self.data_path(state["id"]), "rb") as fh:
            for block in iter(lambda: fh.read(1024 * 1024), b""):
                digest.update(block)
        received = digest.hexdigest()

        declared = (state["metadata"].get(SHA_KEY) or "").lower()
        state["complete"] = True
        state["sha256_received"] = received
        state["verified"] = (received == declared) if declared else None
        self.save(state)
        return state


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "FalconTusStub/1.0"

    store = None
    fail_at = 0

    # -- utilidades --------------------------------------------------------

    def log_message(self, fmt, *args):
        pass  # el log util lo emitimos nosotros, no una linea por peticion

    def _base_headers(self):
        self.send_header("Tus-Resumable", TUS_VERSION)
        self.send_header("Cache-Control", "no-store")

    def _respond(self, code, headers=None, body=b""):
        self.send_response(code)
        self._base_headers()
        for key, value in (headers or {}).items():
            self.send_header(key, str(value))
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _json(self, code, payload):
        body = json.dumps(payload, indent=2).encode("utf-8")
        self._respond(code, {"Content-Type": "application/json"}, body)

    def _upload_id(self):
        m = re.match(r"^/files/([0-9a-f]{32})(/status)?$", self.path)
        return (m.group(1), bool(m.group(2))) if m else (None, False)

    def _check_auth(self):
        """Auth de mentira, deliberadamente: acepta cualquier Bearer no vacio.

        Existe para que el cliente ejercite el camino de mandar la cabecera y
        para que sustituir el token de stub por el real no toque el transporte.
        """
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or not auth[7:].strip():
            self._json(401, {"error": "falta Authorization: Bearer <token>"})
            return False
        return True

    # -- verbos ------------------------------------------------------------

    def do_OPTIONS(self):
        self._respond(204, {
            "Tus-Version": TUS_VERSION,
            "Tus-Extension": TUS_EXTENSIONS,
            "Tus-Max-Size": 8 * 1024 * 1024 * 1024,
        })

    def do_POST(self):
        # Algunos proxies corporativos filtran PATCH, y el HttpURLConnection del
        # JDK de escritorio ni siquiera lo admite. El cliente tiene ese camino
        # alternativo, asi que el stub tiene que poder ejercitarlo.
        if self.headers.get("X-HTTP-Method-Override", "").upper() == "PATCH":
            return self.do_PATCH()
        if self.path.rstrip("/") != "/files":
            self._json(404, {"error": "ruta desconocida: %s" % self.path})
            return
        if not self._check_auth():
            return

        try:
            length = int(self.headers.get("Upload-Length", ""))
        except ValueError:
            self._json(400, {"error": "falta o es invalido Upload-Length"})
            return
        if length <= 0:
            self._json(400, {"error": "Upload-Length debe ser > 0"})
            return

        metadata = parse_metadata(self.headers.get("Upload-Metadata"))
        with _lock:
            state = self.store.create(length, metadata)

        log("CREATE  %s  %s  %.1f MB  incidente=%s" % (
            state["id"][:8], metadata.get("filename", "?"),
            length / 1048576.0, metadata.get("incident_id", "?")))
        if metadata.get(SHA_KEY):
            log("        %s declarado: %s" % (SHA_KEY, metadata[SHA_KEY]))

        self._respond(201, {"Location": "/files/%s" % state["id"]})

    def do_HEAD(self):
        uid, _ = self._upload_id()
        state = self.store.load(uid) if uid else None
        if state is None:
            # 404 en un HEAD de tus significa "esta sesion ya no existe": el
            # cliente debe descartar su URL guardada y crear una nueva.
            self._respond(404)
            return
        self._respond(200, {
            "Upload-Offset": state["offset"],
            "Upload-Length": state["length"],
        })

    def do_PATCH(self):
        uid, _ = self._upload_id()
        state = self.store.load(uid) if uid else None
        if state is None:
            self._json(404, {"error": "sesion desconocida"})
            return
        if not self._check_auth():
            return
        if self.headers.get("Content-Type") != "application/offset+octet-stream":
            self._json(415, {"error": "Content-Type debe ser application/offset+octet-stream"})
            return

        try:
            offset = int(self.headers.get("Upload-Offset", ""))
        except ValueError:
            self._json(400, {"error": "falta o es invalido Upload-Offset"})
            return
        if offset != state["offset"]:
            # 409 es el codigo del spec y el que entrena al cliente a
            # re-preguntar el offset en vez de reintentar a ciegas.
            log("CONFLICT %s  cliente en %d, servidor en %d" % (
                uid[:8], offset, state["offset"]))
            self._respond(409, {"Upload-Offset": state["offset"]})
            return

        remaining = int(self.headers.get("Content-Length", "0"))
        if remaining <= 0:
            self._json(400, {"error": "falta Content-Length"})
            return
        if state["offset"] + remaining > state["length"]:
            self._json(400, {"error": "el bloque desborda Upload-Length"})
            return

        written = 0
        while remaining > 0:
            block = self.rfile.read(min(READ_BLOCK, remaining))
            if not block:
                break
            with _lock:
                self.store.append(state, block)
            remaining -= len(block)
            written += len(block)

            # Corte inyectado: se persiste lo escrito hasta aqui y se cierra la
            # conexion sin responder. Es la unica forma de probar de verdad que
            # el cliente reanuda por el offset correcto y no reenvia el fichero.
            if self.fail_at and not state["failed_once"] and state["offset"] >= self.fail_at:
                state["failed_once"] = True
                with _lock:
                    self.store.save(state)
                log("CORTE   %s  conexion cerrada en el byte %d (--fail-at)" % (
                    uid[:8], state["offset"]))
                self.close_connection = True
                return

        with _lock:
            self.store.save(state)

        pct = 100.0 * state["offset"] / state["length"]
        log("PATCH   %s  +%.1f MB  ->  %d/%d  (%.1f%%)" % (
            uid[:8], written / 1048576.0, state["offset"], state["length"], pct))

        if state["offset"] >= state["length"]:
            with _lock:
                self.store.finish(state)
            verdict = {True: "OK", False: "NO COINCIDE", None: "sin hash declarado"}[state["verified"]]
            log("HECHO   %s  sha256 %s" % (uid[:8], verdict))
            log("        recibido: %s" % state["sha256_received"])
            if state["verified"] is False:
                log("        declarado: %s" % state["metadata"].get(SHA_KEY))
            with open(self.store.data_path(uid), "rb") as fh:
                if fh.read(4) == b"FEVD":
                    log("        cabecera FEVD: es evidencia cifrada")

        self._respond(204, {"Upload-Offset": state["offset"]})

    def do_GET(self):
        """Extension de verificacion (no es tus): estado legible de una subida.

        Es lo que permite al cliente marcar la evidencia como entregada solo
        cuando el servidor ha confirmado el hash, y no antes.
        """
        if self.path.rstrip("/") == "/files":
            self._json(200, {"uploads": self._listing()})
            return
        uid, status = self._upload_id()
        if not uid or not status:
            self._json(404, {"error": "ruta desconocida: %s" % self.path})
            return
        state = self.store.load(uid)
        if state is None:
            self._json(404, {"error": "sesion desconocida"})
            return
        self._json(200, {
            "id": state["id"],
            "offset": state["offset"],
            "length": state["length"],
            "complete": state["complete"],
            "verified": state["verified"],
            "sha256_received": state["sha256_received"],
            "metadata": state["metadata"],
        })

    def _listing(self):
        out = []
        for name in sorted(os.listdir(self.store.root)):
            if not name.endswith(".json"):
                continue
            state = self.store.load(name[:-5])
            if state:
                out.append({
                    "id": state["id"],
                    "filename": state["metadata"].get("filename"),
                    "incident_id": state["metadata"].get("incident_id"),
                    "offset": state["offset"],
                    "length": state["length"],
                    "complete": state["complete"],
                    "verified": state["verified"],
                })
        return out


def main():
    ap = argparse.ArgumentParser(
        description="Servidor stub de subida reanudable (tus 1.0.0 core + Creation).")
    ap.add_argument("--port", type=int, default=1080)
    ap.add_argument("--host", default="0.0.0.0",
                    help="0.0.0.0 para que la unidad llegue por la LAN")
    ap.add_argument("--dir", default="uploads", help="donde se guarda lo recibido")
    ap.add_argument("--fail-at", type=int, default=0, metavar="BYTES",
                    help="corta la conexion una vez por subida al superar ese offset")
    args = ap.parse_args()

    Handler.store = Store(os.path.abspath(args.dir))
    Handler.fail_at = args.fail_at

    log("stub de subida en http://%s:%d/files/" % (args.host, args.port))
    log("guardando en %s" % Handler.store.root)
    if args.fail_at:
        log("corte inyectado al superar %d bytes (una vez por subida)" % args.fail_at)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log("parado")
    return 0


if __name__ == "__main__":
    sys.exit(main())
