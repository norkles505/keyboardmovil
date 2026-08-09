package com.tecladomovil.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

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
    private val client = OkHttpClient()
    private var socket: WebSocket? = null

    var connected = false
        private set
    var listener: ConexionListener? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
        startForeground(NOTIF_ID, construirNotificacion("Sin conectar"))
    }

    /**
     * Si el usuario mata la app desde la lista de recientes (la "cierra por
     * completo"), cortamos la conexion y el servicio en vez de dejarlo
     * colgado en segundo plano para siempre.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        socket?.close(1000, "app cerrada")
        connected = false
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
                listener?.onDesconectado(t.message ?: "Error de conexion")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                actualizarNotificacion("Desconectado")
                listener?.onDesconectado("Desconectado")
            }
        })
    }

    fun enviarJson(json: String) {
        if (connected) socket?.send(json)
    }

    fun desconectarManualmente() {
        socket?.close(1000, "desconexion manual")
        connected = false
        actualizarNotificacion("Sin conectar")
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
}
