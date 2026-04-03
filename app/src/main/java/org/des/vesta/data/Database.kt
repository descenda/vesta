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
    val lastMsgTs: String
)

@Entity(tableName = "messages")
@Serializable
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val contactId: String,
    val senderId: String,
    val senderName: String,
    val msgType: String, // "text", "image", "voice"
    val content: String, // Base64 for media or encrypted text
    val timestamp: String
)

@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey val key: String,
    val value: String
)

@Dao
interface MessengerDao {
    @Query("SELECT * FROM chats ORDER BY lastMsgTs DESC")
    fun getAllChats(): Flow<List<Chat>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: Chat)

    @Query("SELECT * FROM chats WHERE contactId = :contactId")
    suspend fun getChat(contactId: String): Chat?

    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY id ASC")
    fun getMessages(contactId: String): Flow<List<Message>>

    @Insert
    suspend fun insertMessage(message: Message)

    @Query("DELETE FROM messages WHERE contactId = :contactId")
    suspend fun deleteMessagesForChat(contactId: String)

    @Query("DELETE FROM chats WHERE contactId = :contactId")
    suspend fun deleteChat(contactId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSetting(setting: Setting)

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun getSetting(key: String): String?
}

@Database(entities = [Chat::class, Message::class, Setting::class], version = 1, exportSchema = false)
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
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
