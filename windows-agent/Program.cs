using System.Drawing;
using System.Drawing.Imaging;
using System.Net;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;

namespace windows_agent;

internal class Program
{
    private static string _pin = "";

    private static async Task Main()
    {
        _pin = new Random().Next(0, 10000).ToString("D4");

        Console.WriteLine("=== Teclado Movil - Agente de Windows ===");
        Console.WriteLine();
        Console.WriteLine($"IP local: {GetLocalIPv4()}");
        Console.WriteLine($"Puerto:   8765");
        Console.WriteLine($"PIN:      {_pin}");
        Console.WriteLine();
        Console.WriteLine("Esperando conexion del celular...");
        Console.WriteLine("(Ctrl+C para salir)");
        Console.WriteLine();

        var listener = new HttpListener();
        listener.Prefixes.Add("http://+:8765/");

        try
        {
            listener.Start();
        }
        catch (HttpListenerException ex)
        {
            Console.WriteLine($"Error al iniciar el servidor: {ex.Message}");
            Console.WriteLine("Puede que necesites ejecutar esto como Administrador,");
            Console.WriteLine("o reservar el puerto con netsh (avisame y te paso el comando).");
            return;
        }

        while (true)
        {
            var context = await listener.GetContextAsync();
            if (context.Request.IsWebSocketRequest)
            {
                _ = HandleClientAsync(context);
            }
            else
            {
                context.Response.StatusCode = 400;
                context.Response.Close();
            }
        }
    }

