package org.des.vesta.network
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import io.ktor.client.engine.okhttp.* // Для OkHttp и preconfigured

@Serializable
data class Packet(
    val action: String,
    val id: String? = null,
    val name: String? = null,
    val pub_key: String? = null,
    val target: String? = null,
    val to: String? = null,
    val from: String? = null,
    val data: JsonObject? = null
)

class MessengerClient(private val host: String) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val client = HttpClient(OkHttp) { // Добавили движок OkHttp
        engine {
            // Подключаем наш "взломщик" проверок SSL
            preconfigured = getUnsafeOkHttpClient()
        }

        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
            // СТРОКУ С maxFrameSize УДАЛИЛИ, чтобы OkHttp не ругался
        }

        install(ContentNegotiation) {
            json(json)
        }
    }
    private var session: DefaultClientWebSocketSession? = null

    private val _incomingPackets = MutableSharedFlow<Packet>()
    val incomingPackets = _incomingPackets.asSharedFlow()

    private val _onConnected = MutableSharedFlow<Unit>()
    val onConnected = _onConnected.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        scope.launch {
            while (isActive) {
                try {
                    client.wss(
                        method = HttpMethod.Get,
                        host = host,
                        path = "/",
                        request = {
                            headers {
                                append("ngrok-skip-browser-warning", "true")
                            }
                        }
                    ) {
                        session = this
                        _isConnected.value = true
                        _onConnected.emit(Unit)

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    try {
                                        val packet = json.decodeFromString<Packet>(text)
                                        _incomingPackets.emit(packet)
                                    } catch (e: Exception) {
                                        println("WS Parse Error: ${e.message}")
                                    }
                                }
                            }
                        } finally {
                            _isConnected.value = false
                            session = null
                        }
                    }
                } catch (e: Exception) {
                    println("WS Connection Error: ${e.message}")
                    _isConnected.value = false
                    delay(5000)
                }
            }
        }
    }

    suspend fun sendPacket(packet: Packet): Boolean {
        val currentSession = session
        return if (currentSession != null && currentSession.isActive) {
            try {
                currentSession.sendSerialized(packet)
                true
            } catch (e: Exception) {
                println("WS Send Error: ${e.message}")
                false
            }
        } else {
            false
        }
    }
}


// Эту функцию можно положить прямо в конец твоего Kotlin файла
fun getUnsafeOkHttpClient(): OkHttpClient {
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    val sslContext = SSLContext.getInstance("SSL")
    sslContext.init(null, trustAllCerts, SecureRandom())

    return OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true } // Вот это лечит ошибку "hostname aware"
        .build()
}