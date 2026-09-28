#!/usr/bin/env bash
#
# Alta de bodycams contra el IAM del backend (workflow 13, opcion B del 28-sep).
#
#   Uso:  tools/alta-bodycam-iam.sh [--url URL] [--telefonos S1,S2] [--forzar] [serial ...]
#
# Sin seriales, da de alta TODAS las bodycams conectadas por adb que tengan la app
# instalada: pensado para la etapa de pruebas, con varias unidades a la vez.
#
# Por que existe. Hasta ahora las W1 se daban de alta con la CA de pruebas
# (tools/alta-bodycam.sh del repo del movil) y los telefonos contra el backend, que
# firma con otras CA (qpd-device-ca / qpd-user-ca). Resultado: ninguna W1 aceptaba a
# un telefono dado de alta contra el backend, ni al reves. Aqui la W1 pide su
# certificado al MISMO backend que los telefonos, y con el recibe las dos anclas del
# tenant. Todas las unidades y todos los telefonos del tenant quedan en el mismo
# dominio de confianza; a quien sirve cada camara lo decide despues la atadura
# firmada del agente (workflow 33), no el alta.
#
# Por unidad:
#   1. la W1 genera (o reutiliza) su par EC del Keystore y deja su CSR;
#   2. el CSR va a POST /api/iam/devices/enroll como device_kind=bodycam. El backend
#      respeta el BWC-xxxx del CSR y devuelve el certificado y las dos anclas;
#   3. el certificado y las anclas vuelven a la W1, que comprueba la posesion de la
#      clave antes de instalarlos.
#
# Es idempotente: una unidad cuya ancla ya coincide con la del backend se salta
# (--forzar la repite). La clave de la W1 NO se regenera nunca: generarPar() no hace
# nada si ya existe, asi que repetir el alta no invalida lo firmado antes.
#
# --telefonos instala ademas el ancla de dispositivos del backend en telefonos YA
# dados de alta (el Samsung del 28-sep), para que acepten a las W1. Los que se den de
# alta a partir de la tarea AN-0 la guardan solos.
#
# Requisitos:
#   - backend levantado (por defecto http://localhost:8000, o IAM_URL / --url);
#   - si el backend exige secreto de enrolamiento: IAM_ENROLLMENT_TOKEN en el entorno;
#   - APK de DEPURACION en las W1 y en los telefonos: las ordenes de alta por adb y
#     run-as solo existen en debug (MainActivity, BuildConfig.DEBUG).
#
# Deja en tools/altas-iam/ (no va a git) las dos anclas del backend y un registro
# CSV de que unidad (serial adb) quedo con que BWC-xxxx.

set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash en Windows: que no reescriba rutas de la unidad

PKG_BWC="com.falconone.bodycamserver"
PKG_TEL="com.delta.aeria_nexus_prototype"
URL="${IAM_URL:-http://localhost:8000}"
TELEFONOS=""
FORZAR=0
SERIALES=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --url) URL="$2"; shift 2 ;;
        --telefonos) TELEFONOS="$2"; shift 2 ;;
        --forzar) FORZAR=1; shift ;;
        -h|--help) sed -n '2,45p' "$0"; exit 0 ;;
        *) SERIALES+=("$1"); shift ;;
    esac
done

REGISTRO="$(cd "$(dirname "$0")" && pwd)/altas-iam"
mkdir -p "$REGISTRO"
TRABAJO="$(mktemp -d)"
trap 'rm -rf "$TRABAJO"' EXIT
# En Git Bash, python, openssl y curl son programas de Windows y no entienden
# /tmp/...; con MSYS_NO_PATHCONV nadie se las traduce. C:/... vale para todos.
if command -v cygpath > /dev/null 2>&1; then
    TRABAJO="$(cygpath -m "$TRABAJO")"
    REGISTRO="$(cygpath -m "$REGISTRO")"
fi

fallo() { echo "  ✘ $*" >&2; }
huella() { openssl x509 -in "$1" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2; }
b64() { openssl base64 -A -in "$1"; }

# --- Backend ------------------------------------------------------------------

echo "==> Backend: $URL"
if ! curl -sf -m 5 "$URL/api/health/" > /dev/null; then
    echo "El backend no responde en $URL/api/health/. Levantalo o pasa --url." >&2
    exit 1
fi

# --- Unidades -----------------------------------------------------------------