    private static async Task HandleClientAsync(HttpListenerContext context)
    {
        WebSocket socket;
        try
        {
            var wsContext = await context.AcceptWebSocketAsync(null);
            socket = wsContext.WebSocket;
        }
        catch
        {
            context.Response.StatusCode = 500;
            context.Response.Close();
            return;
        }

        Console.WriteLine($"[+] Cliente conectado: {context.Request.RemoteEndPoint}");
        bool authenticated = false;
        var buffer = new byte[8192];

        // Controla el hilo que manda capturas de pantalla mientras el celular
        // este parado en la pestaña "Pantalla PC".
        CancellationTokenSource? screenCts = null;

        try
        {
            while (socket.State == WebSocketState.Open)
            {
                var result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);

                if (result.MessageType == WebSocketMessageType.Close)
                {
                    await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);
                    break;
                }

                var json = Encoding.UTF8.GetString(buffer, 0, result.Count);
                Msg? msg;
                try
                {
                    msg = JsonSerializer.Deserialize<Msg>(json);
                }
                catch
                {
                    continue;
                }

                if (msg is null) continue;

                if (!authenticated)
                {
                    if (msg.type == "auth" && msg.pin == _pin)
                    {
                        authenticated = true;
                        Console.WriteLine("[+] Cliente autenticado.");
                        await SendAsync(socket, "{\"type\":\"auth_ok\"}");
                    }
                    else
                    {
                        await SendAsync(socket, "{\"type\":\"auth_fail\"}");
                    }
                    continue;
                }

                switch (msg.type)
                {
                    case "key" when msg.key is not null && msg.action is not null:
                        KeySender.SendKey(msg.key, msg.action == "down");
                        break;

                    case "edit":
                        // Se procesa entero antes de leer el siguiente mensaje, para
                        // que las ediciones nunca se mezclen entre si.
                        await ProcesarEdicionAsync(msg.delete ?? 0, msg.insert ?? "");
                        break;

                    case "mouse_move" when msg.dx.HasValue && msg.dy.HasValue:
                        MouseSender.Mover(msg.dx.Value, msg.dy.Value);
                        break;

                    case "mouse_click" when msg.button is not null && msg.action is not null:
                        MouseSender.Click(msg.button, msg.action == "down");
                        break;

                    case "combo" when msg.combo is not null:
                        KeySender.SendCombo(msg.combo);
                        break;

                    case "scroll" when msg.amount.HasValue:
                        MouseSender.Scroll(msg.amount.Value);
                        break;

                    case "start_screen":
                        screenCts?.Cancel();
                        screenCts = new CancellationTokenSource();
                        Console.WriteLine("[+] Empezando a compartir pantalla.");
                        _ = ScreenStreamLoopAsync(socket, screenCts.Token);
                        break;

                    case "stop_screen":
                        screenCts?.Cancel();
                        screenCts = null;
                        Console.WriteLine("[-] Se corto la transmision de pantalla.");
                        break;
                }
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[!] Error con el cliente: {ex.Message}");
        }
        finally
        {
            screenCts?.Cancel();
            Console.WriteLine("[-] Cliente desconectado.");
            socket.Dispose();
        }
    }

    /// <summary>
    /// Manda capturas de pantalla (JPEG chico, en BINARIO -no texto/base64-
    /// para que pese menos y se decodifique mas rapido de los dos lados) una
    /// tras otra hasta que se cancele (el celular manda "stop_screen" o se
    /// corta la conexion). No es video real, son fotos seguidas, pero para
    /// ver la pantalla de la PC desde el celular alcanza y es mucho mas
    /// simple.
    /// </summary>
    private static async Task ScreenStreamLoopAsync(WebSocket socket, CancellationToken token)
    {
        // Ritmo objetivo del stream. Con el delay DINAMICO de abajo, esto ya
        // no es "cuanto esperar ademas de lo que tardo el frame", sino
        // "cuanto debe durar cada vuelta en total" (captura + envio + espera).
        const int targetIntervalMs = 66; // ~15 fps objetivo

        byte[]? frameAnterior = null;
        var cronometro = new System.Diagnostics.Stopwatch();

        while (!token.IsCancellationRequested && socket.State == WebSocketState.Open)
        {
            cronometro.Restart();

            try
            {
                // La captura via GDI es sincrona y consume CPU: se manda al
                // thread pool con Task.Run para no trabar este loop (y de
                // paso, para que en el futuro se pueda solapar con el envio
                // del frame anterior si hace falta exprimir mas).
                var jpegBytes = await Task.Run(() => ScreenCapturer.CapturarComoJpegBytes(), token);

                // Si el frame es practicamente identico al anterior (nada se
                // movio en pantalla: el usuario esta leyendo, no escribiendo),
                // no tiene sentido gastar ancho de banda mandando el mismo
                // JPEG de nuevo. Esto deja mas "presupuesto" de tiempo/red
                // libre para cuando si hay movimiento.
                if (!EsFrameIgual(frameAnterior, jpegBytes))
                {
                    await socket.SendAsync(
                        new ArraySegment<byte>(jpegBytes),
                        WebSocketMessageType.Binary,
                        true,
                        token);
                    frameAnterior = jpegBytes;
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[!] Error capturando pantalla: {ex.Message}");
            }

            try
            {
                // Delay DINAMICO: se descuenta lo que ya tardo capturar+
                // comprimir+mandar este frame, para apuntar a un ritmo
                // constante de ~targetIntervalMs por vuelta en vez de sumar
                // un delay fijo ARRIBA del tiempo que ya se gasto. Si un
                // frame tardo mas que el objetivo (ej. pantalla compleja),
                // se espera el minimo (8ms) en vez de negativo.
                var transcurrido = (int)cronometro.ElapsedMilliseconds;
                var espera = Math.Max(8, targetIntervalMs - transcurrido);
                await Task.Delay(espera, token);
            }
            catch (TaskCanceledException)
            {
                break;
            }
        }
    }

    /// <summary>
    /// Compara dos frames JPEG byte a byte para detectar si la pantalla no
    /// cambio desde el frame anterior. Es una comparacion barata comparada
    /// con el costo de capturar+comprimir+mandar por red, asi que vale la
    /// pena hacerla siempre antes de decidir si se manda el frame o no.
    /// </summary>
    private static bool EsFrameIgual(byte[]? anterior, byte[] actual)
    {
        if (anterior is null || anterior.Length != actual.Length) return false;
        return anterior.AsSpan().SequenceEqual(actual);
    }

    /// <summary>
    /// Aplica una edicion completa (borrar N caracteres al final + escribir un
    /// texto nuevo) con una pequenia pausa entre cada tecla, para que Windows
    /// no pierda ninguna cuando llegan muchas de golpe (por ej. autocorreccion).
    /// </summary>
    private static async Task ProcesarEdicionAsync(int borrar, string agregar)
    {
        for (int i = 0; i < borrar; i++)
        {
            KeySender.SendKey("backspace", true);
            KeySender.SendKey("backspace", false);
            await Task.Delay(8);
        }

        foreach (var c in agregar)
        {
            if (c == '\n') continue; // el enter se maneja como tecla aparte
            var keyValue = c == ' ' ? "space" : c.ToString();
            KeySender.SendKey(keyValue, true);
            KeySender.SendKey(keyValue, false);
            await Task.Delay(8);
        }
    }

    private static async Task SendAsync(WebSocket socket, string json)
    {
        var bytes = Encoding.UTF8.GetBytes(json);
        await socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None);
    }

    private static string GetLocalIPv4()
    {
        foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;

            foreach (var addr in ni.GetIPProperties().UnicastAddresses)
            {
                if (addr.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                {
                    return addr.Address.ToString();
                }
            }
        }
        return "No encontrada (revisa que estes conectado a WiFi)";
    }
}

