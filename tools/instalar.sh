#!/bin/sh
# Instala la app en el móvil conectado por USB (depuración USB) y la empareja con el servidor:
# lee el token por SSH y lo escribe en los ajustes de la app (run-as, solo en la versión debug).
# El token no se muestra.  Uso: sh tools/instalar.sh [--sin-token]
set -e
cd "$(dirname "$0")/../android"
PKG=com.mnldz.asistente
# KEY (clave SSH), SSH (usuario@servidor) y SERVIDOR (ip:puerto por Tailscale): ver servidor.env.example
[ -f ../tools/servidor.env ] || { echo "Falta tools/servidor.env (copia tools/servidor.env.example)" >&2; exit 1; }
. ../tools/servidor.env

./gradlew -q assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

if [ "$1" != "--sin-token" ]; then
  adb shell am force-stop $PKG
  ssh -i "$KEY" "$SSH" 'sudo cat /home/asistente/datos/token' | tr -d '\r\n' | {
    read -r TOKEN || true
    [ -n "$TOKEN" ] || { echo "No se ha podido leer el token" >&2; exit 1; }
    printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<string name="servidor">%s</string>\n<string name="token">%s</string>\n</map>\n' "$SERVIDOR" "$TOKEN"
  } | adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/ajustes.xml'"
  echo "Emparejada con $SERVIDOR"
fi
adb shell am start -n $PKG/.MainActivity >/dev/null