if [[ ${#SERIALES[@]} -eq 0 ]]; then
    while read -r serial estado; do
        [[ "$estado" == "device" ]] || continue
        if adb -s "$serial" shell pm list packages "$PKG_BWC" | tr -d '\r' | grep -qx "package:$PKG_BWC"; then
            SERIALES+=("$serial")
        fi
    done < <(adb devices | tail -n +2)
fi
if [[ ${#SERIALES[@]} -eq 0 && -z "$TELEFONOS" ]]; then
    echo "No hay ninguna bodycam conectada con $PKG_BWC instalada." >&2
    adb devices -l >&2
    exit 1
fi

# Espera hasta N segundos a que aparezca un patron en el logcat de la unidad.
esperar_log() {
    local serial="$1" patron="$2" segundos="$3" etiquetas="$4"
    for _ in $(seq "$segundos"); do
        if adb -s "$serial" logcat -d -s $etiquetas | grep -q "$patron"; then return 0; fi
        sleep 1
    done
    return 1
}

run_as() { adb -s "$1" shell run-as "$PKG_BWC" "${@:2}" | tr -d '\r'; }

alta_de() {
    local serial="$1" dir="$TRABAJO/$1"
    mkdir -p "$dir"
    echo "==> Bodycam $serial"

    if ! adb -s "$serial" shell run-as "$PKG_BWC" true 2>/dev/null; then
        fallo "la app no es de depuracion: sin run-as ni ordenes de alta por adb"
        return 1
    fi

    # Idempotencia: el ancla instalada ya es la del backend.
    run_as "$serial" cat files/identity/ca.pem > "$dir/ancla-actual.pem" 2>/dev/null || true
    if [[ $FORZAR -eq 0 && -f "$REGISTRO/device-ca.pem" && -s "$dir/ancla-actual.pem" ]] &&
       [[ "$(huella "$dir/ancla-actual.pem")" == "$(huella "$REGISTRO/device-ca.pem")" ]]; then
        echo "  ✔ ya estaba dada de alta contra este backend (--forzar para repetir)"
        return 0
    fi

    # 0. El BWC sale del serial (BodycamIdentity.bwcId, desde el 29-sep) y leerlo
    #    pide READ_PHONE_STATE. En kiosco se lo da el device owner; si no, aqui. Se
    #    reinicia la app para que todo (uid de Agora incluido) coja el id nuevo.
    adb -s "$serial" shell pm grant "$PKG_BWC" android.permission.READ_PHONE_STATE 2>/dev/null ||
        fallo "no se pudo conceder READ_PHONE_STATE: el BWC seguira siendo el guardado"
    adb -s "$serial" shell am force-stop "$PKG_BWC"

    # 1. CSR. Se borra el anterior para no mandar uno viejo si la app no responde.
    run_as "$serial" rm -f files/identity/bwc.csr.pem || true
    adb -s "$serial" logcat -c
    adb -s "$serial" shell am start -n "$PKG_BWC/.MainActivity" --es bwc_enroll 1 > /dev/null
    if ! esperar_log "$serial" "CSR de BWC-" 15 BwcAlta; then
        fallo "la unidad no genero el CSR:"; adb -s "$serial" logcat -d -s BwcAlta BodycamIdentity | tail -5 >&2
        return 1
    fi
    run_as "$serial" cat files/identity/bwc.csr.pem > "$dir/bwc.csr.pem"
    if ! openssl req -in "$dir/bwc.csr.pem" -verify -noout 2>/dev/null; then
        fallo "CSR ilegible o con firma mala"; return 1
    fi
    local bwc
    bwc="$(openssl req -in "$dir/bwc.csr.pem" -noout -subject -nameopt multiline | sed -n 's/^ *commonName *= *//p')"
    echo "  identidad: $bwc"

    # Dos unidades con el mismo BWC (16 bits: ~0,7 % con 30) se pisarian el uid de
    # Agora y el backend rechazaria la segunda. Se para aqui, antes de mandar nada,
    # si otra unidad de esta tanda o del registro ya lo tiene.
    local otra="${BWC_EN_TANDA[$bwc]:-}"
    [[ -z "$otra" ]] && otra="$(awk -F, -v b="$bwc" -v s="$serial" '$3==b && $2!=s {print $2; exit}' "$REGISTRO/registro.csv" 2>/dev/null || true)"
    if [[ -n "$otra" && "$otra" != "$serial" ]]; then
        fallo "CHOQUE: $bwc es tambien la identidad de la unidad $otra. No se da de alta."
        fallo "Avisar: hay que decidir como desempatar (el id sale del serial y no cambia solo)."
        return 1
    fi
    BWC_EN_TANDA[$bwc]="$serial"

    # 2. Alta en el backend.
    local modelo so version
    modelo="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
    so="$(adb -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
    version="$(adb -s "$serial" shell dumpsys package "$PKG_BWC" | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1)"
    python - "$dir/bwc.csr.pem" "$modelo" "$so" "$version" > "$dir/peticion.json" <<'PY'
import json, sys
csr, modelo, so, version = sys.argv[1:5]
print(json.dumps({
    "csr_pem": open(csr, encoding="ascii").read(),
    "device_kind": "bodycam",
    "attributes": {"model": modelo, "os_version": so, "app_version": version},
}))
PY
    local cabecera_token=()
    [[ -n "${IAM_ENROLLMENT_TOKEN:-}" ]] && cabecera_token=(-H "Authorization: Bearer $IAM_ENROLLMENT_TOKEN")
    local codigo
    codigo="$(curl -s -m 20 -o "$dir/respuesta.json" -w '%{http_code}' \
        -H 'Content-Type: application/json' "${cabecera_token[@]}" \
        --data-binary @"$dir/peticion.json" "$URL/api/iam/devices/enroll")"
    case "$codigo" in
        201) ;;
        409)
            fallo "el backend ya tiene $bwc dado de alta. O esta unidad se dio de alta y le faltan"
            fallo "las anclas (revoca la credencial y repite), o OTRA unidad saca el mismo BWC-xxxx"
            fallo "de su serial (16 bits, ver BodycamIdentity.bwcId). Mira el registro:"
            grep ",$bwc," "$REGISTRO/registro.csv" 2>/dev/null >&2 || fallo "  (no hay ninguna alta anterior de $bwc en este PC)"
            return 1 ;;
        401) fallo "secreto de enrolamiento no valido: exporta IAM_ENROLLMENT_TOKEN"; return 1 ;;
        *) fallo "el backend respondio $codigo:"; cat "$dir/respuesta.json" >&2; echo >&2; return 1 ;;
    esac

    python - "$dir" <<'PY'
import json, os, sys
d = sys.argv[1]
r = json.load(open(os.path.join(d, "respuesta.json"), encoding="utf-8"))
open(os.path.join(d, "bwc.crt"), "w", newline="\n").write(r["certificate_pem"])
open(os.path.join(d, "device-ca.pem"), "w", newline="\n").write(r["device_ca_pem"])
open(os.path.join(d, "user-ca.pem"), "w", newline="\n").write(r["user_ca_pem"])
open(os.path.join(d, "meta"), "w", newline="\n").write(f'{r["device_id"]} {r["not_after"]}\n')
PY
    local device_id not_after
    read -r device_id not_after < "$dir/meta"
    if [[ "$device_id" != "$bwc" ]]; then
        fallo "el backend emitio para $device_id y la unidad es $bwc: no se instala"; return 1
    fi
    if ! openssl verify -CAfile "$dir/device-ca.pem" "$dir/bwc.crt" > /dev/null; then
        fallo "el certificado no lo firma la CA de dispositivos que vino con el"; return 1
    fi

    # 3. Certificado y anclas a la unidad. Ella comprueba la posesion de la clave.
    adb -s "$serial" logcat -c
    adb -s "$serial" shell am start -n "$PKG_BWC/.MainActivity" \
        --es bwc_cert "$(b64 "$dir/bwc.crt")" \
        --es bwc_anchor "$(b64 "$dir/device-ca.pem")" \
        --es bwc_user_anchor "$(b64 "$dir/user-ca.pem")" > /dev/null
    if ! esperar_log "$serial" "Prueba de posesion: true" 15 BwcAlta; then
        fallo "la unidad no confirmo el certificado:"; adb -s "$serial" logcat -d -s BwcAlta BodycamIdentity | tail -5 >&2
        return 1
    fi
    run_as "$serial" cat files/identity/ca.pem > "$dir/ancla-nueva.pem"
    if [[ "$(huella "$dir/ancla-nueva.pem")" != "$(huella "$dir/device-ca.pem")" ]]; then
        fallo "el ancla instalada no es la del backend"; return 1
    fi

    cp "$dir/device-ca.pem" "$REGISTRO/device-ca.pem"
    cp "$dir/user-ca.pem" "$REGISTRO/user-ca.pem"
    [[ -f "$REGISTRO/registro.csv" ]] || echo "fecha,serial_adb,bwc_id,caduca,backend" > "$REGISTRO/registro.csv"
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),$serial,$bwc,$not_after,$URL" >> "$REGISTRO/registro.csv"
    echo "  ✔ $bwc dada de alta; certificado hasta $not_after"
}

HECHAS=0; FALLIDAS=0
declare -A BWC_EN_TANDA=()
for serial in "${SERIALES[@]}"; do
    if alta_de "$serial"; then HECHAS=$((HECHAS + 1)); else FALLIDAS=$((FALLIDAS + 1)); fi
done

# --- Telefonos ya dados de alta --------------------------------------------------

if [[ -n "$TELEFONOS" ]]; then
    if [[ ! -f "$REGISTRO/device-ca.pem" ]]; then
        echo "No hay ancla del backend todavia: da de alta antes una bodycam." >&2
        exit 1
    fi
    IFS=',' read -ra LISTA <<< "$TELEFONOS"
    for tel in "${LISTA[@]}"; do
        echo "==> Telefono $tel: ancla de perifericos del backend"
        adb -s "$tel" logcat -c
        adb -s "$tel" shell am start -n "$PKG_TEL/.MainActivity" \
            --es peripheral_anchor "$(b64 "$REGISTRO/device-ca.pem")" > /dev/null
        sleep 3
        adb -s "$tel" logcat -d -s AeriaAlta | tail -2
    done
fi

echo
echo "==> Bodycams: $HECHAS bien, $FALLIDAS con fallo. Registro: $REGISTRO/registro.csv"
[[ $FALLIDAS -eq 0 ]]