internal class Msg
{
    public string? type { get; set; }
    public string? pin { get; set; }
    public string? key { get; set; }
    public string? action { get; set; }
    public int? delete { get; set; }
    public string? insert { get; set; }
    public int? dx { get; set; }
    public int? dy { get; set; }
    public string? button { get; set; }
    public int? amount { get; set; }
    public string? combo { get; set; }
}

/// <summary>
/// Captura la pantalla principal YA REDUCIDA de entrada (via StretchBlt, sin
/// pasar por una imagen a resolucion completa) y la comprime como JPEG,
/// devolviendo los bytes crudos, listos para mandar por el WebSocket como
/// binario.
///
/// Antes esto capturaba la pantalla ENTERA a resolucion real (por ej.
/// 1920x1080), reservaba esa imagen completa en memoria, y recien despues la
/// achicaba a 480px de ancho. Con monitores grandes eso es carísimo: se
/// reserva y procesa una imagen enorme en cada vuelta para tirar casi todo
/// después. StretchBlt (una funcion nativa de Windows) hace la reduccion de
/// tamaño DURANTE la copia, asi que nunca existe la version a resolucion
/// completa - se ahorra memoria y tiempo de CPU en cada frame.
/// </summary>
internal static class ScreenCapturer
{
    [DllImport("user32.dll")]
    private static extern bool GetCursorInfo(ref CURSORINFO pci);

    [DllImport("user32.dll")]
    private static extern bool GetIconInfo(IntPtr hIcon, out ICONINFO piconinfo);

    [DllImport("user32.dll")]
    private static extern bool DrawIconEx(
        IntPtr hdc, int xLeft, int yTop, IntPtr hIcon,
        int cxWidth, int cyWidth, uint istepIfAniCur,
        IntPtr hbrFlickerFreeDraw, uint diFlags);

    [DllImport("user32.dll")]
    private static extern IntPtr GetDC(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr hObject);

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateCompatibleDC(IntPtr hdc);

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateCompatibleBitmap(IntPtr hdc, int width, int height);

    [DllImport("gdi32.dll")]
    private static extern IntPtr SelectObject(IntPtr hdc, IntPtr hgdiobj);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteDC(IntPtr hdc);

    [DllImport("gdi32.dll")]
    private static extern bool StretchBlt(
        IntPtr hdcDest, int xDest, int yDest, int wDest, int hDest,
        IntPtr hdcSrc, int xSrc, int ySrc, int wSrc, int hSrc, int rop);

    [DllImport("gdi32.dll")]
    private static extern int SetStretchBltMode(IntPtr hdc, int mode);

