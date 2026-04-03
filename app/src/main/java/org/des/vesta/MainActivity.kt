package org.des.vesta

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.des.vesta.data.Chat
import org.des.vesta.data.Message
import org.des.vesta.ui.theme.VestaTheme
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MessengerViewModel = viewModel()
            val isDarkMode by viewModel.isDarkMode.collectAsState()
            
            VestaTheme(darkTheme = isDarkMode) {
                MainScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MessengerViewModel) {
    var selectedChatId by remember { mutableStateOf<String?>(null) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    val chats by viewModel.allChats.collectAsState()
    val myId by viewModel.myId.collectAsState()
    val myName by viewModel.userName.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(if (selectedChatId == null) "vesta" else chats.find { it.contactId == selectedChatId }?.displayName ?: "Chat")
                        if (selectedChatId == null) {
                            Text("ID: $myId", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    if (selectedChatId != null) {
                        IconButton(onClick = { selectedChatId = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (selectedChatId == null) {
                        IconButton(onClick = { showAddContactDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Contact")
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (selectedChatId == null) {
                ChatList(chats) { id -> selectedChatId = id }
            } else {
                ChatView(selectedChatId!!, viewModel)
            }
        }
    }

    if (showAddContactDialog) {
        var targetId by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            title = { Text("Add Contact") },
            text = {
                TextField(
                    value = targetId,
                    onValueChange = { targetId = it.uppercase() },
                    placeholder = { Text("Enter 6-digit ID") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (targetId.length == 6) {
                        viewModel.addContact(targetId)
                        showAddContactDialog = false
                    }
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddContactDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showSettingsDialog) {
        var tempName by remember { mutableStateOf(myName) }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("Your Name") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dark Mode")
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = { viewModel.toggleTheme(it) }
                        )
                    }
                    Text("Your ID: $myId", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateProfile(tempName)
                    showSettingsDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
fun ChatList(chats: List<Chat>, onChatClick: (String) -> Unit) {
    if (chats.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No chats yet. Add someone by ID!", color = Color.Gray)
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(chats) { chat ->
            ListItem(
                headlineContent = { Text(chat.displayName ?: chat.contactId, fontWeight = FontWeight.Bold) },
                supportingContent = { 
                    Text(
                        if (chat.status == "active") "Secure Tunnel" else "Handshake pending...",
                        color = if (chat.status == "active") MaterialTheme.colorScheme.primary else Color.Gray
                    ) 
                },
                leadingContent = {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, contentDescription = null)
                        }
                    }
                },
                modifier = Modifier.clickable { onChatClick(chat.contactId) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun ChatView(chatId: String, viewModel: MessengerViewModel) {
    val messages by viewModel.getMessages(chatId).collectAsState(initial = emptyList())
    var messageText by remember { mutableStateOf("") }
    val myId by viewModel.myId.collectAsState()
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val b64 = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
            viewModel.sendMessage(chatId, b64, isImage = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            StarryBackground()
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                reverseLayout = false
            ) {
                items(messages) { msg ->
                    MessageBubble(msg, msg.senderId == myId)
                }
            }
        }
        
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Icon(Icons.Default.Add, contentDescription = "Attach")
                }
                TextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Write a message...") },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                Spacer(Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            viewModel.sendMessage(chatId, messageText)
                            messageText = ""
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun StarryBackground() {
    val starColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val planetColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        val random = Random(42)
        repeat(100) {
            drawCircle(
                color = starColor,
                radius = 1.dp.toPx(),
                center = Offset(
                    x = random.nextFloat() * size.width,
                    y = random.nextFloat() * size.height
                )
            )
        }
        
        drawCircle(
            color = planetColor,
            radius = size.width,
            center = Offset(size.width / 2, size.height + size.width * 0.4f)
        )
    }
}

@Composable
fun MessageBubble(msg: Message, isMe: Boolean) {
    val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
    val color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isMe) 
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp) else 
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(color)
                .padding(12.dp)
        ) {
            if (!isMe) {
                Text(msg.senderName, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
            }
            
            if (msg.msgType == "image") {
                val imageBytes = Base64.decode(msg.content, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                }
            } else {
                Text(text = msg.content, color = textColor, fontSize = 16.sp)
            }

            val time = try {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                sdf.format(Date(msg.timestamp.toLong()))
            } catch (e: Exception) { "" }
            
            Text(
                text = time, 
                color = textColor.copy(alpha = 0.6f),
                fontSize = 10.sp, 
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}
