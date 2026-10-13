#!/usr/bin/env python3
"""
Teclado Movil - Agente para Linux (X11)

Equivalente al agente de Windows (windows-agent/Program.cs). Habla EXACTAMENTE
el mismo protocolo WebSocket, asi que la app del celular funciona sin cambios:

  - Teclado, combinaciones, edicion de texto, mouse, scroll y brillo.
  - Transmision de la pantalla de la PC (JPEG por WebSocket).
  - Recibe la captura de pantalla del celular y la copia al portapapeles.
  - Muestra un QR (tecladomovil://IP:PUERTO/PIN) para conectar sin tipear.

Requiere una sesion X11 (el Linux Mint con Cinnamon la usa por defecto).
"""
import asyncio
import base64
import io
import json
import os
import secrets
import shutil
import socket
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

try:
    import mss
    import websockets
    import websockets.exceptions
    from PIL import Image, ImageDraw
    from Xlib import X
    from Xlib import display as xdisplay
    from Xlib.ext import xtest
except ImportError as e:
    print(f"Falta una dependencia de Python: {e.name}")
    print("Ejecuta ./iniciar-agente.sh (instala todo solo) o: pip install -r requirements.txt")
    sys.exit(1)

PUERTO = 8765
PIN = ""
ENTRADA = None                      # se crea en el hilo del servidor
AUTENTICADO_EVENTO = threading.Event()   # avisa a la ventana del QR que ya conecto un cliente
EJECUTOR_PANTALLA = ThreadPoolExecutor(max_workers=1)
_local = threading.local()


# ============================================================ TECLAS

# Nombre de tecla que manda el celular -> keysym de X11.
KEYSYMS = {
    "space": 0x20, "enter": 0xFF0D, "backspace": 0xFF08, "tab": 0xFF09,
    "escape": 0xFF1B, "shift": 0xFFE1, "ctrl": 0xFFE3, "alt": 0xFFE9,
    "delete": 0xFFFF, "arrowup": 0xFF52, "arrowdown": 0xFF54,
    "arrowleft": 0xFF51, "arrowright": 0xFF53,
    "win": 0xFFEB,              # Super_L
    "capslock": 0xFFE5, "home": 0xFF50, "end": 0xFF57,
    "pageup": 0xFF55, "pagedown": 0xFF56, "insert": 0xFF63,
    # Multimedia y brillo (XF86...)
    "volumemute": 0x1008FF12, "volumedown": 0x1008FF11, "volumeup": 0x1008FF13,
    "mediaprevioustrack": 0x1008FF16, "medianexttrack": 0x1008FF17,
    "mediaplaypause": 0x1008FF14,
    "brightnessup": 0x1008FF02, "brightnessdown": 0x1008FF03,
}
for _i in range(1, 13):
    KEYSYMS[f"f{_i}"] = 0xFFBE + (_i - 1)

# Si el teclado no tiene keycode para una tecla multimedia, se prueba con xdotool.
NOMBRE_XDOTOOL = {
    "volumemute": "XF86AudioMute", "volumedown": "XF86AudioLowerVolume",
    "volumeup": "XF86AudioRaiseVolume", "mediaprevioustrack": "XF86AudioPrev",
    "medianexttrack": "XF86AudioNext", "mediaplaypause": "XF86AudioPlay",
    "brightnessup": "XF86MonBrightnessUp", "brightnessdown": "XF86MonBrightnessDown",
}


async def ejecutar(*cmd, entrada=None):
    """Corre un comando externo sin bloquear el loop. Devuelve el codigo de salida (o -1)."""
    if not shutil.which(cmd[0]):
        return -1
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdin=asyncio.subprocess.PIPE if entrada is not None else asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
    )
    try:
        await asyncio.wait_for(proc.communicate(entrada), timeout=5)
    except asyncio.TimeoutError:
        proc.kill()
        return -1
    return proc.returncode