    [StructLayout(LayoutKind.Sequential)]
    private struct CURSORINFO
    {
        public int cbSize;
        public int flags;
        public IntPtr hCursor;
        public POINT ptScreenPos;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int x;
        public int y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ICONINFO
    {
        public bool fIcon;
        public int xHotspot;
        public int yHotspot;
        public IntPtr hbmMask;
        public IntPtr hbmColor;
    }

    private const int CURSOR_SHOWING = 0x0001;
    private const uint DI_NORMAL = 0x0003;
    private const int SRCCOPY = 0x00CC0020;
    private const int HALFTONE = 4;
    private const int SM_CXCURSOR = 13;
    private const int SM_CYCURSOR = 14;

    // Se subio de 480px/calidad 45 a 720px/calidad 70 para que se vea mas
    // nitido (se notaba borroso). El frame skipping en ScreenStreamLoopAsync
    // compensa el frame mas pesado: cuando la pantalla esta quieta no se
    // manda nada, asi que el costo extra solo se paga cuando realmente hay
    // movimiento en pantalla.
    public static byte[] CapturarComoJpegBytes(int anchoDestino = 600, long calidad = 68L)
    {
        var bounds = Screen.PrimaryScreen!.Bounds;
        var altoDestino = Math.Max(1, (int)(anchoDestino * ((double)bounds.Height / bounds.Width)));
        var escala = anchoDestino / (double)bounds.Width;

        var hdcScreen = GetDC(IntPtr.Zero);
        var hdcMem = CreateCompatibleDC(hdcScreen);
        var hBitmap = CreateCompatibleBitmap(hdcScreen, anchoDestino, altoDestino);
        var hAnterior = SelectObject(hdcMem, hBitmap);

        try
        {
            // HALFTONE da mejor calidad al achicar que el modo por defecto
            // (que directamente descarta pixeles y se ve mas "dentado").
            SetStretchBltMode(hdcMem, HALFTONE);
            StretchBlt(
                hdcMem, 0, 0, anchoDestino, altoDestino,
                hdcScreen, bounds.Left, bounds.Top, bounds.Width, bounds.Height,
                SRCCOPY);

            // CopyFromScreen/StretchBlt no incluyen el cursor (es una
            // limitacion de Windows): hay que dibujarlo aparte, ya escalado
            // al mismo tamaño reducido que el resto de la imagen.
            DibujarCursorEscalado(hdcMem, bounds, escala);

            using var bitmap = Image.FromHbitmap(hBitmap);
            using var ms = new MemoryStream();
            var codecInfo = ImageCodecInfo.GetImageEncoders().First(e => e.FormatID == ImageFormat.Jpeg.Guid);
            var parametros = new EncoderParameters(1);
            // Se usa el nombre completo (System.Drawing.Imaging.Encoder)
            // porque "Encoder" a secas es ambiguo con System.Text.Encoder
            // (por el using System.Text de arriba, que se necesita para otra cosa).
            parametros.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, calidad);
            bitmap.Save(ms, codecInfo, parametros);

            return ms.ToArray();
        }
        finally
        {
            SelectObject(hdcMem, hAnterior);
            DeleteObject(hBitmap);
            DeleteDC(hdcMem);
            ReleaseDC(IntPtr.Zero, hdcScreen);
        }
    }

