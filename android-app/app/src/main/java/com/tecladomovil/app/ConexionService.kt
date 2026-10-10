package com.tecladomovil.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Mantiene la conexion WebSocket con el agente de Windows viva en un
 * servicio en primer plano (con notificacion fija), para que Android no la
 * corte cuando la app se va a segundo plano. Solo se corta si el usuario
 * cierra la app por completo (la saca de la lista de recientes) o aprieta
 * "Desconectar".
 */
class ConexionService : Service() {

    companion object {
        private const val CHANNEL_ID = "teclado_movil_conexion"
        private const val NOTIF_ID = 1
    }

    interface ConexionListener {
        fun onConectando() {}
        fun onAutenticado() {}
        fun onAuthFallo() {}
        fun onDesconectado(motivo: String) {}
        // Los frames de pantalla llegan como mensajes BINARIOS (no texto/JSON
        // con base64 como antes), asi pesan menos y se procesan mas rapido.
        fun onFrameDePantalla(jpegBytes: ByteArray) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): ConexionService = this@ConexionService
    }

    private val binder = LocalBinder()
    // pingInterval manda un "ping" de WebSocket cada 20s aunque no haya
    // nada para transmitir. Esto cumple dos funciones: mantiene abiertas
    // las conexiones NAT/router (muchos cortan sockets inactivos despues de
    // un rato sin trafico) y hace que OkHttp detecte un corte de verdad en
    // segundos en vez de recien darse cuenta cuando se intenta mandar algo.
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null

    // Mientras estamos conectados, evita que Android apague el radio WiFi
    // para ahorrar bateria cuando la pantalla se apaga o la app pasa a
    // segundo plano (la causa mas comun de que la conexion se corte sola).
    private var wifiLock: WifiManager.WifiLock? = null

    var connected = false
        private set
    var listener: ConexionListener? = null

    // --- Captura de UNICO frame de la pantalla del celular (MediaProjection) ---
    // Se manda al agente de Windows para que lo copie al portapapeles de la
    // PC. Estas referencias se guardan aca (no son locales de la funcion)
    // porque la captura es asincrona (llega en un callback de ImageReader) y
    // hay que poder liberarlas todas una vez que se obtuvo el frame.
    private var proyeccionPantallaMovil: MediaProjection? = null
    private var virtualDisplayCapturaMovil: VirtualDisplay? = null
    private var imageReaderCapturaMovil: ImageReader? = null
    private val capturaMovilYaObtenida = AtomicBoolean(false)

    // --- Boton flotante para disparar la captura de pantalla del celular ---
    // Se muestra POR ENCIMA de cualquier otra app (con Teclado Movil en
    // segundo plano) para que el usuario pueda navegar a lo que quiere
    // capturar ANTES de sacar la foto. Antes, la captura se disparaba en el
    // mismo instante de tocar la tecla F3,C7, cuando la app de Teclado Movil
    // todavia estaba en primer plano: el resultado siempre era una captura
    // de la propia app, nunca de lo que el usuario realmente queria.
    private var windowManager: WindowManager? = null
    private var botonFlotante: View? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
        // Arranca SOLO con tipo dataSync. El tipo mediaProjection (tambien
        // declarado en el manifest) no se pide aca: Android 14+ exige que el
        // permiso de captura de pantalla ya este otorgado ANTES de que el
        // servicio se declare de tipo mediaProjection, y eso recien pasa
        // cuando el usuario toca "Captura" (ver iniciarCapturaPantallaMovil).
        // Pedirlo aca, apenas se abre la app, tira SecurityException y cierra
        // la app en el acto.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            construirNotificacion("Sin conectar"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /**
     * Si el usuario mata la app desde la lista de recientes (la "cierra por
     * completo"), cortamos la conexion y el servicio en vez de dejarlo
     * colgado en segundo plano para siempre.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        socket?.close(1000, "app cerrada")
        connected = false
        liberarWifiLock()
        ocultarBotonFlotanteCaptura()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    fun conectar(ip: String, puerto: String, pin: String) {
        actualizarNotificacion("Conectando...")
        listener?.onConectando()

        val request = Request.Builder().url("ws://$ip:$puerto/").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val auth = JSONObject()
                auth.put("type", "auth")
                auth.put("pin", pin)
                webSocket.send(auth.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "auth_ok" -> {
                        connected = true
                        actualizarNotificacion("Conectado")
                        adquirirWifiLock()
                        listener?.onAutenticado()
                    }
                    "auth_fail" -> {
                        connected = false
                        actualizarNotificacion("PIN incorrecto")
                        listener?.onAuthFallo()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Los frames de la pantalla de la PC viajan en binario
                // (JPEG crudo), separados de los mensajes de control (que
                // siguen siendo JSON de texto).
                listener?.onFrameDePantalla(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                actualizarNotificacion("Error de conexion")
                liberarWifiLock()
                listener?.onDesconectado(t.message ?: "Error de conexion")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                actualizarNotificacion("Desconectado")
                liberarWifiLock()
                listener?.onDesconectado("Desconectado")
            }
        })
    }

    fun enviarJson(json: String) {
        if (connected) socket?.send(json)
    }

    /**
     * Manda bytes binarios sueltos al agente (por ahora solo se usa para la
     * captura de pantalla del celular, ver iniciarCapturaPantallaMovil). Se
     * distingue de los mensajes JSON de texto porque viaja como frame
     * BINARIO de WebSocket: el agente los identifica por tipo de mensaje,
     * no por contenido.
     */
    fun enviarBytes(bytes: ByteArray) {
        if (connected) socket?.send(bytes.toByteString())
    }

    fun desconectarManualmente() {
        socket?.close(1000, "desconexion manual")
        connected = false
        actualizarNotificacion("Sin conectar")
        liberarWifiLock()
        ocultarBotonFlotanteCaptura()
    }

    // ==================== BOTON FLOTANTE DE CAPTURA (pantalla del celular) ====================

    /**
     * Muestra un boton circular flotante, arrastrable, por ENCIMA de
     * cualquier app (incluida la pantalla de inicio). El permiso de
     * MediaProjection (resultCode + data) ya viene concedido desde
     * MainActivity: se guarda para usarlo recien cuando el usuario TOQUE el
     * boton, no antes. Asi, entre que se concede el permiso y se toca el
     * boton, el usuario puede irse a cualquier otra app y la captura va a
     * ser de lo que sea que este viendo en ese momento.
     */
    fun mostrarBotonFlotanteCaptura(resultCode: Int, data: Intent) {
        ocultarBotonFlotanteCaptura() // por si habia uno mostrado de antes

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val boton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_captura_pantalla_movil)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dpFlotante(14), dpFlotante(14), dpFlotante(14), dpFlotante(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2E7D32"))
            }
            alpha = 0.92f
        }

        // TYPE_APPLICATION_OVERLAY es el tipo correcto desde Android 8 (O)
        // para ventanas que se dibujan encima de otras apps con el permiso
        // SYSTEM_ALERT_WINDOW; TYPE_PHONE es el equivalente en versiones
        // viejas (deprecado, pero necesario para compatibilidad).
        val tipoVentana = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            dpFlotante(56),
            dpFlotante(56),
            tipoVentana,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpFlotante(16)
            y = dpFlotante(200)
        }

        // Distingue un TOQUE (dispara la captura) de un ARRASTRE (mueve el
        // boton para destaparlo de lo que se quiere capturar): si el dedo se
        // movio poco entre el ACTION_DOWN y el ACTION_UP, es un toque.
        var xInicial = 0
        var yInicial = 0
        var toqueXInicial = 0f
        var toqueYInicial = 0f
        var movimientoTotal = 0f

        boton.setOnTouchListener { vista, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    xInicial = params.x
                    yInicial = params.y
                    toqueXInicial = event.rawX
                    toqueYInicial = event.rawY
                    movimientoTotal = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - toqueXInicial
                    val dy = event.rawY - toqueYInicial
                    movimientoTotal += abs(dx) + abs(dy)
                    params.x = xInicial + dx.toInt()
                    params.y = yInicial + dy.toInt()
                    try { windowManager?.updateViewLayout(vista, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (movimientoTotal < dpFlotante(12)) {
                        // Toque corto sin arrastrar: dispara la captura.
                        // OJO CON EL ORDEN: hay que sacar el boton de
                        // pantalla ANTES de capturar (no despues, como
                        // estaba antes), porque si seguia dibujado en el
                        // instante en que se arma el VirtualDisplay, la
                        // captura terminaba incluyendo al propio boton.
                        // ocultarBotonFlotanteCaptura() saca la vista de
                        // forma SINCRONICA (removeViewImmediate, no
                        // removeView), y el delay de despues le da tiempo
                        // al sistema a redibujar la pantalla sin el boton
                        // antes de que se dispare la captura real.
                        ocultarBotonFlotanteCaptura()
                        Handler(Looper.getMainLooper()).postDelayed({
                            iniciarCapturaPantallaMovil(resultCode, data)
                        }, 120)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        botonFlotante = boton
        try {
            wm.addView(boton, params)
        } catch (_: Exception) {
            // Puede fallar si el permiso de superposicion se revoco justo
            // en el medio (raro, pero posible desde Ajustes del sistema).
            botonFlotante = null
        }
    }

    /**
     * Saca el boton flotante de pantalla, si estaba mostrado. Se llama tanto
     * ANTES de disparar la captura real (para que el boton no salga en la
     * propia captura) como al desconectarse o cerrar la app.
     *
     * Usa removeViewImmediate() en vez de removeView(): este ultimo es
     * ASINCRONICO (encola la remocion para la siguiente pasada del sistema
     * de ventanas), asi que si justo despues se dispara una captura, el
     * boton todavia podia estar dibujado en pantalla en ese instante.
     * removeViewImmediate() saca la vista en el momento, de forma
     * sincronica, garantizando que ya no este cuando esta funcion retorna.
     */
    fun ocultarBotonFlotanteCaptura() {
        botonFlotante?.let {
            try {
                windowManager?.removeViewImmediate(it)
            } catch (_: Exception) {
                // Ya se habia sacado o la ventana ya no era valida: no pasa nada.
            }
        }
        botonFlotante = null
    }

    private fun dpFlotante(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    // ==================== CAPTURA DE PANTALLA DEL CELULAR ====================

    /**
     * Arranca la captura de UN SOLO frame de la pantalla del celular usando
     * MediaProjection (la misma API que usan las apps de grabacion de
     * pantalla), a partir del permiso ya otorgado por el usuario
     * (resultCode + data vienen del Intent que devuelve
     * MediaProjectionManager.createScreenCaptureIntent(), lanzado desde
     * MainActivity). Apenas llega el primer frame al ImageReader, se
     * comprime a PNG y se manda al agente de Windows; despues se liberan
     * todos los recursos de la proyeccion (no se queda grabando ni
     * transmitiendo nada mas).
     */
    fun iniciarCapturaPantallaMovil(resultCode: Int, data: Intent) {
        capturaMovilYaObtenida.set(false)

        // Recien ahora (el usuario ya toco "Captura" y ya concedio el
        // permiso del dialogo de sistema) es seguro subir el servicio a
        // tipo mediaProjection. Llamar a getMediaProjection() sin haber
        // hecho esto antes tira SecurityException.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            construirNotificacion(if (connected) "Conectado" else "Sin conectar"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proyeccion = mpm.getMediaProjection(resultCode, data)
        if (proyeccion == null) {
            actualizarNotificacion(if (connected) "Conectado" else "Sin conectar")
            return
        }
        proyeccionPantallaMovil = proyeccion

        // Desde Android 14 (API 34), hay que registrar un callback ANTES de
        // crear el virtual display, o el sistema puede rechazar la
        // proyeccion. onStop se dispara si el usuario corta el permiso desde
        // la barra de estado (el ícono de "se esta grabando la pantalla").
        proyeccion.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                liberarRecursosCapturaMovil()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val ancho = metrics.widthPixels
        val alto = metrics.heightPixels
        val densidad = metrics.densityDpi

        val imageReader = ImageReader.newInstance(ancho, alto, PixelFormat.RGBA_8888, 2)
        imageReaderCapturaMovil = imageReader

        imageReader.setOnImageAvailableListener({ reader ->
            // El listener puede dispararse mas de una vez con frames
            // sucesivos; solo nos interesa el PRIMERO (esto es una captura,
            // no un stream), asi que los siguientes se descartan.
            if (!capturaMovilYaObtenida.compareAndSet(false, true)) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val imagen = reader.acquireLatestImage()
            if (imagen != null) {
                try {
                    val bitmap = imagenACapturaBitmap(imagen, ancho, alto)
                    val salida = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, salida)
                    enviarBytes(salida.toByteArray())
                    bitmap.recycle()
                } finally {
                    imagen.close()
                }
            }

            // Se libera con un pequenio delay (no en el mismo callback) para
            // no cerrar el ImageReader/VirtualDisplay mientras todavia se
            // esta procesando el frame que acaban de entregar.
            Handler(Looper.getMainLooper()).postDelayed({ liberarRecursosCapturaMovil() }, 150)
        }, Handler(Looper.getMainLooper()))

        virtualDisplayCapturaMovil = proyeccion.createVirtualDisplay(
            "captura_pantalla_movil",
            ancho, alto, densidad,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )
    }

    /**
     * Convierte el frame RGBA_8888 crudo que entrega ImageReader a un
     * Bitmap normal. Ojo: el "rowStride" que devuelve Android casi nunca
     * coincide exactamente con "ancho * 4 bytes" (suele venir con relleno
     * extra al final de cada fila por alineacion de memoria), asi que hay
     * que armar el bitmap con el ancho "con relleno" y despues recortarlo
     * al ancho real.
     */
    private fun imagenACapturaBitmap(imagen: android.media.Image, ancho: Int, alto: Int): Bitmap {
        val plano = imagen.planes[0]
        val buffer = plano.buffer
        val pixelStride = plano.pixelStride
        val rowStride = plano.rowStride
        val relleno = rowStride - pixelStride * ancho
        val anchoConRelleno = ancho + relleno / pixelStride

        val bitmapCrudo = Bitmap.createBitmap(anchoConRelleno, alto, Bitmap.Config.ARGB_8888)
        bitmapCrudo.copyPixelsFromBuffer(buffer)

        return if (relleno == 0) {
            bitmapCrudo
        } else {
            val recortado = Bitmap.createBitmap(bitmapCrudo, 0, 0, ancho, alto)
            bitmapCrudo.recycle()
            recortado
        }
    }

    private fun liberarRecursosCapturaMovil() {
        virtualDisplayCapturaMovil?.release()
        virtualDisplayCapturaMovil = null
        imageReaderCapturaMovil?.close()
        imageReaderCapturaMovil = null
        proyeccionPantallaMovil?.stop()
        proyeccionPantallaMovil = null

        // Volvemos el servicio a su tipo base (sin mediaProjection): ya se
        // uso y se libero, no tiene sentido mantenerlo declarado como tal
        // el resto del tiempo que la app siga conectada.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            construirNotificacion(if (connected) "Conectado" else "Sin conectar"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "Conexion Teclado Movil",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(canal)
        }
    }

    private fun construirNotificacion(estado: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Teclado Movil")
            .setContentText(estado)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setColor(Color.parseColor("#2E7D32"))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun actualizarNotificacion(estado: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, construirNotificacion(estado))
    }

    /**
     * WIFI_MODE_FULL_HIGH_PERF le pide a Android que mantenga el radio WiFi
     * activo a maxima velocidad, sin entrar en los modos de ahorro de
     * energia que normalmente se activan cuando la pantalla se apaga o la
     * app pasa a segundo plano. Es justo esa "siesta" del WiFi la causa mas
     * comun de que el WebSocket se corte solo al minimizar la app.
     */
    private fun adquirirWifiLock() {
        if (wifiLock != null) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TecladoMovil:wifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun liberarWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }
}