class Entrada:
    """Simula teclado y mouse con la extension XTEST de X11."""

    def __init__(self):
        self.d = xdisplay.Display()
        if not self.d.has_extension("XTEST"):
            raise RuntimeError("El servidor X no tiene la extension XTEST.")
        self.teclas_abajo = set()
        self.botones_abajo = set()

    # ---------- bajo nivel
    def _tecla_xtest(self, keysym, abajo):
        keycode = self.d.keysym_to_keycode(keysym)
        if not keycode:
            return False
        xtest.fake_input(self.d, X.KeyPress if abajo else X.KeyRelease, keycode)
        self.d.flush()
        if abajo:
            self.teclas_abajo.add(keycode)
        else:
            self.teclas_abajo.discard(keycode)
        return True

    def soltar_todo(self):
        """Suelta teclas/botones que hayan quedado apretados (ej. se corto la conexion con Shift sostenido)."""
        try:
            for kc in list(self.teclas_abajo):
                xtest.fake_input(self.d, X.KeyRelease, kc)
            for b in list(self.botones_abajo):
                xtest.fake_input(self.d, X.ButtonRelease, b)
            self.d.flush()
        except Exception:
            pass
        self.teclas_abajo.clear()
        self.botones_abajo.clear()

    # ---------- teclado
    async def tecla(self, key, abajo):
        k = key.lower()
        keysym = KEYSYMS.get(k)
        if keysym is None and len(k) == 1 and k.isascii() and k.isalnum():
            keysym = ord(k)

        if keysym is not None:
            if self._tecla_xtest(keysym, abajo):
                return
            if k in NOMBRE_XDOTOOL:
                if abajo:
                    await ejecutar("xdotool", "key", NOMBRE_XDOTOOL[k])
                return

        # Cualquier otro caracter suelto (ñ, tildes, signos...) se tipea con
        # xdotool, que resuelve solo la distribucion de teclado activa.
        if len(key) == 1:
            if abajo:
                await ejecutar("xdotool", "type", "--clearmodifiers", "--delay", "0", "--", key)
            return

        print(f"[!] Tecla no reconocida: {key}")

    async def combo(self, combo):
        teclas = combo.split("+")
        for t in teclas:
            await self.tecla(t, True)
            await asyncio.sleep(0.015)
        for t in reversed(teclas):
            await self.tecla(t, False)
            await asyncio.sleep(0.015)

    async def edicion(self, borrar, insertar):
        """Borra N caracteres y escribe texto, con una pausa entre teclas para no perder ninguna."""
        for _ in range(borrar):
            self._tecla_xtest(KEYSYMS["backspace"], True)
            self._tecla_xtest(KEYSYMS["backspace"], False)
            await asyncio.sleep(0.008)
        for c in insertar:
            if c == "\n":
                continue
            clave = "space" if c == " " else c
            await self.tecla(clave, True)
            await self.tecla(clave, False)
            await asyncio.sleep(0.008)

    async def brillo(self, subir):
        paso = "10%+" if subir else "10%-"
        if await ejecutar("brightnessctl", "set", paso) == 0:
            return
        # Sin brightnessctl: se manda la tecla de brillo, que Cinnamon/GNOME ya manejan.
        clave = "brightnessup" if subir else "brightnessdown"
        await self.tecla(clave, True)
        await self.tecla(clave, False)

    # ---------- mouse
    def mover(self, dx, dy):
        if dx == 0 and dy == 0:
            return
        xtest.fake_input(self.d, X.MotionNotify, 1, x=dx, y=dy)   # detail=1 -> movimiento relativo
        self.d.flush()

    def click(self, boton, abajo):
        b = {"left": 1, "middle": 2, "right": 3}.get(boton, 1)
        xtest.fake_input(self.d, X.ButtonPress if abajo else X.ButtonRelease, b)
        self.d.flush()
        if abajo:
            self.botones_abajo.add(b)
        else:
            self.botones_abajo.discard(b)

    def scroll(self, cantidad):
        """cantidad en unidades de WHEEL_DELTA: 120 = un 'click' de rueda hacia arriba, -120 hacia abajo."""
        b = 4 if cantidad > 0 else 5
        for _ in range(max(1, abs(cantidad) // 120)):
            xtest.fake_input(self.d, X.ButtonPress, b)
            xtest.fake_input(self.d, X.ButtonRelease, b)
        self.d.flush()


# ============================================================ PANTALLA

CURSOR = [(0, 0), (0, 13), (3, 10), (5, 15), (7, 14), (5, 9), (9, 9)]


def capturar_jpeg(ancho_destino=600, calidad=68):
    """Captura el monitor principal reducido a ancho_destino, con el cursor dibujado, como JPEG."""
    if not hasattr(_local, "sct"):
        _local.sct = mss.mss()
        try:
            _local.disp = xdisplay.Display()   # conexion propia de este hilo (Xlib no es thread-safe)
        except Exception:
            _local.disp = None

    sct = _local.sct
    mon = sct.monitors[1] if len(sct.monitors) > 1 else sct.monitors[0]
    shot = sct.grab(mon)
    img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
    alto = max(1, int(ancho_destino * img.height / img.width))
    img = img.resize((ancho_destino, alto), Image.Resampling.BILINEAR)

    # La captura no incluye el cursor: se dibuja aparte, a la misma escala.
    if _local.disp is not None:
        try:
            p = _local.disp.screen().root.query_pointer()
            esc = ancho_destino / mon["width"]
            x = int((p.root_x - mon["left"]) * esc)
            y = int((p.root_y - mon["top"]) * esc)
            if 0 <= x < ancho_destino and 0 <= y < alto:
                ImageDraw.Draw(img).polygon(
                    [(x + px, y + py) for px, py in CURSOR], fill="white", outline="black")
        except Exception:
            pass

    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=calidad)
    return buf.getvalue()


async def transmitir_pantalla(ws):
    """Manda capturas una tras otra (~15 fps) hasta que se cancele la tarea o se corte la conexion."""
    objetivo = 0.066
    anterior = None
    loop = asyncio.get_running_loop()
    errores = 0
    try:
        while True:
            t0 = loop.time()
            try:
                jpeg = await loop.run_in_executor(EJECUTOR_PANTALLA, capturar_jpeg)
                # Si la pantalla no cambio, no se gasta red mandando lo mismo.
                if jpeg != anterior:
                    await ws.send(jpeg)
                    anterior = jpeg
            except websockets.exceptions.ConnectionClosed:
                break
            except Exception as ex:
                errores += 1
                if errores == 1 or errores % 100 == 0:
                    print(f"[!] Error capturando pantalla: {ex}")
            await asyncio.sleep(max(0.008, objetivo - (loop.time() - t0)))
    except asyncio.CancelledError:
        pass


async def copiar_captura_al_portapapeles(png):
    if not shutil.which("xclip"):
        print("[!] Falta xclip para copiar la captura del celular: sudo apt install xclip")
        return
    rc = await ejecutar("xclip", "-selection", "clipboard", "-t", "image/png", "-i", entrada=png)
    if rc == 0:
        print("[+] Captura del celular copiada al portapapeles.")
    else:
        print("[!] No se pudo copiar la captura al portapapeles.")


# ============================================================ SERVIDOR

async def manejar_cliente(ws, path=None):
    print(f"[+] Cliente conectado: {ws.remote_address}")
    autenticado = False
    intentos_fallidos = 0
    tarea_pantalla = None

    try:
        async for mensaje in ws:
            # Mensaje BINARIO desde el celular = captura de pantalla del celular (PNG).
            if isinstance(mensaje, (bytes, bytearray)):
                if autenticado:
                    await copiar_captura_al_portapapeles(bytes(mensaje))
                continue

            try:
                msg = json.loads(mensaje)
            except ValueError:
                continue
            if not isinstance(msg, dict):
                continue
            tipo = msg.get("type")

            if not autenticado:
                pin_recibido = str(msg.get("pin", "")).encode()
                if tipo == "auth" and secrets.compare_digest(pin_recibido, PIN.encode()):
                    autenticado = True
                    print("[+] Cliente autenticado.")
                    AUTENTICADO_EVENTO.set()
                    await ws.send('{"type":"auth_ok"}')
                else:
                    intentos_fallidos += 1
                    await asyncio.sleep(1)   # frena la fuerza bruta (el PIN es de 4 digitos)
                    await ws.send('{"type":"auth_fail"}')
                    if intentos_fallidos >= 3:
                        print("[!] Demasiados PIN incorrectos: se cierra la conexion.")
                        await ws.close()
                        break
                continue

            try:
                if tipo == "key" and msg.get("key") is not None and msg.get("action") is not None:
                    await ENTRADA.tecla(str(msg["key"]), msg["action"] == "down")

                elif tipo == "edit":
                    await ENTRADA.edicion(int(msg.get("delete") or 0), str(msg.get("insert") or ""))

                elif tipo == "mouse_move":
                    ENTRADA.mover(int(msg.get("dx") or 0), int(msg.get("dy") or 0))

                elif tipo == "mouse_click" and msg.get("button") is not None and msg.get("action") is not None:
                    ENTRADA.click(str(msg["button"]), msg["action"] == "down")

                elif tipo == "combo" and msg.get("combo"):
                    await ENTRADA.combo(str(msg["combo"]))

                elif tipo == "scroll":
                    ENTRADA.scroll(int(msg.get("amount") or 0))

                elif tipo == "start_screen":
                    if tarea_pantalla:
                        tarea_pantalla.cancel()
                    print("[+] Empezando a compartir pantalla.")
                    tarea_pantalla = asyncio.create_task(transmitir_pantalla(ws))

                elif tipo == "stop_screen":
                    if tarea_pantalla:
                        tarea_pantalla.cancel()
                        tarea_pantalla = None
                    print("[-] Se corto la transmision de pantalla.")

                elif tipo == "brightness_up":
                    await ENTRADA.brillo(True)

                elif tipo == "brightness_down":
                    await ENTRADA.brillo(False)

            except websockets.exceptions.ConnectionClosed:
                raise
            except Exception as ex:
                print(f"[!] Error procesando '{tipo}': {ex}")

    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as ex:
        print(f"[!] Error con el cliente: {ex}")
    finally:
        if tarea_pantalla:
            tarea_pantalla.cancel()
        if ENTRADA:
            ENTRADA.soltar_todo()
        print("[-] Cliente desconectado.")


async def servidor():
    global ENTRADA
    ENTRADA = Entrada()     # la conexion X se crea y usa siempre desde este hilo
    # compression=None: JPEG/PNG ya vienen comprimidos, comprimirlos de nuevo solo gasta CPU.
    # max_size=None: la captura PNG del celular puede pasar de 1 MB.
    async with websockets.serve(manejar_cliente, "0.0.0.0", PUERTO,
                                max_size=None, compression=None):
        await asyncio.Future()


def correr_servidor():
    try:
        asyncio.run(servidor())
    except OSError as ex:
        print(f"\nError al iniciar el servidor en el puerto {PUERTO}: {ex}")
        print("Puede que ya haya otro agente corriendo.")
        os._exit(1)
    except Exception as ex:
        print(f"\nError al iniciar el agente: {ex}")
        os._exit(1)


# ============================================================ RED / QR

def obtener_ip_local():
    forzada = os.environ.get("TECLADO_IP")
    if forzada:
        return forzada
    # Conectar un socket UDP no manda nada: solo hace que el sistema elija la
    # interfaz de salida real (WiFi/Ethernet), ignorando adaptadores virtuales.
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def mostrar_qr(ip, pin):
    """Ventana con el QR. Se cierra sola cuando un celular se autentica."""
    contenido = f"tecladomovil://{ip}:{PUERTO}/{pin}"
    try:
        import qrcode
    except ImportError:
        print("(Instala 'qrcode' para ver el QR. Escribe IP, puerto y PIN a mano en la app.)")
        return False

    qr = qrcode.QRCode(border=2, box_size=10, error_correction=qrcode.constants.ERROR_CORRECT_M)
    qr.add_data(contenido)
    qr.make(fit=True)

    try:
        import tkinter as tk
        img = qr.make_image(fill_color="black", back_color="white")
        buf = io.BytesIO()
        img.save(buf, format="PNG")

        root = tk.Tk()
        root.title("Teclado Movil - Escanea este QR")
        root.configure(bg="white")
        root.resizable(False, False)
        foto = tk.PhotoImage(data=base64.b64encode(buf.getvalue()).decode())
        tk.Label(root, image=foto, bg="white").pack(padx=20, pady=(20, 0))
        tk.Label(root, text=f"IP: {ip}   Puerto: {PUERTO}   PIN: {pin}",
                 bg="white", font=("sans-serif", 11)).pack(pady=12)

        def revisar():
            if AUTENTICADO_EVENTO.is_set():
                root.destroy()
            else:
                root.after(300, revisar)

        root.after(300, revisar)
        root.mainloop()
        return True
    except Exception:
        # Sin tkinter (python3-tk) o sin pantalla: el QR va a la terminal.
        qr.print_ascii(invert=True)
        return False


# ============================================================ MAIN

def main():
    global PIN

    if not os.environ.get("DISPLAY"):
        print("ERROR: no hay entorno grafico (variable DISPLAY vacia). Ejecuta esto dentro de tu sesion.")
        sys.exit(1)
    if os.environ.get("XDG_SESSION_TYPE", "").lower() == "wayland":
        print("AVISO: estas en una sesion Wayland. El teclado/mouse simulados y la captura de")
        print("       pantalla pueden no funcionar. Cierra sesion y entra con 'Cinnamon' (X11).\n")

    PIN = f"{secrets.randbelow(10000):04d}"
    ip = obtener_ip_local()

    print("=== Teclado Movil - Agente de Linux ===\n")
    print(f"IP local: {ip or 'No encontrada (revisa que estes conectado a WiFi)'}")
    print(f"Puerto:   {PUERTO}")
    print(f"PIN:      {PIN}\n")

    for herramienta, para in (("xdotool", "escribir ñ, tildes y signos"),
                              ("xclip", "copiar la captura del celular al portapapeles")):
        if not shutil.which(herramienta):
            print(f"[!] Falta '{herramienta}' ({para}): sudo apt install {herramienta}")
    print()

    hilo = threading.Thread(target=correr_servidor, daemon=True)
    hilo.start()
    time.sleep(0.5)

    print("Esperando conexion del celular...")
    print("(Ctrl+C para salir)\n")

    try:
        if ip:
            print("Se abrio una ventana con el QR: escanealo desde la app (boton 'Escanear QR').\n")
            mostrar_qr(ip, PIN)
        while hilo.is_alive():
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nAgente detenido.")


if __name__ == "__main__":
    main()
