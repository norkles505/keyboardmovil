#!/usr/bin/env bash
# Compila e instala la app en un dispositivo Android (USB, emulador o WiFi).
# Equivalente al compilar.bat de Windows.
#
# Por WiFi: prueba la dirección guardada en wifi_device.txt y, si falla (el puerto
# de la depuración inalámbrica cambia a menudo), busca el celular solo en la red
# y actualiza wifi_device.txt con la dirección nueva.
cd "$(dirname "$0")" || exit 1

pausar() { read -rp "Presiona Enter para salir..." _; }

# --- SDK de Android: usa ANDROID_HOME si ya está definido; si no, busca ubicaciones comunes
if [ -z "$ANDROID_HOME" ]; then
  for c in "$HOME/Android/android-sdk" "$HOME/Android/Sdk"; do
    if [ -d "$c" ]; then ANDROID_HOME="$c"; break; fi
  done
fi
export ANDROID_HOME

# --- Java: si JAVA_HOME no está definido, usa un JDK 17 si existe; si no, el java del sistema
if [ -z "$JAVA_HOME" ]; then
  for j in /usr/lib/jvm/*17*; do
    if [ -d "$j" ]; then export JAVA_HOME="$j"; break; fi
  done
fi

ADB="$ANDROID_HOME/platform-tools/adb"
if [ ! -x "$ADB" ]; then ADB="$(command -v adb)"; fi
if [ -z "$ADB" ]; then
  echo "No encontré adb. Define ANDROID_HOME o instala platform-tools."
  pausar
  exit 1
fi

hay_dispositivo() {
  "$ADB" devices | awk 'NR>1 && $2=="device" {f=1} END{exit !f}'
}

# Busca en la red un celular con la depuración inalámbrica activa (mDNS) y devuelve IP:puerto
descubrir_wifi() {
  "$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF}' | head -n1
}

echo "=== Verificando dispositivo conectado (USB o emulador) ==="
"$ADB" devices

if ! hay_dispositivo; then
  echo
  echo "No hay nada por USB ni emulador. Probando conectar por WiFi..."
  # Limpia conexiones WiFi viejas que quedaron en estado "offline"
  "$ADB" disconnect >/dev/null 2>&1

  # 1) Dirección guardada
  if [ -f wifi_device.txt ]; then
    WIFI_ADDR="$(head -n1 wifi_device.txt | tr -d '\r\n ')"
    if [ -n "$WIFI_ADDR" ]; then
      echo "Conectando a $WIFI_ADDR por WiFi..."
      "$ADB" connect "$WIFI_ADDR"
      sleep 1
    fi
  fi

  # 2) Si no funcionó, buscarlo automáticamente en la red
  if ! hay_dispositivo; then
    echo
    echo "La dirección guardada no funcionó. Buscando el celular en la red..."
    NUEVA="$(descubrir_wifi)"
    if [ -n "$NUEVA" ]; then
      echo "Encontrado: $NUEVA"
      "$ADB" connect "$NUEVA"
      sleep 1
      if hay_dispositivo; then
        echo "$NUEVA" > wifi_device.txt
        echo "Guardé la dirección nueva en wifi_device.txt"
      fi
    else
      echo "No encontré ningún celular con la depuración inalámbrica activa."
      echo "Revisa en el celular: Opciones de desarrollador > Depuración inalámbrica (activada),"
      echo "y que el celular y esta PC estén en la misma red WiFi."
      echo "Si es la primera vez en esta PC, empareja antes con: adb pair IP:puerto-de-emparejamiento"
    fi
  fi
fi

if ! hay_dispositivo; then
  echo
  echo "No se encontró ningún dispositivo (USB, emulador ni WiFi). Cancelando."
  pausar
  exit 1
fi

echo
echo "=== Compilando e instalando ==="
if ./gradlew installDebug; then
  echo
  echo "Listo. La app se instaló."
else
  echo
  echo "Algo falló. Revisa el mensaje de error arriba."
  pausar
  exit 1
fi
