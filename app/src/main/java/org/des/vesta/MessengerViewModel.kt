package org.des.vesta

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.des.vesta.crypto.CryptoEngine
import org.des.vesta.data.AppDatabase
import org.des.vesta.data.Chat
import org.des.vesta.data.Message
import org.des.vesta.data.Setting
import org.des.vesta.network.MessengerClient
import org.des.vesta.network.Packet
import java.util.*

class MessengerViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val dao = db.messengerDao()
    private var crypto = CryptoEngine()
    private val client = MessengerClient("herringlike-unneedy-benson.ngrok-free.dev")

    private val _myId = MutableStateFlow("")
    val myId: StateFlow<String> = _myId

    private val _userName = MutableStateFlow("User")
    val userName: StateFlow<String> = _userName

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    val allChats: StateFlow<List<Chat>> = dao.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadIdentity()
        client.connect()
        observeIncomingPackets()
        
        viewModelScope.launch {
            client.onConnected.collect {
                if (_myId.value.isNotEmpty()) {
                    register()
                }
            }
        }
    }

    private fun loadIdentity() {
        viewModelScope.launch {
            var id = dao.getSetting("my_id")
            val name = dao.getSetting("user_name") ?: "User"
            val theme = dao.getSetting("theme") ?: "Light"
            
            val privPem = dao.getSetting("private_key_pem")
            val pubPem = dao.getSetting("public_key_pem")

            if (id == null || privPem == null || pubPem == null) {
                id = (0..5).map { "0123456789ABCDEF".random() }.joinToString("")
                val newCrypto = CryptoEngine()
                
                dao.setSetting(Setting("my_id", id))
                dao.setSetting(Setting("user_name", name))
                dao.setSetting(Setting("private_key_pem", newCrypto.getPrivateKeyPem()))
                dao.setSetting(Setting("public_key_pem", newCrypto.getPublicKeyPem()))
                
                crypto = newCrypto
            } else {
                try {
                    crypto = CryptoEngine(privPem, pubPem)
                } catch (e: Exception) {
                    crypto = CryptoEngine()
                }
            }
            
            _myId.value = id
            _userName.value = name
            _isDarkMode.value = theme == "Dark"
            
            if (client.isConnected.value) {
                register()
            }
        }
    }
// i don't care that i should be uppercase
    private fun register() {
        viewModelScope.launch {
            client.sendPacket(Packet(
                action = "register",
                id = _myId.value,
                name = _userName.value,
                pub_key = crypto.getPublicKeyPem()
            ))
        }
    }

    fun updateProfile(newName: String) {
        viewModelScope.launch {
            _userName.value = newName
            dao.setSetting(Setting("user_name", newName))
            client.sendPacket(Packet(
                action = "update_profile",
                name = newName
            ))
        }
    }

    fun toggleTheme(isDark: Boolean) {
        viewModelScope.launch {
            _isDarkMode.value = isDark
            dao.setSetting(Setting("theme", if (isDark) "Dark" else "Light"))
        }
    }

    private fun observeIncomingPackets() {
        viewModelScope.launch {
            client.incomingPackets.collect { packet ->
                when (packet.action) {
                    "incoming" -> handleIncomingPacket(packet)
                    "pub_key_response" -> handlePubKeyResponse(packet)
                }
            }
        }
    }

    private suspend fun handleIncomingPacket(packet: Packet) {
        val from = packet.from ?: return
        val data = packet.data ?: return
        val type = data["type"]?.jsonPrimitive?.content ?: return

        when (type) {
            "msg" -> {
                val chat = dao.getChat(from) ?: return
                val contentEncrypted = data["content"]?.jsonPrimitive?.content ?: return
                val fernetKey = chat.fernetKey ?: return
                val msgType = data["msg_type"]?.jsonPrimitive?.content ?: "text"
                
                try {
                    val decrypted = crypto.decryptFernet(contentEncrypted, fernetKey)
                    val msg = Message(
                        contactId = from,
                        senderId = from,
                        senderName = chat.displayName ?: from,
                        msgType = msgType,
                        content = decrypted,
                        timestamp = System.currentTimeMillis().toString()
                    )
                    dao.insertMessage(msg)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "handshake" -> {
                val encryptedKey = data["key"]?.jsonPrimitive?.content ?: return
                val peerName = data["name"]?.jsonPrimitive?.content ?: from
                
                try {
                    val decryptedKey = crypto.decryptFernetKey(encryptedKey)
                    val chat = Chat(
                        contactId = from,
                        fernetKey = decryptedKey,
                        status = "active",
                        displayName = peerName,
                        lastMsgTs = System.currentTimeMillis().toString()
                    )
                    dao.insertChat(chat)
                    
                    client.sendPacket(Packet(
                        action = "route",
                        to = from,
                        data = buildJsonObject {
                            put("type", "handshake_ok")
                            put("name", _userName.value)
                        }
                    ))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "handshake_ok" -> {
                val peerName = data["name"]?.jsonPrimitive?.content ?: from
                val chat = dao.getChat(from)
                if (chat != null) {
                    dao.insertChat(chat.copy(displayName = peerName, status = "active"))
                }
            }
        }
    }

    private suspend fun handlePubKeyResponse(packet: Packet) {
        val target = packet.target ?: return
        val peerPubKey = packet.pub_key ?: return
        
        try {
            val sessionKey = crypto.generateFernetKey()
            val encryptedKey = crypto.encryptFernetKey(sessionKey, peerPubKey)
            
            val chat = Chat(
                contactId = target,
                fernetKey = sessionKey,
                status = "pending",
                displayName = packet.name ?: target,
                lastMsgTs = System.currentTimeMillis().toString()
            )
            dao.insertChat(chat)
            
            client.sendPacket(Packet(
                action = "route",
                to = target,
                data = buildJsonObject {
                    put("type", "handshake")
                    put("key", encryptedKey)
                    put("name", _userName.value)
                }
            ))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendMessage(contactId: String, content: String, isImage: Boolean = false) {
        viewModelScope.launch {
            val chat = dao.getChat(contactId) ?: return@launch
            val fernetKey = chat.fernetKey ?: return@launch
            
            try {
                val encrypted = crypto.encryptFernet(content, fernetKey)
                val msgType = if (isImage) "image" else "text"
                
                client.sendPacket(Packet(
                    action = "route",
                    to = contactId,
                    data = buildJsonObject {
                        put("type", "msg")
                        put("msg_type", msgType)
                        put("content", encrypted)
                        put("sender_name", _userName.value)
                    }
                ))

                val msg = Message(
                    contactId = contactId,
                    senderId = _myId.value,
                    senderName = _userName.value,
                    msgType = msgType,
                    content = content,
                    timestamp = System.currentTimeMillis().toString()
                )
                dao.insertMessage(msg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addContact(targetId: String) {
        viewModelScope.launch {
            client.sendPacket(Packet(
                action = "get_pub_key",
                target = targetId.uppercase()
            ))
        }
    }

    fun getMessages(contactId: String): Flow<List<Message>> = dao.getMessages(contactId)
}
