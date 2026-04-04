package org.des.vesta.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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

    private val client = HttpClient(OkHttp) {
        engine {
            preconfigured = getUnsafeOkHttpClient()
        }

        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
            pingIntervalMillis = 20_000
        }

        install(ContentNegotiation) {
            json(json)
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private val sendMutex = Mutex()

    private val _incomingPackets = MutableSharedFlow<Packet>()
    val incomingPackets = _incomingPackets.asSharedFlow()

    private val _onConnected = MutableSharedFlow<Unit>()
    val onConnected = _onConnected.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        scope.launch {
            var retryDelay = 1000L
            while (isActive) {
                try {
                    println("WS: Connecting to wss://$host...")
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
                        println("WS: Connected")
                        retryDelay = 1000L
                        session = this
                        _isConnected.value = true
                        _onConnected.emit(Unit)

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    try {
                                        val packet = json.decodeFromString<Packet>(frame.readText())
                                        _incomingPackets.emit(packet)
                                    } catch (e: Exception) {
                                        println("WS Parse Error: ${e.message}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            println("WS Session Error: ${e.message}")
                        } finally {
                            println("WS: Disconnected")
                            _isConnected.value = false
                            session = null
                        }
                    }
                } catch (e: Exception) {
                    println("WS Connection Error: ${e.message}")
                    _isConnected.value = false
                    session = null
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(30000L)
                }
            }
        }
    }

    suspend fun sendPacket(packet: Packet): Boolean {
        val currentSession = session ?: return false
        if (!currentSession.isActive) return false

        return try {
            withTimeout(15_000) {
                sendMutex.withLock {
                    currentSession.sendSerialized(packet)
                    true
                }
            }
        } catch (e: Exception) {
            println("WS Send Failure (${packet.action}): ${e.message}")
            try {
                currentSession.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Send failure"))
            } catch (_: Exception) {}
            false
        }
    }

    fun close() {
        scope.cancel()
        client.close()
    }
}

fun getUnsafeOkHttpClient(): OkHttpClient {
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    return OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}
