#!/usr/bin/env bash
# Prepara el entorno (solo la primera vez) y arranca el agente de Teclado Movil en Linux.
# Equivalente a iniciar-agente.bat de Windows.
cd "$(dirname "$0")" || exit 1

salir() { read -rp "Presiona Enter para salir..." _; exit 1; }

echo "===================================================="
echo " Teclado Movil - Agente de Linux"
echo "===================================================="
echo

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: Python no encontrado. Instala python3."
  salir
fi

# Paquetes del sistema (no se pueden instalar con pip):
#   xdotool    -> escribir ñ, tildes y signos
#   xclip      -> copiar la captura del celular al portapapeles
#   python3-tk -> ventana con el QR
faltan=()
command -v xdotool >/dev/null 2>&1 || faltan+=(xdotool)
command -v xclip   >/dev/null 2>&1 || faltan+=(xclip)
python3 -c "import tkinter" >/dev/null 2>&1 || faltan+=(python3-tk)

if [ ${#faltan[@]} -gt 0 ]; then
  echo "Faltan paquetes del sistema: ${faltan[*]}"
  read -rp "¿Instalarlos ahora con sudo apt? [S/n] " resp
  if [[ ! "$resp" =~ ^[nN]$ ]]; then
    sudo apt install -y "${faltan[@]}" || echo "AVISO: no se pudieron instalar; algunas funciones pueden fallar."
  fi
  echo
fi

if [ ! -x .venv/bin/python ]; then
  echo "Creando entorno virtual..."
  if ! python3 -m venv .venv; then
    rm -rf .venv
    echo "ERROR: No se pudo crear el entorno virtual."
    echo "En Debian/Ubuntu/Mint instala: sudo apt install python3-venv"
    salir
  fi
fi

# Solo instala/actualiza dependencias si cambio requirements.txt.
if [ ! -f .venv/.deps_ok ] || [ requirements.txt -nt .venv/.deps_ok ]; then
  echo "Instalando dependencias de Python..."
  if ! .venv/bin/python -m pip install -r requirements.txt --quiet; then
    echo "ERROR: Falló la instalación de dependencias (¿hay internet?)."
    salir
  fi
  touch .venv/.deps_ok
  echo
fi

exec .venv/bin/python agente.py