    /// <summary>
    /// Igual que antes (dibuja el cursor real, en su posicion, respetando el
    /// hotspot), pero ahora todo escalado al tamaño reducido de la captura:
    /// la posicion, el hotspot Y el tamaño del icono se multiplican por
    /// "escala" para que el cursor se vea proporcional al resto de la imagen
    /// (si no, se veria gigante comparado con un escritorio ya achicado).
    /// </summary>
    private static void DibujarCursorEscalado(IntPtr hdcDestino, Rectangle bounds, double escala)
    {
        var info = new CURSORINFO { cbSize = Marshal.SizeOf(typeof(CURSORINFO)) };
        if (!GetCursorInfo(ref info) || info.flags != CURSOR_SHOWING) return;

        var hotspotX = 0;
        var hotspotY = 0;
        if (GetIconInfo(info.hCursor, out var iconInfo))
        {
            hotspotX = iconInfo.xHotspot;
            hotspotY = iconInfo.yHotspot;
            if (iconInfo.hbmMask != IntPtr.Zero) DeleteObject(iconInfo.hbmMask);
            if (iconInfo.hbmColor != IntPtr.Zero) DeleteObject(iconInfo.hbmColor);
        }

        var cursorAncho = Math.Max(1, (int)(GetSystemMetrics(SM_CXCURSOR) * escala));
        var cursorAlto = Math.Max(1, (int)(GetSystemMetrics(SM_CYCURSOR) * escala));

        var x = (int)((info.ptScreenPos.x - bounds.Left) * escala) - (int)(hotspotX * escala);
        var y = (int)((info.ptScreenPos.y - bounds.Top) * escala) - (int)(hotspotY * escala);

        // cxWidth/cyWidth ya NO van en 0 (tamaño real) como antes: hay que
        // pedirle a Windows que dibuje el icono directamente al tamaño
        // escalado, porque estamos dibujando sobre el bitmap chico.
        DrawIconEx(hdcDestino, x, y, info.hCursor, cursorAncho, cursorAlto, 0, IntPtr.Zero, DI_NORMAL);
    }
}

/// <summary>
/// Structs y P/Invoke de bajo nivel para SendInput, compartidos entre el
/// teclado simulado (KeySender) y el mouse simulado (MouseSender).
/// </summary>
internal static class Win32Input
{
    [DllImport("user32.dll", SetLastError = true)]
    public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT
    {
        public uint type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    public struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    public const uint INPUT_MOUSE = 0;
    public const uint INPUT_KEYBOARD = 1;

    public static void Enviar(INPUT input, string etiqueta)
    {
        var sent = SendInput(1, new[] { input }, Marshal.SizeOf(typeof(INPUT)));
        if (sent == 0)
        {
            var err = Marshal.GetLastWin32Error();
            Console.WriteLine($"[!] SendInput fallo para '{etiqueta}'. Error de Windows: {err}");
        }
    }
}

internal static class KeySender
{
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;

    private static readonly Dictionary<string, ushort> KeyMap = BuildKeyMap();

    private static Dictionary<string, ushort> BuildKeyMap()
    {
        var map = new Dictionary<string, ushort>();

        for (char c = 'a'; c <= 'z'; c++)
            map[c.ToString()] = (ushort)char.ToUpper(c);

        for (char c = '0'; c <= '9'; c++)
            map[c.ToString()] = (ushort)c;

        map["space"] = 0x20;
        map["enter"] = 0x0D;
        map["backspace"] = 0x08;
        map["tab"] = 0x09;
        map["escape"] = 0x1B;
        map["shift"] = 0x10;
        map["ctrl"] = 0x11;
        map["alt"] = 0x12;
        map["delete"] = 0x2E;
        map["arrowup"] = 0x26;
        map["arrowdown"] = 0x28;
        map["arrowleft"] = 0x25;
        map["arrowright"] = 0x27;

        // Teclas "de PC" que no existen en un teclado de celular
        map["win"] = 0x5B;
        map["capslock"] = 0x14;
        map["home"] = 0x24;
        map["end"] = 0x23;
        map["pageup"] = 0x21;
        map["pagedown"] = 0x22;
        map["insert"] = 0x2D;

        for (int i = 1; i <= 12; i++)
            map[$"f{i}"] = (ushort)(0x70 + (i - 1));

        return map;
    }

