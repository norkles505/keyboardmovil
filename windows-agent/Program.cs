using System.Drawing;
using System.Drawing.Imaging;
using System.Management;
using System.Net;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;
using QRCoder;

namespace windows_agent;

internal class Program
{
    private static string _pin = "";

    // Referencia a la ventana del QR para poder cerrarla automaticamente
    // apenas un cliente se autentica (ver CerrarVentanaQrSiEstaAbierta).
    // La asigna el hilo STA que crea la ventana (MostrarQrConexion) y la
    // usa el hilo que atiende al cliente WebSocket (HandleClientAsync).
    private static Form? _formQr;

    private static async Task Main()
    {
        _pin = new Random().Next(0, 10000).ToString("D4");
        var ipLocal = GetLocalIPv4();
        const string puerto = "8765";

        Console.WriteLine("=== Teclado Movil - Agente de Windows ===");
        Console.WriteLine();
        Console.WriteLine($"IP local: {ipLocal}");
        Console.WriteLine($"Puerto:   {puerto}");
        Console.WriteLine($"PIN:      {_pin}");
        Console.WriteLine();

        // En vez de tipear IP+puerto+PIN a mano en el celular, se puede
        // escanear este QR desde el boton "Escanear QR" de la pantalla de
        // conexion de la app: trae los 3 datos juntos y conecta solo.
        //
        // Se muestra en una VENTANA con una imagen real, no como texto
        // ASCII en la consola: el QR de consola quedaba poco nitido (la
        // fuente de la terminal no es perfectamente cuadrada y el contraste
        // es bajo), lo que hacia que la camara del celular no lo detectara.
        // Una imagen real, en blanco y negro solido y a buen tamanio, es
        // mucho mas confiable de escanear.
        if (ipLocal.StartsWith("No encontrada"))
        {
            Console.WriteLine("No se pudo generar el QR: no se encontro una IP local valida.");
        }
        else
        {
            Console.WriteLine("Se abrio una ventana con el QR: escanealo desde la app (boton 'Escanear QR').");
            Console.WriteLine("(La ventana se cierra sola apenas el celular se conecta; tambien la podes cerrar vos a mano.)");
            Console.WriteLine();
            var hiloVentanaQr = new Thread(() => MostrarQrConexion(ipLocal, puerto, _pin));
            hiloVentanaQr.SetApartmentState(ApartmentState.STA);
            hiloVentanaQr.IsBackground = true;
            hiloVentanaQr.Start();
        }

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

    /// <summary>
    /// Genera un QR con el formato "tecladomovil://IP:PUERTO/PIN" y lo
    /// muestra en una VENTANA como imagen real (bitmap en blanco y negro
    /// solido), para que la camara del celular lo pueda leer bien. Corre en
    /// su propio hilo STA (requisito de WinForms) y bloquea ese hilo con
    /// Application.Run hasta que el usuario cierre la ventana (o hasta que
    /// se cierre sola al autenticarse un cliente); el agente en si sigue
    /// corriendo normalmente en el hilo principal mientras tanto.
    /// </summary>
    private static void MostrarQrConexion(string ip, string puerto, string pin)
    {
        var contenido = $"tecladomovil://{ip}:{puerto}/{pin}";
        using var generador = new QRCodeGenerator();
        using var datosQr = generador.CreateQrCode(contenido, QRCodeGenerator.ECCLevel.M);
        using var qrGrafico = new QRCode(datosQr);
        // pixelsPerModule alto (16) para que el QR se vea grande y nitido:
        // cuanto mas grande cada "cuadrito" del QR, mas facil de enfocar y
        // decodificar para la camara del celular.
        using var bitmap = qrGrafico.GetGraphic(16, Color.Black, Color.White, true);

        using var form = new Form
        {
            Text = "Teclado Movil - Escanea este QR",
            StartPosition = FormStartPosition.CenterScreen,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            ClientSize = new Size(bitmap.Width + 40, bitmap.Height + 80),
            BackColor = Color.White
        };

        var pictureBox = new PictureBox
        {
            Image = bitmap,
            SizeMode = PictureBoxSizeMode.CenterImage,
            Dock = DockStyle.Top,
            Height = bitmap.Height + 20
        };
        form.Controls.Add(pictureBox);

        var etiqueta = new Label
        {
            Text = $"IP: {ip}   Puerto: {puerto}   PIN: {pin}",
            Dock = DockStyle.Bottom,
            TextAlign = ContentAlignment.MiddleCenter,
            Height = 40
        };
        form.Controls.Add(etiqueta);

        // Al cerrarse (sola o a mano), se limpia la referencia para que
        // CerrarVentanaQrSiEstaAbierta() no intente usar una ventana ya
        // destruida si se llama de nuevo mas tarde.
        form.FormClosed += (_, _) => _formQr = null;

        _formQr = form;
        Application.Run(form);
    }

    /// <summary>
    /// Cierra la ventana del QR si todavia esta abierta. Se llama apenas un
    /// cliente termina de autenticarse (ver HandleClientAsync), que es la
    /// señal de que ya escaneo el QR y conecto con exito: a partir de ahi la
    /// ventana ya cumplio su proposito y solo estorba en pantalla.
    ///
    /// La ventana vive en su propio hilo STA (ver MostrarQrConexion), asi
    /// que no se puede tocar directo desde este hilo: hay que usar
    /// Invoke/BeginInvoke para que el cierre se ejecute en el hilo dueño de
    /// la ventana, como exige WinForms.
    /// </summary>
    private static void CerrarVentanaQrSiEstaAbierta()
    {
        var form = _formQr;
        if (form is null || form.IsDisposed) return;

        try
        {
            if (form.InvokeRequired)
                form.BeginInvoke(new Action(form.Close));
            else
                form.Close();
        }
        catch (ObjectDisposedException)
        {
            // La ventana se cerro (por el usuario) justo en el medio: no pasa nada.
        }
        catch (InvalidOperationException)
        {
            // El handle de la ventana ya no es valido (se estaba cerrando): no pasa nada.
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
                // Se acumulan los fragmentos hasta juntar el mensaje COMPLETO
                // (result.EndOfMessage == true). Con un solo ReceiveAsync y
                // el buffer fijo de 8192 bytes de antes, un mensaje mas
                // grande que eso (como una captura de pantalla del celular)
                // se cortaba a la mitad; el JSON de los mensajes de control
                // siempre entraba en un solo pedazo, asi que antes no se notaba.
                using var mensajeCompleto = new MemoryStream();
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);

                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);
                        break;
                    }

