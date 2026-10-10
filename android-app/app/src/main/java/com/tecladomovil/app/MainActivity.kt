package com.tecladomovil.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    /**
     * Paleta del modo oscuro. Todo el color de la app pasa por aca en vez de
     * estar suelto en cada view, para que sea facil de tocar si mas adelante
     * se quiere ajustar el tema.
     */
    private object Colores {
        const val FONDO = "#121212"
        const val CAMPO = "#1E1E1E"
        const val BOTON = "#2A2A2A"
        const val TEXTO_PRIMARIO = "#EAEAEA"
        const val TEXTO_HINT = "#8A8A8A"
        const val TEXTO_SECUNDARIO = "#AAAAAA"
        const val OK = "#66BB6A"
        const val ERROR = "#EF5350"
        const val TOUCHPAD_AREA = "#2A2A2A"
        const val SCROLLBAR = "#333333"
        const val PRESION_BOTON = "#FF9800"
    }


    private lateinit var prefs: android.content.SharedPreferences

    // --- Pantalla de conexion (solo se ve al inicio / al desconectarse) ---
    private lateinit var panelConexion: LinearLayout
    private lateinit var ipInput: EditText
    private lateinit var puertoInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var statusText: TextView

    // --- Pantalla principal ---
    private lateinit var panelPrincipal: LinearLayout
    private lateinit var escrituraInput: EditText
    private lateinit var imagenPantallaPc: ImageView

    // Se pone en true la primera vez que llega un frame de pantalla y se
    // ajusta la altura del ImageView a su proporcion real, para que no
    // queden franjas negras arriba/abajo (letterboxing). Se resetea al
    // volver a la pantalla de conexion por si el celular se reconecta con
    // una PC de otra resolucion.
    private var aspectoPantallaPcAjustado = false

    // Evita que se acumulen frames de pantalla sin procesar: si llega uno
    // nuevo mientras el anterior todavia se esta decodificando/mostrando, el
    // nuevo se descarta en vez de encolarse (asi no se va generando atraso).
    private val decodificandoFrame = AtomicBoolean(false)

    private lateinit var panelTeclado: LinearLayout
    private lateinit var panelTouchpad: LinearLayout
    private lateinit var panelTecladoPc: LinearLayout
    private lateinit var botonPresion: Button

    // Boton "Middle" (click de la rueda del mouse), arriba del touchpad.
    // Funciona igual que "Presion": un toque deja la rueda apretada
    // (middle-down) y otro toque la suelta (middle-up).
    private lateinit var botonMiddle: Button
    private var middleSostenido = false

    // True mientras el boton "Presion" esta activo: el click izquierdo del
    // mouse quedo sostenido (left-down mandado, left-up todavia no) para que
    // el usuario pueda arrastrar con el touchpad y seleccionar cosas sin
    // tener que mantener el dedo quieto un instante como en el modo
    // long-press del touchpad.
    private var presionSostenida = false

    // True mientras el switch "Combinaciones" (pestania Teclas PC) esta
    // activado. Cambia el comportamiento de los botones Ctrl/Alt/Shift/Win
    // de la grilla: en vez de tocar = down+up inmediato, tocar los deja
    // SOSTENIDOS (solo se manda el down) hasta volver a tocarlos, para poder
    // combinarlos con una o varias teclas siguientes de la grilla (ej.
    // Ctrl+Z, o Ctrl+Shift+flecha manteniendo dos modificadores a la vez).
    // Ademas, mientras esta activo, las 4 filas de arriba (F1-F4) de la
    // grilla cambian de iconos a letras A-Z (ver actualizarModoTecladoPc),
    // para poder combinar los modificadores sostenidos con una letra.
    private var combinacionesActivas = false

    // Nombres (en minuscula, mismo formato que usa enviarTecla) de los
    // modificadores actualmente sostenidos gracias a "Combinaciones".
    private val modificadoresPresionados = mutableSetOf<String>()

    // Referencia a los botones modificadores de la grilla de Teclas PC
    // (Ctrl, Alt, Shift, Win, Tab), para poder resaltarlos visualmente
    // mientras estan sostenidos y devolverlos a su color normal al
    // soltarlos o al desactivar el switch.
    private val botonesModificadores = mutableMapOf<String, View>()

    // Referencia a la grilla de Teclas PC y a los dos juegos de celdas que
    // se intercambian en ella cuando se activa "Combinaciones": las 4 filas
    // de arriba (F1-F4) alternan entre "modo iconos" (deshacer, brillo,
    // copiar/pegar, multimedia, etc., el modo normal) y "modo letras"
    // (A-Z). F5 y F6 (modificadores, flechas, enter, backspace) quedan
    // siempre fijas, no forman parte de ninguno de los dos juegos.
    private lateinit var gridTecladoPc: GridLayout
    private val celdasModoIconos = mutableListOf<View>()
    private val celdasModoLetras = mutableListOf<View>()

    private var textoPrevio = ""

    // Se pone en true justo antes de limpiar el campo de texto (por Enter),
    // para que el TextWatcher no interprete esa limpieza como una edicion
    // real y mande un "delete" de mas hacia el agente de Windows.
    private var ignorarProximoCambio = false

    // --- Conexion con el servicio en primer plano ---
    private var servicio: ConexionService? = null
    private var servicioLigado = false

    // --- Captura de pantalla del celular (boton de F3,C7) ---
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Lanza el dialogo del sistema que pide permiso para capturar la
    // pantalla ("Empezar a compartir/grabar pantalla"). El resultado
    // (resultCode + Intent data) es lo que MediaProjectionManager necesita
    // para poder obtener la proyeccion. En vez de capturar de una, se lo
    // pasamos al servicio para que muestre el BOTON FLOTANTE: asi la
    // captura real recien se dispara cuando el usuario toque ese boton,
    // pudiendo antes navegar a lo que quiera capturar (la app de Teclado
    // Movil se manda sola a segundo plano con moveTaskToBack).
    private val lanzadorCapturaPantallaMovil = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        if (resultado.resultCode == RESULT_OK && resultado.data != null) {
            servicio?.mostrarBotonFlotanteCaptura(resultado.resultCode, resultado.data!!)
            Toast.makeText(
                this,
                "Anda a lo que queres capturar y toca el boton flotante",
                Toast.LENGTH_LONG
            ).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Permiso de captura de pantalla denegado", Toast.LENGTH_SHORT).show()
        }
    }

    // Lanza la pantalla de Ajustes donde el usuario concede "Mostrar sobre
    // otras apps" (SYSTEM_ALERT_WINDOW), necesario para el boton flotante de
    // captura. No hay forma de pedir este permiso con un dialogo directo
    // (a diferencia de POST_NOTIFICATIONS): Android obliga a pasar por
    // Ajustes. Al volver de esa pantalla, se chequea si de verdad quedo
    // concedido (el resultCode de esta Activity no es confiable para saberlo).
    private val lanzadorPermisoSuperposicion = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            lanzadorCapturaPantallaMovil.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else {
            Toast.makeText(
                this,
                "Sin ese permiso no se puede mostrar el boton flotante de captura",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Pide el permiso de CAMARA (necesario para escanear el QR) justo antes
    // de lanzar el escaneo, en vez de pedirlo en onCreate: asi solo se le
    // molesta al usuario si realmente va a usar esa funcion.
    private val lanzadorPermisoCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) {
            lanzarEscaneoQr()
        } else {
            Toast.makeText(this, "Sin permiso de camara no se puede escanear el QR", Toast.LENGTH_SHORT).show()
        }
    }

    // Lanza la actividad de escaneo (ZXing embedded) y recibe el contenido
    // leido del QR, con el formato "tecladomovil://IP:PUERTO/PIN" que
    // imprime el agente de Windows en la consola al arrancar.
    private val lanzadorEscaneoQr = registerForActivityResult(ScanContract()) { resultado ->
        val contenido = resultado.contents
        if (contenido != null) {
            procesarQrEscaneado(contenido)
        }
    }

    private val conexionServiceListener = object : ConexionService.ConexionListener {
        override fun onConectando() {
            runOnUiThread {
                statusText.text = "Conectando..."
            }
        }

        override fun onAutenticado() {
            runOnUiThread {
                mostrarPantallaPrincipal()
            }
        }

        override fun onAuthFallo() {
            runOnUiThread {
                statusText.text = "PIN incorrecto"
                statusText.setTextColor(Color.parseColor(Colores.ERROR))
            }
        }

        override fun onDesconectado(motivo: String) {
            runOnUiThread {
                mostrarPantallaConexion("Desconectado: $motivo")
            }
        }

        override fun onFrameDePantalla(jpegBytes: ByteArray) {
            // Si el frame anterior todavia se esta decodificando/mostrando,
            // descartamos este directamente en vez de encolarlo. Asi, si el
            // celular se atrasa un instante, se pone al dia solo con el
            // siguiente frame que llegue, en vez de ir mostrando frames cada
            // vez mas viejos (que se siente como lag creciente).
            if (!decodificandoFrame.compareAndSet(false, true)) return

            Thread {
                try {
                    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    if (bitmap != null) {
                        runOnUiThread {
                            ajustarAlturaPantallaPc(bitmap.width, bitmap.height)
                            imagenPantallaPc.setImageBitmap(bitmap)
                            decodificandoFrame.set(false)
                        }
                    } else {
                        decodificandoFrame.set(false)
                    }
                } catch (_: Exception) {
                    // Frame corrupto: se descarta, el que viene despues lo reemplaza.
                    decodificandoFrame.set(false)
                }
            }.start()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as ConexionService.LocalBinder
            servicio = local.getService()
            servicio?.listener = conexionServiceListener
            servicioLigado = true

            if (servicio?.connected == true) {
                mostrarPantallaPrincipal()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            servicio = null
            servicioLigado = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("teclado_movil_prefs", Context.MODE_PRIVATE)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        solicitarExclusionDeOptimizacionBateria()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Sin padding horizontal: la pantalla de la PC (imagenPantallaPc)
            // necesita ocupar el ancho COMPLETO para no dejar espacios vacios
            // a los costados. El padding para los controles (botones, campos
            // de texto) se agrega mas abajo, por seccion, no aca arriba.
            setPadding(0, 24, 0, 24)
            setBackgroundColor(Color.parseColor(Colores.FONDO))
        }

        panelConexion = buildPanelConexion()
        root.addView(panelConexion)

        panelPrincipal = buildPanelPrincipal()
        panelPrincipal.visibility = View.GONE
        root.addView(panelPrincipal)

        setContentView(root)

        val intent = Intent(this, ConexionService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        // Si volvemos a la app (desde otra app o desde el bloqueo de
        // pantalla) y ya estamos conectados, retomamos la vista de la
        // pantalla de la PC que se habia pausado en onPause.
        if (panelPrincipal.visibility == View.VISIBLE && servicio?.connected == true) {
            enviarJson(JSONObject().apply { put("type", "start_screen") })
        }
    }

    override fun onPause() {
        super.onPause()
        // La app se va a segundo plano: no tiene sentido seguir gastando
        // datos/bateria mandando capturas de pantalla que no se ven. La
        // conexion en si sigue viva gracias al servicio en primer plano.
        if (panelPrincipal.visibility == View.VISIBLE) {
            enviarJson(JSONObject().apply { put("type", "stop_screen") })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (servicioLigado) {
            servicio?.listener = null
            unbindService(serviceConnection)
            servicioLigado = false
        }
        // Ojo: no se llama a stopService aca a proposito. El servicio se
        // queda corriendo en primer plano (con su notificacion) para que la
        // conexion no se corte al cambiar de app. Solo se corta si Android
        // saca la app de la lista de recientes (ver ConexionService.onTaskRemoved).
    }

    /**
     * En muchos celulares (sobre todo Xiaomi, Huawei, Oppo, Realme y otros
     * fabricantes con capas de ahorro de bateria propias) el sistema mata
     * igual las apps en segundo plano por mas foreground service que
     * tengan, salvo que el usuario la excluya manualmente de la
     * optimizacion de bateria. En vez de mandar al usuario a buscar ese
     * ajuste a mano en Configuracion, se lo pedimos directo con el dialogo
     * nativo de Android (solo se muestra si todavia no esta concedido).
     */
    private fun solicitarExclusionDeOptimizacionBateria() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Algunos fabricantes bloquean/reemplazan este intent especifico
            // por su propio dialogo (o directamente no lo soportan). Si
            // falla, no pasa nada grave: el usuario puede hacerlo a mano
            // desde Ajustes > Bateria > Sin restricciones.
        }
    }

    // ==================== ESTILO (modo oscuro consistente) ====================

    /**
     * Aplica el estilo de campo de texto del modo oscuro: fondo CAMPO, texto
     * TEXTO_PRIMARIO, hint TEXTO_HINT. Se usa en todos los EditText de la app
     * para no repetir las mismas 3 lineas en cada uno (antes solo el campo
     * de IP lo tenia, y los demas se quedaban con el estilo claro del
     * sistema, que era justo lo que se veia "a medio hacer").
     */
    private fun aplicarEstiloCampo(input: EditText) {
        input.setTextColor(Color.parseColor(Colores.TEXTO_PRIMARIO))
        input.setHintTextColor(Color.parseColor(Colores.TEXTO_HINT))
        input.setBackgroundColor(Color.parseColor(Colores.CAMPO))
        input.setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    /**
     * Aplica el estilo de boton del modo oscuro: fondo BOTON, texto
     * TEXTO_PRIMARIO. Antes casi ningun boton de la app llamaba a esto, asi
     * que se veian con el gris clarito por defecto de Android en vez de
     * integrarse con el resto de la pantalla oscura.
     */
    private fun aplicarEstiloBoton(boton: Button) {
        boton.setTextColor(Color.parseColor(Colores.TEXTO_PRIMARIO))
        boton.setBackgroundColor(Color.parseColor(Colores.BOTON))
    }

    private fun crearFondoConPresion(colorNormal: String): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.parseColor(Colores.PRESION_BOTON)))
            addState(intArrayOf(), ColorDrawable(Color.parseColor(colorNormal)))
        }
    }

    // ==================== PANTALLA DE CONEXION ====================

    private fun buildPanelConexion(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Esta pantalla (a diferencia de la principal) no tiene la
            // imagen de la PC, asi que si necesita su propio padding
            // horizontal ahora que el root ya no lo pone.
            setPadding(dp(16), 0, dp(16), 0)

            ipInput = EditText(this@MainActivity).apply {
                hint = "IP del agente (ej: 192.168.1.4)"
                setText(prefs.getString("ip", ""))
            }
            aplicarEstiloCampo(ipInput)
            addView(ipInput)

            puertoInput = EditText(this@MainActivity).apply {
                hint = "Puerto"
                setText(prefs.getString("puerto", "8765"))
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            aplicarEstiloCampo(puertoInput)
            addView(puertoInput)

            pinInput = EditText(this@MainActivity).apply {
                hint = "PIN"
                setText(prefs.getString("pin", ""))
            }
            aplicarEstiloCampo(pinInput)
            addView(pinInput)

            // Fila con "Conectar" y "Escanear QR" lado a lado: el QR evita
            // tener que tipear IP/puerto/PIN a mano, se completan solos y
            // se conecta directo apenas se lee un codigo valido.
            val filaBotones = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }

            val connectButton = Button(this@MainActivity).apply {
                text = "Conectar"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { conectar() }
            }
            aplicarEstiloBoton(connectButton)
            filaBotones.addView(connectButton)

            val scanButton = Button(this@MainActivity).apply {
                text = "Escanear QR"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(8), 0, 0, 0)
                }
                setOnClickListener { iniciarEscaneoQr() }
            }
            aplicarEstiloBoton(scanButton)
            filaBotones.addView(scanButton)

            addView(filaBotones)

            statusText = TextView(this@MainActivity).apply {
                text = "Sin conectar"
                setPadding(0, 16, 0, 16)
                setTextColor(Color.parseColor(Colores.ERROR))
            }
            addView(statusText)
        }
    }

    private fun conectar() {
        val ip = ipInput.text.toString().trim()
        val puerto = puertoInput.text.toString().trim()
        val pin = pinInput.text.toString().trim()

        if (ip.isEmpty() || puerto.isEmpty() || pin.isEmpty()) {
            statusText.text = "Falta la IP, el puerto o el PIN"
            return
        }

        prefs.edit()
            .putString("ip", ip)
            .putString("puerto", puerto)
            .putString("pin", pin)
            .apply()

        statusText.setTextColor(Color.parseColor(Colores.ERROR))
        servicio?.conectar(ip, puerto, pin)
    }

    /**
     * Punto de entrada del boton "Escanear QR": pide el permiso de camara
     * si todavia no esta concedido y recien ahi lanza el escaneo (si ya
     * esta concedido, salta directo a lanzarEscaneoQr).
     */
    private fun iniciarEscaneoQr() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            lanzarEscaneoQr()
        } else {
            lanzadorPermisoCamara.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun lanzarEscaneoQr() {
        val opciones = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Apunta al QR que muestra el agente de Windows")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        lanzadorEscaneoQr.launch(opciones)
    }

    /**
     * Interpreta el contenido leido del QR, con el formato
     * "tecladomovil://IP:PUERTO/PIN" que imprime el agente de Windows.
     * Si el formato es valido, completa los 3 campos y conecta directo
     * (evita el paso extra de tocar "Conectar" despues de escanear).
     */
    private fun procesarQrEscaneado(contenido: String) {
        try {
            val uri = Uri.parse(contenido)
            val ip = uri.host
            val puerto = if (uri.port != -1) uri.port.toString() else null
            val pin = uri.path?.removePrefix("/")

            if (uri.scheme != "tecladomovil" || ip.isNullOrEmpty() || puerto.isNullOrEmpty() || pin.isNullOrEmpty()) {
                Toast.makeText(this, "Codigo QR no valido", Toast.LENGTH_SHORT).show()
                return
            }

            ipInput.setText(ip)
            puertoInput.setText(puerto)
            pinInput.setText(pin)
            conectar()
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo leer el codigo QR", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mostrarPantallaPrincipal() {
        panelConexion.visibility = View.GONE
        panelPrincipal.visibility = View.VISIBLE
        // La vista de la pantalla de la PC arranca sola apenas te conectas,
        // queda ahi arriba siempre (no depende de en que pestania estes).
        enviarJson(JSONObject().apply { put("type", "start_screen") })
        // Al entrar ya estamos en la pestania "teclado" por defecto: mostramos el teclado.
        mostrarPanel("teclado")
    }

    private fun mostrarPantallaConexion(motivo: String) {
        panelPrincipal.visibility = View.GONE
        panelConexion.visibility = View.VISIBLE
        statusText.text = motivo
        statusText.setTextColor(Color.parseColor(Colores.ERROR))
        // Se resetea para que la proxima vez que se conecte (quiza a otra
        // PC, con otra resolucion de pantalla) se vuelva a calcular bien.
        aspectoPantallaPcAjustado = false
        if (presionSostenida) {
            presionSostenida = false
            actualizarBotonPresion()
        }
        if (middleSostenido) {
            middleSostenido = false
            actualizarBotonMiddle()
        }
        // Al desconectar, si habia modificadores sostenidos (Ctrl/Alt/Shift/
        // Win) por "Combinaciones", se limpia el estado y se les devuelve el
        // color normal: no tiene sentido mandar el "up" porque ya no hay
        // conexion, pero si dejarlos marcados como sostenidos visualmente.
        if (modificadoresPresionados.isNotEmpty()) {
            modificadoresPresionados.clear()
            botonesModificadores.values.forEach { it.background = crearFondoConPresion(Colores.BOTON) }
        }
    }

    // ==================== PANTALLA PRINCIPAL ====================

    private fun buildPanelPrincipal(): LinearLayout {
        val contenedor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // La pantalla de la PC va fija arriba de todo, siempre visible sin
        // importar en que pestania (Teclado/Touchpad/Teclas PC) estes parado.
        // Se agrega DIRECTO al contenedor (que no tiene padding), para que
        // ocupe el ancho completo de la pantalla del celular sin espacios
        // vacios a los costados. Mantene presionada la imagen para
        // desconectar (asi no hace falta gastar espacio en pantalla con un
        // texto de estado fijo).
        imagenPantallaPc = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230))
            setBackgroundColor(Color.parseColor("#000000"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnLongClickListener {
                servicio?.desconectarManualmente()
                mostrarPantallaConexion("Sin conectar")
                Toast.makeText(this@MainActivity, "Desconectado", Toast.LENGTH_SHORT).show()
                true
            }
        }
        contenedor.addView(imagenPantallaPc)

        // Teclado y Touchpad van dentro de un contenedor CON padding
        // horizontal propio, para que esos controles no queden pegados a
        // los bordes como si es deseable que quede la pantalla de la PC.
        val contenidoConPadding = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }

        // --- Barra de pestanias (Teclado / Touchpad / Teclas PC) ---
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val tabTeclado = tabButton("Teclado") { mostrarPanel("teclado") }
        val tabTouchpad = tabButton("Touchpad") { mostrarPanel("touchpad") }
        val tabTecladoPc = tabButton("Teclas PC") { mostrarPanel("teclas_pc") }
        tabBar.addView(tabTeclado)
        tabBar.addView(tabTouchpad)
        tabBar.addView(tabTecladoPc)
        contenidoConPadding.addView(tabBar)

        panelTeclado = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelTeclado.addView(buildPanelTeclado())
        contenidoConPadding.addView(panelTeclado)

        panelTouchpad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        panelTouchpad.addView(buildPanelTouchpad())
        contenidoConPadding.addView(panelTouchpad)

        contenedor.addView(contenidoConPadding)

        // Teclas PC va FUERA del contenedor con padding horizontal (a
        // diferencia de Teclado y Touchpad): la grilla ocupa el ancho
        // completo de la pantalla, sin bordes a los costados, igual que la
        // imagen de la pantalla de la PC. Esto no cambia la separacion
        // ENTRE celdas de la grilla, que la siguen dando los margenes de
        // cada boton dentro de buildPanelTecladoPc.
        panelTecladoPc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            // Un poco de aire arriba, para que la grilla no quede pegada
            // justo debajo de la barra de pestanias.
            setPadding(0, dp(50), 0, 0)
        }
        panelTecladoPc.addView(buildPanelTecladoPc())
        contenedor.addView(panelTecladoPc)

        return contenedor
    }

    private fun tabButton(texto: String, accion: () -> Unit): Button {
        return Button(this).apply {
            text = texto
            textSize = 12f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { accion() }
        }.also { aplicarEstiloBoton(it) }
    }

    /**
     * La primera vez que llega un frame de la pantalla de la PC, ajusta la
     * altura del ImageView para que coincida exactamente con la proporcion
     * (ancho x alto) real de esa imagen. Como imagenPantallaPc ahora ocupa
     * el ancho COMPLETO de la pantalla del celular (sin padding), el ancho
     * disponible real coincide con displayMetrics.widthPixels, asi que la
     * proporcion calculada es exacta y no quedan franjas negras (letterboxing)
     * ni arriba/abajo ni a los costados.
     */
    private fun ajustarAlturaPantallaPc(anchoImagen: Int, altoImagen: Int) {
        if (aspectoPantallaPcAjustado || anchoImagen <= 0) return
        val anchoDisponible = resources.displayMetrics.widthPixels
        val altoCalculado = (anchoDisponible * altoImagen.toFloat() / anchoImagen.toFloat()).toInt()
        imagenPantallaPc.layoutParams = imagenPantallaPc.layoutParams.apply { height = altoCalculado }
        aspectoPantallaPcAjustado = true
    }

    /**
     * Cambia de pestania (Teclado / Touchpad / Teclas PC) y muestra u oculta
     * el teclado nativo segun corresponda. La pantalla de la PC no depende
     * de esto: sigue arriba, siempre visible, en cualquier pestania.
     */
    private fun mostrarPanel(cual: String) {
        panelTeclado.visibility = if (cual == "teclado") View.VISIBLE else View.GONE
        panelTouchpad.visibility = if (cual == "touchpad") View.VISIBLE else View.GONE
        panelTecladoPc.visibility = if (cual == "teclas_pc") View.VISIBLE else View.GONE

        if (cual == "teclado") {
            escrituraInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(escrituraInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            ocultarTeclado()
        }
    }

    private fun ocultarTeclado() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(escrituraInput.windowToken, 0)
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    private fun avisarSiDesconectado() {
        if (servicio?.connected != true) {
            Toast.makeText(this, "No estas conectado al agente de Windows", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enviarJson(msg: JSONObject) {
        servicio?.enviarJson(msg.toString())
    }

    /**
     * Punto de entrada de la tecla "Captura pantalla movil" (F3,C7). Antes
     * disparaba la captura de una, lo que siempre terminaba capturando la
     * propia app de Teclado Movil (porque en ese instante era lo unico que
     * estaba en pantalla). Ahora el flujo es en dos pasos:
     *
     * 1) Se piden los permisos que hagan falta (superposicion + captura de
     *    pantalla) y se muestra un BOTON FLOTANTE arriba de todo, mientras
     *    la app se manda sola a segundo plano.
     * 2) El usuario navega a lo que realmente quiere capturar y TOCA el
     *    boton flotante: recien ahi se dispara la captura real (ver
     *    ConexionService.mostrarBotonFlotanteCaptura).
     */
    private fun solicitarCapturaPantallaMovil() {
        if (servicio?.connected != true) {
            avisarSiDesconectado()
            return
        }

        // El boton flotante necesita el permiso de "Mostrar sobre otras
        // apps". A diferencia de otros permisos, Android no tiene un dialogo
        // directo para esto: hay que mandar al usuario a una pantalla de
        // Ajustes especifica y, al volver, chequear si de verdad lo concedio.
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Activa \"Mostrar sobre otras apps\" para Teclado Movil y volve",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            lanzadorPermisoSuperposicion.launch(intent)
            return
        }

        lanzadorCapturaPantallaMovil.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // ==================== TECLADO (envio de texto) ====================

    /**
     * Arma el panel de "Teclado": el EditText donde se escribe.
     */
    private fun buildPanelTeclado(): LinearLayout {
        escrituraInput = EditText(this).apply {
            hint = "Escribi aca..."
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setImeActionLabel("Enter", EditorInfo.IME_ACTION_SEND)
            setSingleLine(false)
            maxLines = 3

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    avisarSiDesconectado()
                    enviarTecla("enter", true)
                    enviarTecla("enter", false)
                    limpiarPanelEscritura()
                    true
                } else {
                    false
                }
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (ignorarProximoCambio) {
                        ignorarProximoCambio = false
                        textoPrevio = s?.toString() ?: ""
                        return
                    }
                    val nuevo = s?.toString() ?: ""

                    // Algunos teclados (ej. Gboard en este campo multilinea)
                    // no disparan el IME_ACTION_SEND al tocar Enter: en vez
                    // de eso insertan un salto de linea real ("\n") como si
                    // fuera texto normal. Antes ese "\n" se filtraba dentro
                    // de procesarCambioTexto y ahi quedaba: no se mandaba el
                    // Enter al agente de Windows ni se limpiaba el panel, asi
                    // que el texto se quedaba pegado en pantalla. Aca
                    // detectamos el salto ANTES de filtrarlo y lo tratamos
                    // igual que el Enter del boton de accion: mandamos
                    // primero el texto insertado antes del salto (si habia),
                    // despues el Enter, y recien ahi limpiamos el panel.
                    if (nuevo.contains("\n")) {
                        procesarCambioTexto(textoPrevio, nuevo.replace("\n", ""))
                        avisarSiDesconectado()
                        enviarTecla("enter", true)
                        enviarTecla("enter", false)
                        limpiarPanelEscritura()
                        return
                    }

                    procesarCambioTexto(textoPrevio, nuevo)
                    textoPrevio = nuevo
                }
            })
        }
        aplicarEstiloCampo(escrituraInput)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(escrituraInput)
        }
    }

    /**
     * Limpia el panel de "Teclado" (el EditText donde se ve lo que vas
     * escribiendo). Se usa despues de mandar un Enter, sea desde el teclado
     * nativo o desde el boton "Enter" de la pestania Teclas PC, para que el
     * panel de vista previa no se quede con texto de una linea ya enviada.
     */
    private fun limpiarPanelEscritura() {
        ignorarProximoCambio = true
        escrituraInput.setText("")
        textoPrevio = ""
    }

    private fun enviarCombo(combo: String) {
        enviarJson(JSONObject().apply {
            put("type", "combo")
            put("combo", combo)
        })
    }

    private fun enviarTecla(key: String, isDown: Boolean) {
        enviarJson(JSONObject().apply {
            put("type", "key")
            put("key", key)
            put("action", if (isDown) "down" else "up")
        })
    }

    /**
     * Compara el texto anterior con el nuevo (lo que escribio el teclado nativo)
     * y manda al agente de Windows la diferencia en UN SOLO mensaje: cuantos
     * caracteres borrar al final + que texto insertar. Antes se mandaba una
     * rafaga de mensajes (uno por letra), lo que hacia que ediciones rapidas
     * o correcciones de autocorreccion se mezclaran o se perdieran por el camino.
     * Mandando todo junto, el agente lo aplica de forma atomica y ordenada.
     */
    private fun procesarCambioTexto(anterior: String, nuevo: String) {
        var prefijoComun = 0
        val minLen = minOf(anterior.length, nuevo.length)
        while (prefijoComun < minLen && anterior[prefijoComun] == nuevo[prefijoComun]) {
            prefijoComun++
        }

        val borrar = anterior.length - prefijoComun
        val agregar = nuevo.substring(prefijoComun).replace("\n", "").lowercase()

        if (borrar == 0 && agregar.isEmpty()) return

        avisarSiDesconectado()
        enviarJson(JSONObject().apply {
            put("type", "edit")
            put("delete", borrar)
            put("insert", agregar)
        })
    }

    // ==================== TOUCHPAD (mouse + scroll + arrastre) ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanelTouchpad(): LinearLayout {
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Un poco de aire arriba antes de que arranque el touchpad, para
            // que no quede pegado justo debajo de la barra de pestanias.
            setPadding(0, dp(36), 0, 0)
        }

        // Boton "Middle" (click de la rueda), arriba y centrado sobre la barra
        // de scroll + touchpad. Funciona igual que "Presion": un toque deja la
        // rueda apretada (middle-down, cambia a "Soltar" en verde) y otro
        // toque la suelta (middle-up).
        botonMiddle = Button(this).apply {
            text = "Middle"
            textSize = 16f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(50)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dp(6))
            }
            setOnClickListener {
                avisarSiDesconectado()
                middleSostenido = !middleSostenido
                enviarClickMouse("middle", middleSostenido)
                actualizarBotonMiddle()
            }
        }
        aplicarEstiloBoton(botonMiddle)
        contenedor.addView(botonMiddle)

        // Fila de arriba: la barra de scroll (izquierda) y el area del
        // touchpad (derecha, ocupa el resto del ancho).
        val filaSuperior = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        var ultimoX = 0f
        var ultimoY = 0f
        var movimientoTotal = 0f
        var arrastreEnCurso = false

        // Modo "clickear y arrastrar" (como mantener presionado el boton
        // izquierdo del mouse y mover, para seleccionar texto/iconos). Se
        // activa si el dedo queda quieto un ratito antes de arrastrar.
        val handler = Handler(Looper.getMainLooper())
        var modoArrastreConClick = false
        var runnableLongPress: Runnable? = null
        val demoraLongPress = 280L // ms quieto para activar el modo arrastre

        val area = View(this).apply {
            setBackgroundColor(Color.parseColor(Colores.TOUCHPAD_AREA))
            layoutParams = LinearLayout.LayoutParams(0, dp(260), 1f)
        }
        area.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    ultimoX = event.x
                    ultimoY = event.y
                    movimientoTotal = 0f
                    arrastreEnCurso = true
                    modoArrastreConClick = false

                    // Si el dedo se queda quieto sobre el touchpad por un
                    // momento, arrancamos el "click sostenido": mandamos
                    // left-down y desde ahi cualquier movimiento arrastra
                    // (selecciona) en vez de solo mover el cursor.
                    runnableLongPress = Runnable {
                        modoArrastreConClick = true
                        avisarSiDesconectado()
                        enviarClickMouse("left", true)
                    }
                    handler.postDelayed(runnableLongPress!!, demoraLongPress)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (arrastreEnCurso) {
                        val dx = event.x - ultimoX
                        val dy = event.y - ultimoY
                        movimientoTotal += abs(dx) + abs(dy)

                        // Si nos movemos antes de que dispare el long-press,
                        // es un arrastre normal de cursor: cancelamos el modo
                        // "clickear y arrastrar" para que no se dispare solo.
                        if (!modoArrastreConClick && movimientoTotal > 12f) {
                            runnableLongPress?.let { handler.removeCallbacks(it) }
                        }

                        enviarMovimientoMouse(dx, dy)
                        ultimoX = event.x
                        ultimoY = event.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    runnableLongPress?.let { handler.removeCallbacks(it) }
                    if (modoArrastreConClick) {
                        // Soltamos el boton que quedo sostenido.
                        enviarClickMouse("left", false)
                    } else if (movimientoTotal < 12f) {
                        // Toque corto sin mantener: click normal.
                        avisarSiDesconectado()
                        enviarClickMouse("left", true)
                        enviarClickMouse("left", false)
                    }
                    arrastreEnCurso = false
                    modoArrastreConClick = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    runnableLongPress?.let { handler.removeCallbacks(it) }
                    if (modoArrastreConClick) {
                        enviarClickMouse("left", false)
                    }
                    arrastreEnCurso = false
                    modoArrastreConClick = false
                }
            }
            true
        }
        // La rueda de scroll va primero (a la izquierda) y el area de mover
        // el cursor despues (ocupa el resto del ancho, a la derecha).
        filaSuperior.addView(buildBarraScroll())
        filaSuperior.addView(area)
        contenedor.addView(filaSuperior)

        val filaBotones = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val botonIzq = Button(this).apply {
            text = "Click izquierdo"
            textSize = 12f
            isAllCaps = false
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                avisarSiDesconectado()
                enviarClickMouse("left", true)
                enviarClickMouse("left", false)
            }
        }
        aplicarEstiloBoton(botonIzq)
        val botonDer = Button(this).apply {
            text = "Click derecho"
            textSize = 12f
            isAllCaps = false
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                avisarSiDesconectado()
                enviarClickMouse("right", true)
                enviarClickMouse("right", false)
            }
        }
        aplicarEstiloBoton(botonDer)
        // Toggle: primer toque manda left-down y deja el click "sostenido"
        // (cambia a "Soltar" para que quede claro que esta activo); mientras
        // esta activo, arrastrar en el touchpad de arriba selecciona, igual
        // que si mantuvieras apretado el boton fisico del mouse. Segundo
        // toque manda left-up y vuelve al estado normal.
        botonPresion = Button(this).apply {
            text = "Presion"
            textSize = 12f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                avisarSiDesconectado()
                presionSostenida = !presionSostenida
                enviarClickMouse("left", presionSostenida)
                actualizarBotonPresion()
            }
        }
        aplicarEstiloBoton(botonPresion)
        filaBotones.addView(botonIzq)
        filaBotones.addView(botonDer)
        filaBotones.addView(botonPresion)
        contenedor.addView(filaBotones)

        return contenedor
    }

    /**
     * Barra angosta vertical al lado del touchpad que simula la ruedita del
     * mouse: deslizar el dedo hacia arriba/abajo manda "clicks" de scroll,
     * de a poco (como los "notches" de una rueda real), no pixel a pixel.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildBarraScroll(): View {
        var ultimoY = 0f
        var acumulado = 0f
        val umbral = 24f // pixeles de arrastre por cada "click" de rueda

        return TextView(this).apply {
            text = "⇕"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor(Colores.TEXTO_SECUNDARIO))
            setBackgroundColor(Color.parseColor(Colores.SCROLLBAR))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(260)).apply {
                // La barra va a la izquierda del touchpad, asi que el margen
                // de separacion queda a la derecha.
                setMargins(0, 0, dp(6), 0)
            }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        ultimoY = event.y
                        acumulado = 0f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.y - ultimoY
                        ultimoY = event.y
                        acumulado += dy
                        // Cada vez que se junta suficiente arrastre, se manda
                        // un "click" de rueda y se descuenta del acumulado.
                        while (acumulado >= umbral) {
                            avisarSiDesconectado()
                            enviarScroll(-120)
                            acumulado -= umbral
                        }
                        while (acumulado <= -umbral) {
                            avisarSiDesconectado()
                            enviarScroll(120)
                            acumulado += umbral
                        }
                    }
                }
                true
            }
        }
    }

    private fun enviarMovimientoMouse(dx: Float, dy: Float) {
        val sensibilidad = 1.5f
        enviarJson(JSONObject().apply {
            put("type", "mouse_move")
            put("dx", (dx * sensibilidad).toInt())
            put("dy", (dy * sensibilidad).toInt())
        })
    }

    private fun enviarClickMouse(boton: String, isDown: Boolean) {
        enviarJson(JSONObject().apply {
            put("type", "mouse_click")
            put("button", boton)
            put("action", if (isDown) "down" else "up")
        })
    }

    private fun enviarScroll(amount: Int) {
        enviarJson(JSONObject().apply {
            put("type", "scroll")
            put("amount", amount)
        })
    }

    /**
     * Refleja visualmente el estado del boton "Presion": texto y color
     * distintos cuando el click izquierdo esta sostenido, para que sea obvio
     * de un vistazo que hay que volver a tocarlo para soltar.
     */
    private fun actualizarBotonPresion() {
        if (presionSostenida) {
            botonPresion.text = "Soltar"
            botonPresion.setBackgroundColor(Color.parseColor(Colores.OK))
            botonPresion.setTextColor(Color.parseColor("#0A0A0A"))
        } else {
            botonPresion.text = "Presion"
            botonPresion.setBackgroundColor(Color.parseColor(Colores.BOTON))
            botonPresion.setTextColor(Color.parseColor(Colores.TEXTO_PRIMARIO))
        }
    }

    /**
     * Refleja visualmente el estado del boton "Middle": texto y color
     * distintos cuando el click de la rueda esta sostenido, igual que
     * actualizarBotonPresion con el click izquierdo.
     */
    private fun actualizarBotonMiddle() {
        if (middleSostenido) {
            botonMiddle.text = "Soltar"
            botonMiddle.setBackgroundColor(Color.parseColor(Colores.OK))
            botonMiddle.setTextColor(Color.parseColor("#0A0A0A"))
        } else {
            botonMiddle.text = "Middle"
            botonMiddle.setBackgroundColor(Color.parseColor(Colores.BOTON))
            botonMiddle.setTextColor(Color.parseColor(Colores.TEXTO_PRIMARIO))
        }
    }

    // ==================== TECLAS PC ====================
    // Grilla de 6x7 casilleros (segun el boceto), con 2 celdas especiales
    // mas grandes (una ancha de 2 columnas, otra alta de 2 filas). Todos los
    // botones estan vacios por ahora: la tecla/accion de cada uno se agrega
    // despues, segun las indicaciones que se vayan dando celda por celda.

    /**
     * Maneja el toque de un boton modificador (Ctrl/Alt/Shift/Win/Tab) de la
     * grilla de Teclas PC.
     *
     * Sin "Combinaciones" activado: comportamiento de siempre, toque simple
     * = down+up inmediato (como cualquier otra tecla suelta).
     *
     * Con "Combinaciones" activado: el toque alterna entre sostener el
     * modificador (se manda solo el down y queda resaltado en verde) y
     * soltarlo (se manda el up y vuelve a su color normal). Mientras esta
     * sostenido, se puede tocar cualquier otra tecla de la grilla (o mas de
     * un modificador a la vez) para armar la combinacion, ya que el agente
     * de Windows interpreta cada key down/up de forma independiente.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun configurarTecleoConRepeticion(view: View, accion: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val demoraInicialMs = 400L
        val intervaloMs = 50L

        val runnableRepetir = object : Runnable {
            override fun run() {
                avisarSiDesconectado()
                accion()
                handler.postDelayed(this, intervaloMs)
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    avisarSiDesconectado()
                    accion()
                    handler.postDelayed(runnableRepetir, demoraInicialMs)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(runnableRepetir)
                }
            }
            true
        }
    }

    private fun manejarToqueModificador(key: String, view: View) {
        if (!combinacionesActivas) {
            enviarTecla(key, true)
            enviarTecla(key, false)
            return
        }

        if (modificadoresPresionados.contains(key)) {
            enviarTecla(key, false)
            modificadoresPresionados.remove(key)
            view.background = crearFondoConPresion(Colores.BOTON)
        } else {
            enviarTecla(key, true)
            modificadoresPresionados.add(key)
            view.setBackgroundColor(Color.parseColor(Colores.OK))
        }
    }

    /**
     * Intercambia, dentro de la grilla de Teclas PC, las 4 filas de arriba
     * (F1-F4) entre el modo "iconos" (deshacer, brillo, copiar/pegar,
     * multimedia, etc.) y el modo "letras" (A-Z), segun el estado de
     * combinacionesActivas. F5 y F6 (modificadores, flechas, enter,
     * backspace) no se tocan: nunca forman parte de celdasModoIconos ni de
     * celdasModoLetras, asi que quedan siempre visibles y en su lugar.
     */
    private fun actualizarModoTecladoPc() {
        if (combinacionesActivas) {
            celdasModoIconos.forEach { gridTecladoPc.removeView(it) }
            celdasModoLetras.forEach { gridTecladoPc.addView(it) }
        } else {
            celdasModoLetras.forEach { gridTecladoPc.removeView(it) }
            celdasModoIconos.forEach { gridTecladoPc.addView(it) }
        }
    }

    private fun buildPanelTecladoPc(): LinearLayout {
        val contenedor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Switch "Combinaciones": activa el modo "modificador sostenido" en
        // los botones Ctrl/Alt/Shift/Win de la grilla (ver
        // manejarToqueModificador), y ademas cambia las 4 filas de arriba
        // (F1-F4) de iconos a letras A-Z (ver actualizarModoTecladoPc), para
        // poder combinar los modificadores sostenidos con una letra. Va
        // arriba de la grilla, dentro del padding horizontal de este mismo
        // panel (a diferencia de la grilla, que ocupa el ancho completo).
        val switchCombinaciones = Switch(this).apply {
            text = "Combinaciones"
            setTextColor(Color.parseColor(Colores.TEXTO_PRIMARIO))
            setPadding(dp(16), 0, 0, dp(12))
            setOnCheckedChangeListener { _, activado ->
                combinacionesActivas = activado
                // Si se desactiva el switch con algun modificador todavia
                // sostenido, se lo suelta (manda el "up" al agente) para que
                // no quede una tecla "pegada" en la PC.
                if (!activado && modificadoresPresionados.isNotEmpty()) {
                    modificadoresPresionados.toList().forEach { key -> enviarTecla(key, false) }
                    modificadoresPresionados.clear()
                    botonesModificadores.values.forEach { it.background = crearFondoConPresion(Colores.BOTON) }
                }
                actualizarModoTecladoPc()
            }
        }
        contenedor.addView(switchCombinaciones)

        // Grilla de 6 filas x 7 columnas (segun el boceto). Se usa GridLayout
        // (no filas sueltas de LinearLayout) porque la celda de abajo a la
        // derecha ocupa 2 FILAS, algo que un LinearLayout por fila no puede
        // expresar ya que cada fila seria un contenedor independiente.
        val filas = 6
        val columnas = 7
        val altoCelda = dp(46)

        // Cada celda lleva un margen de dp(2) por lado (ver setMargins mas
        // abajo), es decir dp(4) EXTRA de ancho/alto por celda que se suma
        // AL COSTADO del tamanio de la celda (no lo reemplaza). Eso se
        // descuenta ANTES de dividir para que las 7 columnas + sus margenes
        // calcen justo con el ancho disponible.
        val margenPorCelda = dp(4) // dp(2) de un lado + dp(2) del otro
        val anchoDisponible = resources.displayMetrics.widthPixels - (columnas * margenPorCelda)
        val anchoCelda = anchoDisponible / columnas

        val grid = GridLayout(this).apply {
            rowCount = filas
            columnCount = columnas
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        gridTecladoPc = grid

        // Agrega un boton en (fila, columna), pudiendo ocupar varias
        // filas/columnas, y devuelve la vista creada (para poder guardar
        // una referencia, como se hace con los botones modificadores).
        //
        // OJO con las celdas que ocupan mas de 1 fila/columna (colSpan o
        // rowSpan > 1): como cada celda normal tiene margen dp(2) A CADA
        // LADO, dos celdas normales pegadas suman dp(4) de "hueco" entre
        // ellas ademas de sus anchos. Pero una celda combinada (colSpan=2,
        // por ej.) es UNA SOLA vista: solo tiene margen en sus 2 bordes
        // EXTERIORES, no ese hueco interno extra que existiria entre dos
        // celdas separadas. Si no se compensa, la celda combinada queda mas
        // chica que el espacio real que deberia cubrir, y su borde no
        // coincide con las columnas/filas de al lado. Por eso se le suma
        // (span - 1) * margenPorCelda: el "hueco interno" que le faltaria.
        //
        // Las celdas con icono usan ImageButton en vez de Button. Con un
        // Button vacio de texto (text = "") y un compound drawable arriba,
        // Android igual reserva el alto de una linea de texto "fantasma"
        // debajo del icono (el hueco donde iria el texto), y ese bloque
        // completo (icono + linea invisible) es lo que queda centrado, no
        // el icono en si: el icono termina corrido hacia arriba dentro del
        // boton. ImageButton no tiene ese problema porque no maneja texto
        // en absoluto, asi que el drawable queda perfectamente centrado.
        //
        // añadirAlGrid controla si la celda se agrega al GridLayout apenas
        // se crea (true, comportamiento de siempre) o si solo se arma y se
        // devuelve sin agregar (false): esto ultimo se usa para las celdas
        // del modo "letras", que no deben verse hasta que se active
        // "Combinaciones" (ver actualizarModoTecladoPc).
        fun agregarCelda(fila: Int, columna: Int, rowSpan: Int = 1, colSpan: Int = 1, texto: String = "", iconoResId: Int? = null, repetir: Boolean = false, accion: (() -> Unit)? = null, añadirAlGrid: Boolean = true): View {
            val view: View = if (iconoResId != null) {
                ImageButton(this).apply {
                    setImageResource(iconoResId)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    // Un poco de aire alrededor del icono para que no quede
                    // pegado a los bordes del boton (mismo efecto que tienen
                    // los botones con texto gracias a su padding interno).
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = crearFondoConPresion(Colores.BOTON)
                    minimumWidth = 0
                    minimumHeight = 0
                    stateListAnimator = null
                    elevation = 0f
                }
            } else {
                Button(this).apply {
                    isAllCaps = false
                    text = texto
                    // Se saca todo el "relleno" que Android le pone por
                    // defecto a un Button (padding interno, ancho/alto
                    // minimo, sombra al tocar/enfocar), para que el tamanio
                    // real siempre coincida con el que se calcula abajo
                    // (anchoCelda / altoCelda), sin sorpresas del estilo del
                    // sistema.
                    setPadding(0, 0, 0, 0)
                    minWidth = 0
                    minHeight = 0
                    stateListAnimator = null
                    elevation = 0f
                }.also { aplicarEstiloBoton(it); it.background = crearFondoConPresion(Colores.BOTON) }
            }
            // GridLayout.CENTER (en vez de dejar el default, que es
            // alineacion por BASELINE de texto) evita que una celda sin
            // texto quede desplazada respecto a sus vecinas que si tienen
            // texto.
            view.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(fila, rowSpan, GridLayout.CENTER),
                GridLayout.spec(columna, colSpan, GridLayout.CENTER)
            ).apply {
                width = anchoCelda * colSpan + (colSpan - 1) * margenPorCelda
                height = altoCelda * rowSpan + (rowSpan - 1) * margenPorCelda
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            if (accion != null) {
                view.setOnClickListener {
                    avisarSiDesconectado()
                    accion()
                }
            }
            if (repetir && accion != null) {
                configurarTecleoConRepeticion(view, accion)
            }
            if (añadirAlGrid) grid.addView(view)
            return view
        }

        for (fila in 0 until filas) {
            for (columna in 0 until columnas) {
                when {
                    // Celda ancha (2 columnas): fila 2 del boceto (indice 1),
                    // columnas 6-7 (indices 5-6). Backspace. Fija: no cambia
                    // con el modo "Combinaciones" (el boceto de letras
                    // tambien la deja en el mismo lugar).
                    fila == 1 && columna == 5 -> agregarCelda(fila, columna, colSpan = 2, iconoResId = R.drawable.ic_backspace, repetir = true, accion = {
                        enviarTecla("backspace", true)
                        enviarTecla("backspace", false)
                    })
                    fila == 1 && columna == 6 -> Unit // cubierta por la celda de arriba

                    // Celda alta (2 filas): columna 7 del boceto (indice 6),
                    // filas 5-6 (indices 4-5). Enter. Ademas de mandar la
                    // tecla al agente, limpia el panel de "Teclado" (igual
                    // que el Enter nativo del teclado del celular), para que
                    // no quede el texto ya enviado dando vueltas ahi si el
                    // usuario manda el Enter desde esta pestania.
                    fila == 4 && columna == 6 -> agregarCelda(fila, columna, rowSpan = 2, iconoResId = R.drawable.ic_enter, accion = {
                        enviarTecla("enter", true)
                        enviarTecla("enter", false)
                        limpiarPanelEscritura()
                    })
                    fila == 5 && columna == 6 -> Unit // cubierta por la celda de arriba

                    // F1,C7: cerrar ventana (Alt+F4). Icono SVG convertido a
                    // vector drawable. Forma parte del modo "iconos": con
                    // "Combinaciones" activado, esta celda se reemplaza por
                    // la letra G (ver celdasModoLetras).
                    fila == 0 && columna == 6 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_cerrar, añadirAlGrid = false, accion = { enviarCombo("alt+f4") }))

                    // F1,C6: minimizar ventana (Alt+Espacio abre el menu de sistema, N minimiza). Se representa con "-".
                    // (Trasladado desde F1,C5.)
                    fila == 0 && columna == 5 -> celdasModoIconos.add(agregarCelda(fila, columna, texto = "-", añadirAlGrid = false, accion = {
                        enviarCombo("alt+space")
                        enviarTecla("n", true)
                        enviarTecla("n", false)
                    }))

                    // F1,C5: cambiar entre ventanas (Win+Tab). Icono SVG convertido a vector drawable.
                    // (Trasladado desde F1,C4.)
                    fila == 0 && columna == 4 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_cambiar_ventana, añadirAlGrid = false, accion = { enviarCombo("win+tab") }))

                    // F1,C4: mas brillo. Icono SVG convertido a vector drawable.
                    fila == 0 && columna == 3 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_mas_brillo, añadirAlGrid = false, accion = {
                        enviarJson(JSONObject().apply { put("type", "brightness_up") })
                    }))

                    // F1,C3: menos brillo. Icono SVG convertido a vector drawable.
                    fila == 0 && columna == 2 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_menos_brillo, añadirAlGrid = false, accion = {
                        enviarJson(JSONObject().apply { put("type", "brightness_down") })
                    }))

                    // F1,C1: deshacer (Ctrl+Z). Icono SVG convertido a vector drawable.
                    fila == 0 && columna == 0 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_deshacer, añadirAlGrid = false, accion = { enviarCombo("ctrl+z") }))

                    // F1,C2: rehacer (Ctrl+Y). Icono SVG convertido a vector drawable.
                    fila == 0 && columna == 1 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_rehacer, añadirAlGrid = false, accion = { enviarCombo("ctrl+y") }))

                    // F5,C5: flecha arriba.
                    fila == 4 && columna == 4 -> agregarCelda(fila, columna, iconoResId = R.drawable.ic_arriba, repetir = true, accion = {
                        enviarTecla("arrowup", true)
                        enviarTecla("arrowup", false)
                    })

                    // F6,C5: flecha abajo.
                    fila == 5 && columna == 4 -> agregarCelda(fila, columna, iconoResId = R.drawable.ic_abajo, repetir = true, accion = {
                        enviarTecla("arrowdown", true)
                        enviarTecla("arrowdown", false)
                    })

                    // F6,C3: tecla Alt. Con "Combinaciones" activado, se
                    // sostiene/suelta en vez de tocar simple (ver
                    // manejarToqueModificador).
                    fila == 5 && columna == 2 -> {
                        val view = agregarCelda(fila, columna, texto = "Alt")
                        botonesModificadores["alt"] = view
                        view.setOnClickListener {
                            avisarSiDesconectado()
                            manejarToqueModificador("alt", view)
                        }
                    }

                    // F5,C2: tecla Tab. Al lado de Shift. Con "Combinaciones"
                    // activado se comporta igual que Ctrl/Alt/Shift/Win: se
                    // sostiene/suelta en vez de tocar simple (ver
                    // manejarToqueModificador), para poder armar combos como
                    // Alt+Tab (sostener Alt, tocar Tab, soltar Alt) o
                    // Ctrl+Shift+Tab. Se guarda tambien en
                    // botonesModificadores para que se le devuelva el color
                    // normal al desconectar o al desactivar el switch, igual
                    // que a los demas modificadores.
                    fila == 4 && columna == 1 -> {
                        val view = agregarCelda(fila, columna, texto = "Tab")
                        botonesModificadores["tab"] = view
                        view.setOnClickListener {
                            avisarSiDesconectado()
                            manejarToqueModificador("tab", view)
                        }
                    }

                    // F6,C4: flecha izquierda.
                    fila == 5 && columna == 3 -> agregarCelda(fila, columna, iconoResId = R.drawable.ic_izquierda, repetir = true, accion = {
                        enviarTecla("arrowleft", true)
                        enviarTecla("arrowleft", false)
                    })

                    // F6,C6: flecha derecha.
                    fila == 5 && columna == 5 -> agregarCelda(fila, columna, iconoResId = R.drawable.ic_derecha, repetir = true, accion = {
                        enviarTecla("arrowright", true)
                        enviarTecla("arrowright", false)
                    })

                    // F6,C1: tecla Ctrl. Con "Combinaciones" activado, se
                    // sostiene/suelta en vez de tocar simple (ver
                    // manejarToqueModificador).
                    fila == 5 && columna == 0 -> {
                        val view = agregarCelda(fila, columna, texto = "Ctrl")
                        botonesModificadores["ctrl"] = view
                        view.setOnClickListener {
                            avisarSiDesconectado()
                            manejarToqueModificador("ctrl", view)
                        }
                    }

                    // F5,C1: tecla Shift. Con "Combinaciones" activado, se
                    // sostiene/suelta en vez de tocar simple (ver
                    // manejarToqueModificador).
                    fila == 4 && columna == 0 -> {
                        val view = agregarCelda(fila, columna, texto = "Shift")
                        botonesModificadores["shift"] = view
                        view.setOnClickListener {
                            avisarSiDesconectado()
                            manejarToqueModificador("shift", view)
                        }
                    }

                    // F6,C2: tecla Windows (Win). Con "Combinaciones"
                    // activado, se sostiene/suelta en vez de tocar simple
                    // (ver manejarToqueModificador).
                    fila == 5 && columna == 1 -> {
                        val view = agregarCelda(fila, columna, iconoResId = R.drawable.ic_win)
                        botonesModificadores["win"] = view
                        view.setOnClickListener {
                            avisarSiDesconectado()
                            manejarToqueModificador("win", view)
                        }
                    }

                    // F2,C1: copiar (Ctrl+C).
                    fila == 1 && columna == 0 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_copiar, añadirAlGrid = false, accion = { enviarCombo("ctrl+c") }))

                    // F2,C2: pegar (Ctrl+V).
                    fila == 1 && columna == 1 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_pegar, añadirAlGrid = false, accion = { enviarCombo("ctrl+v") }))

                    // F2,C3: cortar (Ctrl+X).
                    fila == 1 && columna == 2 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_cortar, añadirAlGrid = false, accion = { enviarCombo("ctrl+x") }))

                    // F2,C4: portapapeles (Win+V).
                    fila == 1 && columna == 3 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_portapapeles, añadirAlGrid = false, accion = { enviarCombo("win+v") }))

                    // F2,C5: seleccionar todo (Ctrl+A).
                    fila == 1 && columna == 4 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_seleccionar_todo, añadirAlGrid = false, accion = { enviarCombo("ctrl+a") }))

                    // F3,C1: silenciar/activar volumen (mute).
                    fila == 2 && columna == 0 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_mute, añadirAlGrid = false, accion = {
                        enviarTecla("volumemute", true)
                        enviarTecla("volumemute", false)
                    }))

                    // F3,C2: bajar volumen.
                    fila == 2 && columna == 1 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_volumen_bajar, añadirAlGrid = false, accion = {
                        enviarTecla("volumedown", true)
                        enviarTecla("volumedown", false)
                    }))

                    // F3,C3: subir volumen.
                    fila == 2 && columna == 2 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_volumen_subir, añadirAlGrid = false, accion = {
                        enviarTecla("volumeup", true)
                        enviarTecla("volumeup", false)
                    }))

                    // F3,C4: pista anterior.
                    fila == 2 && columna == 3 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_anterior, añadirAlGrid = false, accion = {
                        enviarTecla("mediaprevioustrack", true)
                        enviarTecla("mediaprevioustrack", false)
                    }))

                    // F3,C5: reproducir/pausar.
                    fila == 2 && columna == 4 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_reproducir_pausa, añadirAlGrid = false, accion = {
                        enviarTecla("mediaplaypause", true)
                        enviarTecla("mediaplaypause", false)
                    }))

                    // F3,C6: pista siguiente.
                    fila == 2 && columna == 5 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_siguiente, añadirAlGrid = false, accion = {
                        enviarTecla("medianexttrack", true)
                        enviarTecla("medianexttrack", false)
                    }))

                    // F3,C7: captura la pantalla del CELULAR y la manda a la
                    // PC (el agente de Windows la copia al portapapeles).
                    // Muestra el boton flotante (ver solicitarCapturaPantallaMovil):
                    // la captura real recien se dispara cuando el usuario
                    // toque ESE boton, no al tocar esta tecla.
                    fila == 2 && columna == 6 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_captura_pantalla_movil, añadirAlGrid = false, accion = {
                        solicitarCapturaPantallaMovil()
                    }))

                    // F4,C1: eliminar (Delete).
                    fila == 3 && columna == 0 -> celdasModoIconos.add(agregarCelda(fila, columna, iconoResId = R.drawable.ic_eliminar, añadirAlGrid = false, repetir = true, accion = {
                        enviarTecla("delete", true)
                        enviarTecla("delete", false)
                    }))

                    // Resto de celdas de F1-F4 sin uso especifico todavia
                    // (por ahora solo la fila F4 completa): quedan vacias en
                    // modo "iconos", pero tambien entran en celdasModoIconos
                    // para que el toggle de "Combinaciones" las reemplace
                    // por letras igual que a las demas de esas 4 filas. F5 y
                    // F6 nunca entran aca: quedan fijas siempre. Estas celdas
                    // van con añadirAlGrid = false, igual que el resto de
                    // celdasModoIconos: si no, quedan agregadas al grid DOS
                    // VECES (una aca y otra dentro de actualizarModoTecladoPc,
                    // que ahora se llama tambien al abrir la app), lo que
                    // tira IllegalStateException ("child already has a
                    // parent") y crashea la app apenas se abre.
                    else -> {
                        if (fila in 0..3) {
                            celdasModoIconos.add(agregarCelda(fila, columna, añadirAlGrid = false))
                        } else {
                            agregarCelda(fila, columna)
                        }
                    }
                }
            }
        }

        // ==== Modo "Combinaciones": letras A-Z superpuestas en las mismas
        // 4 filas de arriba (F1-F4), reemplazando esos iconos mientras el
        // switch esta activo. Se arman una sola vez aca, con añadirAlGrid =
        // false (el modo inicial es "iconos", asi que no se agregan al
        // GridLayout todavia), y se van intercambiando con celdasModoIconos
        // en actualizarModoTecladoPc(). F2 (fila 1) solo cubre columnas
        // 0-4: las columnas 5-6 son el backspace, que es fijo.
        val filasLetras = listOf(
            0 to "ABCDEFG",
            1 to "HIJKL",
            2 to "MNOPQRS",
            3 to "TUVWXYZ"
        )
        for ((fila, letras) in filasLetras) {
            for ((columna, letra) in letras.withIndex()) {
                val texto = letra.toString()
                celdasModoLetras.add(
                    agregarCelda(fila, columna, texto = texto, añadirAlGrid = false, accion = {
                        val tecla = texto.lowercase()
                        enviarTecla(tecla, true)
                        enviarTecla(tecla, false)
                    })
                )
            }
        }

        // Sincroniza el estado inicial de la grilla: al construir la
        // pantalla, celdasModoIconos y celdasModoLetras ya existen pero
        // ninguna esta agregada al grid todavia (todas se crearon con
        // añadirAlGrid = false). Sin esta llamada, F1-F4 arrancan vacias y
        // recien se completan al tocar el switch "Combinaciones" una vez
        // (ese toggle dispara actualizarModoTecladoPc desde su listener,
        // que es la unica parte del codigo que agregaba las celdas al
        // grid). Llamarla aca agrega el juego que corresponde segun
        // combinacionesActivas (iconos, ya que arranca en false) desde el
        // primer momento en que se abre la pestania "Teclas PC".
        actualizarModoTecladoPc()

        contenedor.addView(grid)
        return contenedor
    }
}
