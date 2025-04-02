package poc.rat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class ConnectionService : Service() {
    private val SERVER_IP = "192.168.1.24"
    private val SERVER_PORT = 3000
    private lateinit var serverSocket: Socket
    private lateinit var writer: PrintWriter
    private lateinit var reader: BufferedReader

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, "CHANNEL_ID")
            .setContentTitle("RAT Client Running")
            .setContentText("Listening for server commands...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        startForeground(1, notification)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = Socket(SERVER_IP, SERVER_PORT)
                writer = PrintWriter(serverSocket.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(serverSocket.getInputStream()))
                writer.println("phone connected")
                listenForCommands()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun listenForCommands() {
        while (true) {
            val command = reader.readLine() ?: break
            println("Received command: $command")
            handleCommand(command)
        }
    }

    private fun handleCommand(command: String) {
        println("Handling command in service: $command")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            serverSocket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "CHANNEL_ID",
                "RAT Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
