package com.tecladomovil.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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

    // True mientras el boton "Presion" esta activo: el click izquierdo del
    // mouse quedo sostenido (left-down mandado, left-up todavia no) para que
    // el usuario pueda arrastrar con el touchpad y seleccionar cosas sin
    // tener que mantener el dedo quieto un instante como en el modo
    // long-press del touchpad.
    private var presionSostenida = false

    private var textoPrevio = ""

    // Se pone en true justo antes de limpiar el campo de texto (por Enter),
    // para que el TextWatcher no interprete esa limpieza como una edicion
    // real y mande un "delete" de mas hacia el agente de Windows.
    private var ignorarProximoCambio = false

    // --- Conexion con el servicio en primer plano ---
    private var servicio: ConexionService? = null
    private var servicioLigado = false

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }

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

            val connectButton = Button(this@MainActivity).apply {
                text = "Conectar"
                setOnClickListener { conectar() }
            }
            aplicarEstiloBoton(connectButton)
            addView(connectButton)

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

    // ==================== TECLADO (envio de texto) ====================

    private fun buildPanelTeclado(): EditText {
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
                    procesarCambioTexto(textoPrevio, nuevo)
                    textoPrevio = nuevo
                }
            })
        }
        aplicarEstiloCampo(escrituraInput)
        return escrituraInput
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
            setPadding(0, dp(92), 0, 0)
        }

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

    // ==================== TECLAS PC ====================
    // Grilla de 6x7 casilleros (segun el boceto), con 2 celdas especiales
    // mas grandes (una ancha de 2 columnas, otra alta de 2 filas). Todos los
    // botones estan vacios por ahora: la tecla/accion de cada uno se agrega
    // despues, segun las indicaciones que se vayan dando celda por celda.

    private fun buildPanelTecladoPc(): LinearLayout {
        val contenedor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

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

        // Agrega un boton vacio (sin tecla asignada todavia: eso viene
        // despues, seccion por seccion, segun indicaciones) en (fila,
        // columna), pudiendo ocupar varias filas/columnas.
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
        fun agregarCelda(fila: Int, columna: Int, rowSpan: Int = 1, colSpan: Int = 1) {
            val boton = Button(this).apply {
                text = ""
                isAllCaps = false
            }
            aplicarEstiloBoton(boton)
            boton.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(fila, rowSpan),
                GridLayout.spec(columna, colSpan)
            ).apply {
                width = anchoCelda * colSpan + (colSpan - 1) * margenPorCelda
                height = altoCelda * rowSpan + (rowSpan - 1) * margenPorCelda
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            grid.addView(boton)
        }

        for (fila in 0 until filas) {
            for (columna in 0 until columnas) {
                when {
                    // Celda ancha (2 columnas): fila 2 del boceto (indice 1),
                    // columnas 6-7 (indices 5-6).
                    fila == 1 && columna == 5 -> agregarCelda(fila, columna, colSpan = 2)
                    fila == 1 && columna == 6 -> Unit // cubierta por la celda de arriba

                    // Celda alta (2 filas): columna 7 del boceto (indice 6),
                    // filas 5-6 (indices 4-5).
                    fila == 4 && columna == 6 -> agregarCelda(fila, columna, rowSpan = 2)
                    fila == 5 && columna == 6 -> Unit // cubierta por la celda de arriba

                    else -> agregarCelda(fila, columna)
                }
            }
        }

        contenedor.addView(grid)
        return contenedor
    }
}
