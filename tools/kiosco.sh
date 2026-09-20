#!/usr/bin/env bash
#
# Preparacion de la unidad bodycam: modo piloto y modo kiosco.
#
# Hay DOS niveles, y la diferencia esta en el rol de device owner:
#
#   piloto  La app es el lanzador y arranca sola, pero la unidad sigue abierta:
#           el agente puede salirse, las apps del fabricante siguen ahi y el
#           restablecimiento de fabrica no esta bloqueado. Es exactamente lo que
#           hace la app del fabricante (com.smarteye.mcu, analizada el 2026-09-20).
#           No toca cuentas, no borra nada y se deshace con "piloto-off".
#
#   poner   Kiosco de verdad: device owner. No se puede salir de la app, las apps
#           del fabricante se esconden, sin reset de fabrica, permisos concedidos
#           solos y la hora se puede corregir. Exige una unidad SIN CUENTAS y solo
#           se deshace con "soltar".
#
# Las dos pasan por USB una vez por unidad, porque alguien tiene que instalar la
# APK. La diferencia es el riesgo: "piloto" es reversible y no puede dejar la
# unidad inutil; "poner" cambia quien manda en el aparato.
#
# Uso:
#   tools/kiosco.sh piloto      instala y deja la app como lanzador (sin rol)
#   tools/kiosco.sh piloto-off  devuelve el lanzador del fabricante
#   tools/kiosco.sh estado      que ve la unidad ahora mismo
#   tools/kiosco.sh poner       kiosco completo: asigna el rol de device owner
#   tools/kiosco.sh on | off    activa o desactiva el anclaje de pantalla
#   tools/kiosco.sh politicas   reaplica las politicas sin tocar el rol
#   tools/kiosco.sh hora        pone la hora del PC en la unidad
#   tools/kiosco.sh reboot      reinicia la unidad
#   tools/kiosco.sh soltar      renuncia al rol. NO borra nada.
#
# "piloto" recorre TODAS las unidades conectadas (util con un hub USB en el
# despliegue del piloto); el resto de ordenes actuan sobre una sola.
#
set -euo pipefail

ADB=${ADB:-adb}
PKG=com.falconone.bodycamserver
ADMIN="$PKG/.FalconDeviceAdmin"
MAIN="$PKG/.MainActivity"
ACCESIBILIDAD="$PKG/.ButtonAccessibilityService"
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}

# Serie de la unidad sobre la que actuar. Vacio = la unica conectada.
SERIE=${SERIE:-}

adb_() { "$ADB" ${SERIE:+-s "$SERIE"} "$@"; }

# Una orden de mantenimiento: extras de un intent a MainActivity.
# Ver DeviceOwner.atenderOrdenDeMantenimiento.
orden() { adb_ shell am start -n "$MAIN" "$@" >/dev/null; }

unidades() { "$ADB" devices | awk '$2 == "device" { print $1 }'; }

requiere_unidad() {
    local n
    n=$(unidades | wc -l)
    if [ "$n" -eq 0 ]; then
        echo "No hay unidades por adb. Comprueba el cable y 'adb devices'." >&2
        exit 1
    fi
}

instalar() {
    if [ -f "$APK" ]; then
        echo "-> instalando $APK"
        adb_ install -r "$APK"
    else
        echo "-> sin APK en $APK; se usa la que ya esta instalada"
    fi
}

# El servicio de accesibilidad es como se capturan las teclas fisicas con la
# pantalla apagada. Ni un device owner puede encenderlo: es decision del usuario
# y solo se fuerza desde el shell. En una pantalla de 3 cm, ir a Ajustes >
# Accesibilidad a mano en cada unidad no es plan.
accesibilidad() {
    echo "-> activando el servicio de accesibilidad"
    adb_ shell settings put secure enabled_accessibility_services "$ACCESIBILIDAD"
    adb_ shell settings put secure accessibility_enabled 1
}

piloto_una() {
    echo "== unidad ${SERIE:-unica}"
    instalar
    accesibilidad
    # Lanzador por defecto sin device owner. Es lo mismo que elegir la app en el
    # dialogo de "que app usar > Siempre", pero sin tocar la pantalla de 3 cm.
    # Si esta orden no existiera en esta build de Android, queda el dialogo: pulsa
    # el boton de inicio en la unidad y elige FalconOne > Siempre.
    echo "-> poniendo la app como lanzador"
    if ! adb_ shell cmd package set-home-activity "$MAIN" 2>/dev/null; then
        echo "   (no se pudo; elige FalconOne > Siempre al pulsar inicio en la unidad)"
    fi
    echo "-> abriendo la app"
    adb_ shell am start -n "$MAIN" >/dev/null
    echo
}

