package org.des.vesta

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.des.vesta.data.AppDatabase
import org.des.vesta.data.Message
import org.des.vesta.network.MessengerClient
import org.des.vesta.network.Packet
import kotlinx.serialization.json.*
import org.des.vesta.crypto.CryptoEngine

class MessengerService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var client: MessengerClient
    private lateinit var db: AppDatabase
    private var crypto = CryptoEngine()
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val NOTIF_CHANNEL_ID = "messenger_service"
    private val MSG_CHANNEL_ID = "new_messages"

    // Persistent registration info to re-apply on reconnect
    private var regId: String? = null
    private var regName: String? = null
    private var regPubKey: String? = null
    private var regAvatar: String? = null

    companion object {
        private val _incomingPackets = MutableSharedFlow<Packet>()
        val incomingPackets = _incomingPackets.asSharedFlow()
        
        var isAppVisible = false
        var instance: MessengerService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.getDatabase(this)
        client = MessengerClient("herringlike-unneedy-benson.ngrok-free.dev")
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vesta:NetworkLock")
        wakeLock?.acquire(10 * 60 * 1000L) // 10 min auto-release safety

        createNotificationChannels()
        startForeground(1, createForegroundNotification())
        
        client.connect()
        
        serviceScope.launch {
            // Re-register every time the socket connects
            client.onConnected.collect {
                val id = regId
                if (id != null) {
                    client.sendPacket(Packet(
                        action = "register",
                        id = id,
                        name = regName,
                        avatar = regAvatar,
                        pub_key = regPubKey
                    ))
                }
            }
        }

        serviceScope.launch {
            client.incomingPackets.collect { packet ->
                if (packet.action == "incoming") {
                    handleIncomingPacketInBackground(packet)
                }
                _incomingPackets.emit(packet)
            }
        }
    }

    private suspend fun handleIncomingPacketInBackground(packet: Packet) {
        val from = packet.from ?: return
        val data = packet.data ?: return
        val type = data["type"]?.jsonPrimitive?.content ?: return
        
        if (type == "msg" || type == "sticker") {
            val chat = db.messengerDao().getChat(from) ?: return
            val encrypted = data["content"]?.jsonPrimitive?.content ?: return
            val key = chat.fernetKey ?: return
            
            val decrypted = try { crypto.decryptFernet(encrypted, key) } catch (e: Exception) { null }
            val senderName = data["sender_name"]?.jsonPrimitive?.content ?: chat.displayName ?: from
            
            if (!isAppVisible) {
                val preview = when {
                    type == "sticker" -> "Sent a sticker"
                    data["msg_type"]?.jsonPrimitive?.content == "image" -> "Sent a photo"
                    else -> decrypted ?: "New message"
                }
                showNewMessageNotification(from, senderName, preview)
            }
        }
    }

    private fun showNewMessageNotification(contactId: String, sender: String, text: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_CHAT_ID", contactId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, contactId.hashCode(), intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MSG_CHANNEL_ID)
            .setContentTitle(sender)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(contactId.hashCode(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Vesta Service", NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null) }
            
            val msgChannel = NotificationChannel(
                MSG_CHANNEL_ID, "New Messages", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(msgChannel)
        }
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Vesta is active")
            .setContentText("Listening for incoming secure messages...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    suspend fun sendPacket(packet: Packet) = client.sendPacket(packet)

    fun register(id: String, name: String, pubKey: String, avatar: String?) {
        regId = id
        regName = name
        regPubKey = pubKey
        regAvatar = avatar
        serviceScope.launch {
            val privPem = db.messengerDao().getSetting("private_key_pem")
            if (privPem != null) crypto = CryptoEngine(privPem, pubKey)
            client.sendPacket(Packet(
                action = "register",
                id = id,
                name = name,
                avatar = avatar,
                pub_key = pubKey
            ))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        client.close()
        super.onDestroy()
    }
}
