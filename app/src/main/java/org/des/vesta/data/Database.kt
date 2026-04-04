package org.des.vesta.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Entity(tableName = "chats")
@Serializable
data class Chat(
    @PrimaryKey val contactId: String,
    val fernetKey: String?,
    val status: String,
    val type: String = "private", // "private" or "group"
    val displayName: String?,
    val avatar: String? = null,
    val bio: String? = null,
    val lastMsgTs: String,
    val lastMessage: String? = null,
    val unreadCount: Int = 0,
    val draft: String? = null,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val isBlocked: Boolean = false
)

@Entity(tableName = "messages")
@Serializable
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val contactId: String,
    val senderId: String,
    val senderName: String,
    val msgType: String, // "text", "image", "voice", "sticker"
    val content: String, // Base64 for media or encrypted text
    val timestamp: String,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val replyToId: Int? = null,
    val metadata: String? = null // JSON for extra info (e.g., image size, voice duration)
)

@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey val key: String,
    val value: String
)

@Dao
interface MessengerDao {
    @Query("SELECT * FROM chats WHERE isArchived = 0 AND isBlocked = 0 ORDER BY isPinned DESC, lastMsgTs DESC")
    fun getAllChats(): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE isArchived = 1 ORDER BY lastMsgTs DESC")
    fun getArchivedChats(): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE isBlocked = 1")
    fun getBlockedChats(): Flow<List<Chat>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: Chat)

    @Query("SELECT * FROM chats WHERE contactId = :contactId")
    suspend fun getChat(contactId: String): Chat?

    @Query("UPDATE chats SET lastMsgTs = :ts, lastMessage = :text, unreadCount = unreadCount + :unreadInc WHERE contactId = :contactId")
    suspend fun updateLastMessage(contactId: String, text: String, ts: String, unreadInc: Int)

    @Query("UPDATE chats SET unreadCount = 0 WHERE contactId = :contactId")
    suspend fun clearUnreadCount(contactId: String)

    @Query("UPDATE chats SET draft = :draft WHERE contactId = :contactId")
    suspend fun updateDraft(contactId: String, draft: String?)

    @Query("UPDATE chats SET isPinned = :pinned WHERE contactId = :contactId")
    suspend fun setChatPinned(contactId: String, pinned: Boolean)

    @Query("UPDATE chats SET isArchived = :archived WHERE contactId = :contactId")
    suspend fun setChatArchived(contactId: String, archived: Boolean)

    @Query("UPDATE chats SET isBlocked = :blocked WHERE contactId = :contactId")
    suspend fun setChatBlocked(contactId: String, blocked: Boolean)

    @Query("SELECT SUM(unreadCount) FROM chats")
    fun getTotalUnreadCount(): Flow<Int?>

    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY id ASC")
    fun getMessages(contactId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE isStarred = 1 AND contactId = :contactId")
    fun getStarredMessages(contactId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' AND contactId = :contactId")
    suspend fun searchMessages(contactId: String, query: String): List<Message>

    @Insert
    suspend fun insertMessage(message: Message)

    @Query("UPDATE messages SET isRead = 1 WHERE contactId = :contactId AND isRead = 0")
    suspend fun markChatAsRead(contactId: String)

    @Query("UPDATE messages SET isStarred = :starred WHERE id = :msgId")
    suspend fun setMessageStarred(msgId: Int, starred: Boolean)

    @Query("DELETE FROM messages WHERE id = :msgId")
    suspend fun deleteMessage(msgId: Int)

    @Query("DELETE FROM messages WHERE contactId = :contactId")
    suspend fun deleteMessagesForChat(contactId: String)

    @Query("DELETE FROM chats WHERE contactId = :contactId")
    suspend fun deleteChat(contactId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSetting(setting: Setting)

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun getSetting(key: String): String?
}

@Database(entities = [Chat::class, Message::class, Setting::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messengerDao(): MessengerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "secure_messenger.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