    public static void SendKey(string key, bool isDown)
    {
        var lookup = key.ToLowerInvariant();

        // Teclas especiales / letras y numeros basicos: se mandan por codigo
        // de tecla virtual (funciona bien y es compatible con todo).
        if (KeyMap.TryGetValue(lookup, out var vk))
        {
            EnviarPorVirtualKey(vk, isDown);
            return;
        }

        // Cualquier otro caracter suelto (tildes, ñ, signos de puntuacion, etc.)
        // se manda como caracter Unicode directo. Esto funciona sin importar
        // el idioma/distribucion de teclado configurado en Windows.
        if (key.Length == 1)
        {
            EnviarPorUnicode(key[0], isDown);
            return;
        }

        Console.WriteLine($"[!] Tecla no reconocida: {key}");
    }

    /// <summary>
    /// Manda un combo tipo "ctrl+c": presiona todas las teclas en orden
    /// (ctrl, luego c) y las suelta en orden INVERSO (c, luego ctrl), que es
    /// como espera Windows que se manden los atajos de teclado.
    /// </summary>
    public static void SendCombo(string combo)
    {
        var teclas = combo.Split('+');
        foreach (var t in teclas)
            SendKey(t, true);
        for (int i = teclas.Length - 1; i >= 0; i--)
            SendKey(teclas[i], false);
    }

    private static void EnviarPorVirtualKey(ushort vk, bool isDown)
    {
        var input = new Win32Input.INPUT
        {
            type = Win32Input.INPUT_KEYBOARD,
            U = new Win32Input.InputUnion
            {
                ki = new Win32Input.KEYBDINPUT
                {
                    wVk = vk,
                    wScan = 0,
                    dwFlags = isDown ? 0 : KEYEVENTF_KEYUP,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };
        Win32Input.Enviar(input, vk.ToString());
    }

    private static void EnviarPorUnicode(char c, bool isDown)
    {
        var input = new Win32Input.INPUT
        {
            type = Win32Input.INPUT_KEYBOARD,
            U = new Win32Input.InputUnion
            {
                ki = new Win32Input.KEYBDINPUT
                {
                    wVk = 0,
                    wScan = c,
                    dwFlags = (isDown ? 0u : KEYEVENTF_KEYUP) | KEYEVENTF_UNICODE,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };
        Win32Input.Enviar(input, c.ToString());
    }
}

internal static class MouseSender
{
    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;

    public static void Mover(int dx, int dy)
    {
        var input = new Win32Input.INPUT
        {
            type = Win32Input.INPUT_MOUSE,
            U = new Win32Input.InputUnion
            {
                mi = new Win32Input.MOUSEINPUT
                {
                    dx = dx,
                    dy = dy,
                    mouseData = 0,
                    dwFlags = MOUSEEVENTF_MOVE,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };
        Win32Input.Enviar(input, "mouse_move");
    }

    public static void Click(string boton, bool isDown)
    {
        uint flag = boton == "right"
            ? (isDown ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP)
            : (isDown ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP);

        var input = new Win32Input.INPUT
        {
            type = Win32Input.INPUT_MOUSE,
            U = new Win32Input.InputUnion
            {
                mi = new Win32Input.MOUSEINPUT
                {
                    dx = 0,
                    dy = 0,
                    mouseData = 0,
                    dwFlags = flag,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };
        Win32Input.Enviar(input, $"mouse_{boton}_{(isDown ? "down" : "up")}");
    }

    /// <summary>
    /// Simula la rueda del mouse. "amount" viene en unidades de WHEEL_DELTA
    /// (120 = un "click" de rueda hacia arriba, -120 hacia abajo). mouseData
    /// es un campo con signo a nivel de Windows, pero la struct lo declara
    /// como uint, asi que se castea sin problema (mismo patron que MOUSEEVENTF).
    /// </summary>
    public static void Scroll(int amount)
    {
        var input = new Win32Input.INPUT
        {
            type = Win32Input.INPUT_MOUSE,
            U = new Win32Input.InputUnion
            {
                mi = new Win32Input.MOUSEINPUT
                {
                    dx = 0,
                    dy = 0,
                    mouseData = unchecked((uint)amount),
                    dwFlags = MOUSEEVENTF_WHEEL,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };
        Win32Input.Enviar(input, "scroll");
    }
}