                    mensajeCompleto.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);

                if (result.MessageType == WebSocketMessageType.Close) break;

                // Mensaje BINARIO desde el celular: es una captura de
                // pantalla del celular (PNG), lo unico que el celular manda
                // en binario. Se copia directo al portapapeles de Windows.
                if (result.MessageType == WebSocketMessageType.Binary)
                {
                    if (authenticated) CopiarCapturaMovilAlPortapapeles(mensajeCompleto.ToArray());
                    continue;
                }

                var json = Encoding.UTF8.GetString(mensajeCompleto.ToArray());
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
                        // El celular ya escaneo el QR y conecto con exito:
                        // la ventana del QR ya cumplio su proposito.
                        CerrarVentanaQrSiEstaAbierta();
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

                    case "brightness_down":
                        BrightnessController.Bajar();
                        break;

                    case "brightness_up":
                        BrightnessController.Subir();
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

    /// <summary>
    /// Recibe el PNG con la captura de pantalla del CELULAR y lo copia al
    /// portapapeles de Windows (Ctrl+V en cualquier app lo pega como
    /// imagen). Clipboard.SetImage necesita el hilo en modo STA
    /// (Single-Threaded Apartment), que es un requisito del portapapeles de
    /// Windows en si (nada que ver con async/await); como el hilo que llega
    /// hasta aca no es STA, se hace la llamada en un hilo nuevo dedicado,
    /// creado especialmente en ese modo, y se espera (Join) a que termine.
    /// </summary>
    private static void CopiarCapturaMovilAlPortapapeles(byte[] pngBytes)
    {
        var hilo = new Thread(() =>
        {
            try
            {
                using var ms = new MemoryStream(pngBytes);
                using var bitmap = new Bitmap(ms);
                Clipboard.SetImage(bitmap);
                Console.WriteLine("[+] Captura del celular copiada al portapapeles.");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[!] No se pudo copiar la captura al portapapeles: {ex.Message}");
            }
        });
        hilo.SetApartmentState(ApartmentState.STA);
        hilo.Start();
        hilo.Join();
    }

    private static async Task SendAsync(WebSocket socket, string json)
    {
        var bytes = Encoding.UTF8.GetBytes(json);
        await socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None);
    }

    private static string GetLocalIPv4()
    {
        // Se prioriza WiFi y Ethernet reales, y se descartan interfaces
        // VIRTUALES (VirtualBox Host-Only, Hyper-V, VPN, VMware, etc.).
        // Antes se tomaba la primera interfaz "Up" que apareciera, y en PCs
        // con VirtualBox/Hyper-V instalado eso agarraba el adaptador
        // virtual (tipico rango 192.168.56.x de VirtualBox) en vez del WiFi
        // real, dejando al celular intentando conectar a una IP en una red
        // totalmente distinta a la suya (por eso el timeout de conexion).
        var interfaces = System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()
            .Where(ni => ni.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up)
            .Where(ni => ni.NetworkInterfaceType != System.Net.NetworkInformation.NetworkInterfaceType.Loopback)
            .Where(ni => !EsInterfazVirtual(ni.Description) && !EsInterfazVirtual(ni.Name))
            .OrderBy(ni => ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Wireless80211 ? 0
                         : ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Ethernet ? 1
                         : 2);

        foreach (var ni in interfaces)
        {
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

    /// <summary>
    /// Detecta adaptadores de red VIRTUALES por palabras clave conocidas en
    /// su nombre/descripcion (VirtualBox, Hyper-V, VMware, VPN...). Estos
    /// adaptadores estan "Up" igual que el WiFi real, pero no sirven para
    /// que el celular se conecte porque viven en una subred aislada de la PC.
    /// </summary>
    private static bool EsInterfazVirtual(string texto)
    {
        var t = texto.ToLowerInvariant();
        string[] palabrasClave =
        {
            "virtualbox", "vbox", "hyper-v", "hyperv", "vmware",
            "virtual", "vpn", "loopback", "tap-", "tun", "wsl"
        };
        return palabrasClave.Any(t.Contains);
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

    private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
    private const uint KEYEVENTF_SCANCODE = 0x0008;

    // MapVirtualKey con MAPVK_VK_TO_VSC (0) convierte un virtual-key a su
    // scan code segun la distribucion de teclado activa. Programas como
    // Blender (que leen el teclado a bajo nivel) identifican las teclas,
    // sobre todo Shift/Ctrl/Alt, por el scan code: si llega en 0 los ignoran.
    [DllImport("user32.dll")]
    private static extern uint MapVirtualKey(uint uCode, uint uMapType);

    // Teclas multimedia: no tienen un scan code de teclado normal, asi que
    // se siguen mandando solo por virtual-key como antes.
    private static readonly HashSet<ushort> TeclasMultimedia = new()
    {
        0xAD, 0xAE, 0xAF, 0xB0, 0xB1, 0xB3
    };

    // Virtual-key codes que Windows espera recibir marcados como "extended
    // key" (flag KEYEVENTF_EXTENDEDKEY) en SendInput. Sin este flag, Windows
    // puede interpretar la tecla de forma ambigua (como si viniera del
    // bloque numerico en vez del bloque de navegacion), y algunas apps
    // -sobre todo editores de texto que dependen de esto para gestos como
    // Shift+Flecha para seleccionar texto- terminan ignorando el combo o
    // comportandose distinto a lo esperado. Son las mismas teclas que en un
    // teclado fisico real viven "duplicadas" fuera del bloque numerico:
    // flechas, Home/End, Insert/Delete, Page Up/Down.
    private static readonly HashSet<ushort> ExtendedKeys = new()
    {
        0x26, 0x28, 0x25, 0x27, // arriba, abajo, izquierda, derecha
        0x24, 0x23,             // home, end
        0x21, 0x22,             // pageup, pagedown
        0x2D, 0x2E              // insert, delete
    };

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
        map["shift"] = 0xA0; // VK_LSHIFT
        map["ctrl"] = 0xA2;  // VK_LCONTROL
        map["alt"] = 0xA4;   // VK_LMENU
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

        // Multimedia: volumen (subir/bajar/silenciar) y control de
        // reproduccion (anterior/siguiente/play-pausa), con sus virtual-key
        // codes dedicados de Windows. A diferencia del brillo (que depende
        // de WMI y no siempre funciona en monitores externos), estas son
        // teclas normales via SendInput: no dependen del driver del
        // monitor/reproductor, funcionan en cualquier PC con cualquier app
        // que escuche estas teclas (Spotify, YouTube, reproductor nativo, etc).
        map["volumemute"] = 0xAD;
        map["volumedown"] = 0xAE;
        map["volumeup"] = 0xAF;
        map["mediaprevioustrack"] = 0xB1;
        map["medianexttrack"] = 0xB0;
        map["mediaplaypause"] = 0xB3;

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
            { SendKey(t, true); Thread.Sleep(15); }
        for (int i = teclas.Length - 1; i >= 0; i--)
            { SendKey(teclas[i], false); Thread.Sleep(15); }
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
                    wScan = (ushort)MapVirtualKey(vk, 0),
                    dwFlags = ArmarFlagsVirtualKey(vk, isDown),
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };
        Win32Input.Enviar(input, vk.ToString());
    }

    private static uint ArmarFlagsVirtualKey(ushort vk, bool isDown)
    {
        var flags = isDown ? 0u : KEYEVENTF_KEYUP;
        if (ExtendedKeys.Contains(vk))
            flags |= KEYEVENTF_EXTENDEDKEY;
        // Las teclas normales (letras, modificadores, flechas, etc.) se
        // mandan con scan code, como un teclado fisico. Las multimedia no.
        if (!TeclasMultimedia.Contains(vk) && MapVirtualKey(vk, 0) != 0)
            flags |= KEYEVENTF_SCANCODE;
        return flags;
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
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
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
        uint flag = boton == "middle"
            ? (isDown ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP)
            : boton == "right"
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

/// <summary>
/// Ajusta el brillo de la pantalla via WMI (namespace root\WMI, clases
/// WmiMonitorBrightness / WmiMonitorBrightnessMethods). Esto es lo mismo que
/// usa el control deslizante de brillo de Windows, asi que funciona bien en
/// laptops. OJO: muchos monitores externos de PC de escritorio NO exponen
/// brillo por WMI (dependen del driver/firmware del monitor), en ese caso
/// esto no va a tener efecto y quedaria pendiente resolverlo por DDC/CI,
/// que es mas complejo (requiere hablarle al monitor por el mismo cable de
/// video en vez de a traves de Windows).
/// </summary>
internal static class BrightnessController
{
    private const int PasoPorDefecto = 10;

    public static void Subir(int paso = PasoPorDefecto) => Ajustar(paso);
    public static void Bajar(int paso = PasoPorDefecto) => Ajustar(-paso);

    private static void Ajustar(int delta)
    {
        try
        {
            byte actual = LeerBrilloActual();
            var nuevo = (byte)Math.Clamp(actual + delta, 0, 100);
            AplicarBrillo(nuevo);
            Console.WriteLine($"[+] Brillo ajustado a {nuevo}%.");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[!] No se pudo ajustar el brillo (el monitor puede no soportarlo via WMI): {ex.Message}");
        }
    }

    private static byte LeerBrilloActual()
    {
        using var buscador = new ManagementObjectSearcher("root\\WMI", "SELECT * FROM WmiMonitorBrightness");
        using var coleccion = buscador.Get();
        foreach (ManagementObject monitor in coleccion)
        {
            return (byte)monitor["CurrentBrightness"];
        }
        return 50; // no se encontro ningun monitor compatible: valor neutro por defecto
    }

    private static void AplicarBrillo(byte nuevo)
    {
        using var buscadorMetodos = new ManagementObjectSearcher("root\\WMI", "SELECT * FROM WmiMonitorBrightnessMethods");
        using var coleccionMetodos = buscadorMetodos.Get();
        foreach (ManagementObject metodo in coleccionMetodos)
        {
            // El primer parametro (Timeout) va en 1 segundo; el segundo es
            // el nuevo nivel de brillo (0-100).
            metodo.InvokeMethod("WmiSetBrightness", new object[] { 1, nuevo });
        }
    }
}
