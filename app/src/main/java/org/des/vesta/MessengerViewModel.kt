package org.des.vesta

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.des.vesta.crypto.CryptoEngine
import org.des.vesta.data.AppDatabase
import org.des.vesta.data.Chat
import org.des.vesta.data.Message
import org.des.vesta.data.Setting
import org.des.vesta.network.Packet
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

class MessengerViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val dao = db.messengerDao()
    private var crypto = CryptoEngine()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    private val _myId = MutableStateFlow("")
    val myId: StateFlow<String> = _myId

    private val _userName = MutableStateFlow("User")
    val userName: StateFlow<String> = _userName

    private val _userAvatar = MutableStateFlow<String?>(null)
    val userAvatar: StateFlow<String?> = _userAvatar

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    private val _showLinkWarning = MutableStateFlow(true)
    val showLinkWarning: StateFlow<Boolean> = _showLinkWarning

    // QoL: Track currently opened chat to handle unread logic
    private var currentChatId: String? = null

    private val incomingChunks = mutableMapOf<String, MutableMap<Int, String>>()
    private val incomingChunksTotal = mutableMapOf<String, Int>()

    val allChats: StateFlow<List<Chat>> = dao.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadIdentity()
        observeIncomingPackets()
    }

    private fun loadIdentity() {
        viewModelScope.launch {
            var id = dao.getSetting("my_id")
            val name = dao.getSetting("user_name") ?: "User"
            val avatar = dao.getSetting("user_avatar")
            val theme = dao.getSetting("theme") ?: "Light"
            val linkWarn = dao.getSetting("link_warning") ?: "True"
            
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
            _userAvatar.value = avatar
            _isDarkMode.value = theme == "Dark"
            _showLinkWarning.value = linkWarn == "True"
            
            MessengerService.instance?.register(_myId.value, _userName.value, crypto.getPublicKeyPem(), _userAvatar.value)
        }
    }

    fun deleteChat(contactId: String) {
        viewModelScope.launch {
            dao.deleteMessagesForChat(contactId)
            dao.deleteChat(contactId)
        }
    }

    fun setChatPinned(contactId: String, pinned: Boolean) {
        viewModelScope.launch {
            dao.setChatPinned(contactId, pinned)
        }
    }

    fun setCurrentChat(contactId: String?) {
        currentChatId = contactId
        if (contactId != null) {
            clearUnreadCount(contactId)
        }
    }

    fun clearUnreadCount(contactId: String) {
        viewModelScope.launch {
            dao.clearUnreadCount(contactId)
            dao.markChatAsRead(contactId)
        }
    }

    fun updateDraft(contactId: String, draft: String?) {
        viewModelScope.launch {
            dao.updateDraft(contactId, draft)
        }
    }

    fun updateProfile(newName: String) {
        viewModelScope.launch {
            _userName.value = newName
            dao.setSetting(Setting("user_name", newName))
             MessengerService.instance?.sendPacket(Packet(
                action = "update_profile",
                name = newName,
                avatar = _userAvatar.value
            ))
        }
    }

    fun updateAvatar(base64: String) {
        viewModelScope.launch {
            _userAvatar.value = base64
            dao.setSetting(Setting("user_avatar", base64))
            MessengerService.instance?.sendPacket(Packet(
                action = "update_profile",
                name = _userName.value,
                avatar = base64
            ))
        }
    }

    fun toggleTheme(isDark: Boolean) {
        viewModelScope.launch {
            _isDarkMode.value = isDark
            dao.setSetting(Setting("theme", if (isDark) "Dark" else "Light"))
        }
    }

    fun toggleLinkWarning(enabled: Boolean) {
        viewModelScope.launch {
            _showLinkWarning.value = enabled
            dao.setSetting(Setting("link_warning", if (enabled) "True" else "False"))
        }
    }

    private fun observeIncomingPackets() {
        viewModelScope.launch {
            MessengerService.incomingPackets.collect { packet ->
                when (packet.action) {
                    "incoming" -> handleIncomingPacket(packet)
                    "pub_key_response" -> handlePubKeyResponse(packet)
                    "profile_updated" -> handleProfileUpdated(packet)
                }
            }
        }
    }

    private suspend fun handleProfileUpdated(packet: Packet) {
        val contactId = packet.id ?: return
        val name = packet.name
        val avatar = packet.avatar
        val chat = dao.getChat(contactId)
        if (chat != null) {
            dao.insertChat(chat.copy(displayName = name ?: chat.displayName, avatar = avatar ?: chat.avatar))
        }
    }

    private suspend fun handleIncomingPacket(packet: Packet) {
        val from = packet.from ?: return
        val data = packet.data ?: return
        val type = data["type"]?.jsonPrimitive?.content ?: return

        when (type) {
            "msg", "sticker" -> {
                val chat = dao.getChat(from) ?: return
                val contentEncrypted = data["content"]?.jsonPrimitive?.content ?: return
                val fernetKey = chat.fernetKey ?: return
                val msgType = if (type == "sticker") "sticker" else (data["msg_type"]?.jsonPrimitive?.content ?: "text")
                
                try {
                    val decrypted = withContext(Dispatchers.Default) {
                        crypto.decryptFernet(contentEncrypted, fernetKey)
                    }
                    val msg = Message(
                        contactId = from,
                        senderId = from,
                        senderName = chat.displayName ?: from,
                        msgType = msgType,
                        content = decrypted,
                        timestamp = System.currentTimeMillis().toString()
                    )
                    dao.insertMessage(msg)
                    
                    // QoL Fix: Only increment unread count if user is NOT currently in this chat
                    val unreadIncrement = if (currentChatId == from) 0 else 1
                    val preview = if (msgType == "text") decrypted else if (msgType == "image") "Sent a photo" else "Sent a sticker"
                    dao.updateLastMessage(from, preview, msg.timestamp, unreadIncrement)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "chunk" -> {
                val msgId = data["msg_id"]?.jsonPrimitive?.content ?: return
                val index = data["index"]?.jsonPrimitive?.int ?: return
                val total = data["total"]?.jsonPrimitive?.int ?: return
                val chunkContent = data["content"]?.jsonPrimitive?.content ?: return
                val msgType = data["msg_type"]?.jsonPrimitive?.content ?: "text"
                val senderName = data["sender_name"]?.jsonPrimitive?.content ?: from

                val chunks = incomingChunks.getOrPut(msgId) { mutableMapOf() }
                chunks[index] = chunkContent
                incomingChunksTotal[msgId] = total

                if (chunks.size == total) {
                    val fullEncrypted = (0 until total).joinToString("") { chunks[it]!! }
                    incomingChunks.remove(msgId)
                    incomingChunksTotal.remove(msgId)
                    
                    val chat = dao.getChat(from) ?: return
                    val fernetKey = chat.fernetKey ?: return
                    
                    try {
                        val decrypted = withContext(Dispatchers.Default) {
                            crypto.decryptFernet(fullEncrypted, fernetKey)
                        }
                        val msg = Message(
                            contactId = from,
                            senderId = from,
                            senderName = senderName,
                            msgType = msgType,
                            content = decrypted,
                            timestamp = System.currentTimeMillis().toString()
                        )
                        dao.insertMessage(msg)
                        
                        val unreadIncrement = if (currentChatId == from) 0 else 1
                        val preview = if (msgType == "text") decrypted else if (msgType == "image") "Sent a photo" else "Sent a sticker"
                        dao.updateLastMessage(from, preview, msg.timestamp, unreadIncrement)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
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
                    
                    MessengerService.instance?.sendPacket(Packet(
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
            val (sessionKey, encryptedKey) = withContext(Dispatchers.Default) {
                val sKey = crypto.generateFernetKey()
                val eKey = crypto.encryptFernetKey(sKey, peerPubKey)
                sKey to eKey
            }
            
            val chat = Chat(
                contactId = target,
                fernetKey = sessionKey,
                status = "pending",
                displayName = packet.name ?: target,
                lastMsgTs = System.currentTimeMillis().toString()
            )
            dao.insertChat(chat)
            
            MessengerService.instance?.sendPacket(Packet(
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
                val encrypted = withContext(Dispatchers.Default) {
                    crypto.encryptFernet(content, fernetKey)
                }
                
                val msgType = if (isImage) "image" else "text"
                val maxChunkSize = 12000 // 12KB
                
                if (encrypted.length <= maxChunkSize) {
                    val success = MessengerService.instance?.sendPacket(Packet(
                        action = "route",
                        to = contactId,
                        data = buildJsonObject {
                            put("type", "msg")
                            put("msg_type", msgType)
                            put("content", encrypted)
                            put("sender_name", _userName.value)
                        }
                    )) ?: false
                    if (success) {
                        insertLocalMessage(contactId, content, msgType)
                        val preview = if (msgType == "text") content else "Sent a photo"
                        dao.updateLastMessage(contactId, preview, System.currentTimeMillis().toString(), 0)
                    }
                } else {
                    val msgId = UUID.randomUUID().toString()
                    val chunks = encrypted.chunked(maxChunkSize)
                    val total = chunks.size
                    var allSuccess = true
                    
                    for ((index, chunkContent) in chunks.withIndex()) {
                        val success = MessengerService.instance?.sendPacket(Packet(
                            action = "route",
                            to = contactId,
                            data = buildJsonObject {
                                put("type", "chunk")
                                put("msg_id", msgId)
                                put("index", index)
                                put("total", total)
                                put("msg_type", msgType)
                                put("content", chunkContent)
                                put("sender_name", _userName.value)
                            }
                        )) ?: false
                        if (!success) {
                            allSuccess = false
                            break
                        }
                    }
                    
                    if (allSuccess) {
                        insertLocalMessage(contactId, content, msgType)
                        val preview = if (msgType == "text") content else "Sent a photo"
                        dao.updateLastMessage(contactId, preview, System.currentTimeMillis().toString(), 0)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendSticker(contactId: String, stickerType: String) {
        viewModelScope.launch {
            val chat = dao.getChat(contactId) ?: return@launch
            val fernetKey = chat.fernetKey ?: return@launch

            val stickerContent = when (stickerType) {
                "time" -> {
                    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                    "TIME:${sdf.format(Date())}"
                }
                "date" -> {
                    val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
                    "DATE:${sdf.format(Date())}"
                }
                "location" -> {
                    try {
                        val locationResult = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).customAwait()
                        if (locationResult != null) {
                            val geocoder = Geocoder(getApplication(), Locale.getDefault())
                            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                suspendCancellableCoroutine { continuation ->
                                    geocoder.getFromLocation(locationResult.latitude, locationResult.longitude, 1, object : Geocoder.GeocodeListener {
                                        override fun onGeocode(addresses: MutableList<Address>) {
                                            continuation.resume(addresses)
                                        }
                                        override fun onError(errorMessage: String?) {
                                            continuation.resume(emptyList<Address>())
                                        }
                                    })
                                }
                            } else {
                                withContext(Dispatchers.IO) {
                                    @Suppress("DEPRECATION")
                                    geocoder.getFromLocation(locationResult.latitude, locationResult.longitude, 1)
                                }
                            }
                            val city = addresses?.firstOrNull()?.locality ?: "${locationResult.latitude.toString().take(5)}, ${locationResult.longitude.toString().take(5)}"
                            "LOC:$city"
                        } else "LOC:Unknown"
                    } catch (e: Exception) { "LOC:Error" }
                }
                "battery" -> {
                    val bm = getApplication<Application>().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    "BATT:$level%"
                }
                "device" -> "DEV:${Build.MODEL}"
                "greet" -> "GREET:Hello!"
                "status" -> "STAT:Secure"
                else -> return@launch
            }

            try {
                val encrypted = withContext(Dispatchers.Default) {
                    crypto.encryptFernet(stickerContent, fernetKey)
                }
                
                val success = MessengerService.instance?.sendPacket(Packet(
                    action = "route",
                    to = contactId,
                    data = buildJsonObject {
                        put("type", "sticker")
                        put("content", encrypted)
                        put("sender_name", _userName.value)
                    }
                )) ?: false

                if (success) {
                    insertLocalMessage(contactId, stickerContent, "sticker")
                    dao.updateLastMessage(contactId, "Sent a sticker", System.currentTimeMillis().toString(), 0)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private suspend fun insertLocalMessage(contactId: String, content: String, msgType: String) {
        val msg = Message(
            contactId = contactId,
            senderId = _myId.value,
            senderName = _userName.value,
            msgType = msgType,
            content = content,
            timestamp = System.currentTimeMillis().toString()
        )
        dao.insertMessage(msg)
    }

    fun addContact(targetId: String) {
        viewModelScope.launch {
            MessengerService.instance?.sendPacket(Packet(
                action = "get_pub_key",
                target = targetId.uppercase()
            ))
        }
    }

    fun getMessages(contactId: String): Flow<List<Message>> = dao.getMessages(contactId)

    private suspend fun <T> Task<T>.customAwait(): T? = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.resume(null)
            }
        }
    }
}