piloto() {
    local series
    series=$(unidades)
    echo "Unidades conectadas:"; echo "$series" | sed 's/^/  /'; echo
    for s in $series; do
        # Asignacion suelta y no delante de la llamada: un prefijo de variable
        # ante una funcion se queda puesto despues, y aqui hay bucle.
        SERIE="$s"
        piloto_una
    done
    SERIE=""
    echo "Listo. La unidad arranca en FalconOne y se puede salir: es el piloto,"
    echo "no el kiosco. Para cerrarla del todo: tools/kiosco.sh poner"
}

piloto_off() {
    # Devuelve el lanzador del fabricante. No hay orden para "quitar" el
    # preferido, asi que se le da el suyo: com.smarteye.mcu es la app del
    # fabricante en estas unidades.
    echo "-> devolviendo el lanzador del fabricante"
    adb_ shell cmd package set-home-activity com.smarteye.mcu/com.smarteye.mcu.SplashActivity 2>/dev/null \
        || echo "   (no se pudo: pulsa inicio en la unidad y elige el lanzador del fabricante)"
}

estado() {
    echo "== Rol =="
    # dumpsys es la fuente fiable: 'dpm' no tiene consulta de estado.
    adb_ shell dumpsys device_policy | grep -iE "device owner|admin=" || echo "  sin device owner"
    echo
    echo "== Lanzador =="
    adb_ shell cmd package resolve-activity -c android.intent.category.HOME 2>/dev/null \
        | grep -iE "packageName|name=" | head -3 || echo "  desconocido"
    echo
    echo "== Anclaje =="
    adb_ shell dumpsys activity activities | grep -i "mLockTaskModeState" || echo "  desconocido"
    echo
    echo "== adb y accesibilidad =="
    echo -n "  adb_enabled: "; adb_ shell settings get global adb_enabled
    echo -n "  accesibilidad: "; adb_ shell settings get secure enabled_accessibility_services
    echo
    echo "== Hora =="
    echo -n "  unidad: "; adb_ shell date
    echo    "  PC    : $(date)"
}

poner() {
    # El rol se rechaza si hay cuentas: es la causa numero uno de que esto falle.
    local cuentas
    cuentas=$(adb_ shell dumpsys account | grep -c "Account {" || true)
    if [ "$cuentas" != "0" ]; then
        echo "La unidad tiene $cuentas cuenta(s) configurada(s): el rol se va a rechazar." >&2
        echo "Quitalas en Ajustes > Cuentas y vuelve a intentarlo." >&2
        exit 1
    fi

    instalar
    echo "-> asignando el rol de device owner"
    adb_ shell dpm set-device-owner "$ADMIN"
    accesibilidad
    echo "-> aplicando politicas"
    orden --es admin politicas
    echo
    estado
}

requiere_unidad

case "${1:-estado}" in
    piloto)     piloto ;;
    piloto-off) piloto_off ;;
    estado)     estado ;;
    poner)      poner ;;
    on)         orden --es kiosco on;  echo "Kiosco activado" ;;
    off)        orden --es kiosco off; echo "Kiosco desactivado (el rol sigue puesto)" ;;
    politicas)  orden --es admin politicas; echo "Politicas reaplicadas" ;;
    soltar)
        echo "Esto renuncia al rol de device owner. No borra ni la app ni la evidencia."
        read -r -p "Seguro? [s/N] " respuesta
        [ "$respuesta" = "s" ] || { echo "Cancelado"; exit 0; }
        orden --es admin renunciar
        echo "Rol renunciado. Para volver a ponerlo: tools/kiosco.sh poner"
        ;;
    hora)
        # La unidad va 6 h por delante del PC. Esto la pone en hora con la del PC,
        # en milisegundos de epoca, via DevicePolicyManager.setTime (API 28).
        # Solo funciona con el rol puesto: en modo piloto no hay forma.
        ms=$(( $(date +%s) * 1000 ))
        orden --el hora "$ms"
        sleep 1
        echo -n "unidad: "; adb_ shell date
        echo    "PC    : $(date)"
        ;;
    reboot)     orden --es admin reboot; echo "Reinicio pedido" ;;
    *)
        sed -n '3,34p' "$0" | sed 's/^# \{0,1\}//'
        exit 1
        ;;
esac
