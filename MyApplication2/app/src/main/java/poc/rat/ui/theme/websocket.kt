package poc.rat.ui.theme
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class SimpleWebSocketListener : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
        // Connection established
        webSocket.send("Hello from Android!")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        // Received a text message
        println("Received text: $text")
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        // Received a binary message
        println("Received bytes: ${bytes.hex()}")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        println("Closing: $code / $reason")
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        // Handle error
        t.printStackTrace()
    }
}

