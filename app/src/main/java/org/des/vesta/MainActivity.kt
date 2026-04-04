package org.des.vesta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.des.vesta.data.Chat
import org.des.vesta.data.Message
import org.des.vesta.ui.theme.VestaTheme
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.math.*
import kotlin.random.Random

private val EntranceSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
private val BouncySpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

val NineSidedShape = GenericShape { size, _ ->
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val radius = size.width.coerceAtMost(size.height) / 2f
    val sides = 9
    val amplitude = radius * 0.05f 
    val steps = 72 
    for (i in 0 until steps) {
        val angle = 2.0 * PI * i / steps
        val r = radius - amplitude + amplitude * cos(sides * angle).toFloat()
        val x = centerX + (r * cos(angle)).toFloat()
        val y = centerY + (r * sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

class MainActivity : ComponentActivity() {
    private var initialChatId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        startMessengerService()
        
        setContent {
            val viewModel: MessengerViewModel = viewModel()
            val isDarkMode by viewModel.isDarkMode.collectAsState()
            
            VestaTheme(darkTheme = isDarkMode) {
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (!isGranted) Toast.makeText(context, "Notifications required for sync", Toast.LENGTH_LONG).show()
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                MainScreen(viewModel, initialChatId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        initialChatId = intent?.getStringExtra("OPEN_CHAT_ID")
    }

    private fun startMessengerService() {
        val intent = Intent(this, MessengerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    override fun onResume() { super.onResume(); MessengerService.isAppVisible = true }
    override fun onPause() { super.onPause(); MessengerService.isAppVisible = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MessengerViewModel, incomingChatId: String? = null) {
    var selectedChatId by remember { mutableStateOf<String?>(incomingChatId) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    val chats by viewModel.allChats.collectAsState()
    val myId by viewModel.myId.collectAsState()
    val myName by viewModel.userName.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val showLinkWarning by viewModel.showLinkWarning.collectAsState()

    LaunchedEffect(incomingChatId) { 
        if (incomingChatId != null) {
            selectedChatId = incomingChatId
            viewModel.setCurrentChat(incomingChatId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(if (selectedChatId == null) "vesta" else chats.find { it.contactId == selectedChatId }?.displayName ?: "Chat")
                        if (selectedChatId == null) Text("ID: $myId", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    if (selectedChatId != null) {
                        IconButton(onClick = { 
                            selectedChatId = null 
                            viewModel.setCurrentChat(null)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (selectedChatId == null) {
                        IconButton(onClick = { showAddContactDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add") }
                        IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            AnimatedContent(
                targetState = selectedChatId,
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                    } using SizeTransform(clip = false)
                },
                label = ""
            ) { targetChatId ->
                if (targetChatId == null) ChatList(chats, viewModel) { id -> 
                    selectedChatId = id
                    viewModel.setCurrentChat(id)
                }
                else ChatView(targetChatId, viewModel)
            }
        }
    }

    if (showAddContactDialog) {
        var targetId by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            title = { Text("Add Contact") },
            text = {
                TextField(value = targetId, onValueChange = { targetId = it.uppercase() }, placeholder = { Text("6-digit ID") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = { if (targetId.length == 6) { viewModel.addContact(targetId); showAddContactDialog = false } }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddContactDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSettingsDialog) {
        var tempName by remember { mutableStateOf(myName) }
        val context = LocalContext.current
        
        val pfpLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                val original = BitmapFactory.decodeStream(context.contentResolver.openInputStream(it))
                val scale = min(1f, 300f / max(original.width, original.height))
                val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true) else original
                val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                viewModel.updateAvatar(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
        }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(100.dp).clickable { pfpLauncher.launch("image/*") }) {
                        IdenticonAvatar(myId, myName, size = 100.dp)
                        Box(modifier = Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp)) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    TextField(value = tempName, onValueChange = { tempName = it }, label = { Text("Your Name") }, singleLine = true)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Dark Mode"); Switch(checked = isDarkMode, onCheckedChange = { viewModel.toggleTheme(it) })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Link Warning"); Switch(checked = showLinkWarning, onCheckedChange = { viewModel.toggleLinkWarning(it) })
                    }
                }
            },
            confirmButton = { Button(onClick = { viewModel.updateProfile(tempName); showSettingsDialog = false }) { Text("Save") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatList(chats: List<Chat>, viewModel: MessengerViewModel, onChatClick: (String) -> Unit) {
    var chatToManage by remember { mutableStateOf<Chat?>(null) }
    val view = LocalView.current

    if (chats.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ChatBubbleOutline, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Text("No chats yet.", color = Color.Gray)
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(chats, key = { it.contactId }) { chat ->
            Box(modifier = Modifier.animateItem()) {
                ListItem(
                    headlineContent = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(chat.displayName ?: chat.contactId, fontWeight = FontWeight.Bold)
                            if (chat.isPinned) {
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.PushPin, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    supportingContent = { 
                        Text(
                            chat.lastMessage ?: if (chat.status == "active") "Secure Tunnel" else "Pending...",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (chat.unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        ) 
                    },
                    leadingContent = { 
                        IdenticonAvatar(chat.contactId, chat.displayName, avatarBase64 = chat.avatar)
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatTimeShort(chat.lastMsgTs.toLongOrNull() ?: 0L), style = MaterialTheme.typography.labelSmall)
                            if (chat.unreadCount > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.primary) { 
                                    Text(chat.unreadCount.toString(), color = MaterialTheme.colorScheme.onPrimary) 
                                }
                            }
                        }
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = { onChatClick(chat.contactId) },
                        onLongClick = { 
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            chatToManage = chat 
                        }
                    )
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    if (chatToManage != null) {
        AlertDialog(
            onDismissRequest = { chatToManage = null },
            title = { Text(chatToManage!!.displayName ?: chatToManage!!.contactId) },
            text = { Text("Chat Options") },
            confirmButton = {
                Column {
                    TextButton(onClick = { 
                        viewModel.setChatPinned(chatToManage!!.contactId, !chatToManage!!.isPinned)
                        chatToManage = null
                    }) { Text(if (chatToManage!!.isPinned) "Unpin" else "Pin") }
                    
                    TextButton(onClick = { 
                        viewModel.deleteChat(chatToManage!!.contactId)
                        chatToManage = null
                    }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) { Text("Delete Chat") }
                }
            }
        )
    }
}

@Composable
fun IdenticonAvatar(id: String, name: String?, size: androidx.compose.ui.unit.Dp = 48.dp, avatarBase64: String? = null) {
    val bitmap = remember(avatarBase64) {
        if (avatarBase64 != null) {
            try {
                val bytes = Base64.decode(avatarBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) { null }
        } else null
    }

    Surface(modifier = Modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop)
        } else {
            val color = remember(id) {
                val hash = id.hashCode()
                Color(
                    red = (hash and 0xFF0000 shr 16) / 255f * 0.4f + 0.3f,
                    green = (hash and 0x00FF00 shr 8) / 255f * 0.4f + 0.3f,
                    blue = (hash and 0x0000FF) / 255f * 0.4f + 0.3f,
                    alpha = 1f
                )
            }
            Box(modifier = Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
                Text(
                    text = (name ?: id).take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onSurface, // Fallback to onSurface color as requested
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = (size.value * 0.4f).sp)
                )
            }
        }
    }
}

@Composable
fun ChatView(chatId: String, viewModel: MessengerViewModel) {
    val messages by viewModel.getMessages(chatId).collectAsState(initial = emptyList())
    val chat by remember(chatId) { derivedStateOf { viewModel.allChats.value.find { it.contactId == chatId } } }
    
    var messageText by remember { mutableStateOf(chat?.draft ?: "") }
    val myId by viewModel.myId.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val listState = rememberLazyListState()
    var showStickers by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val original = BitmapFactory.decodeStream(context.contentResolver.openInputStream(it))
            val scale = min(1f, 480f / max(original.width, original.height))
            val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true) else original
            val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 30, out)
            viewModel.sendMessage(chatId, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), isImage = true)
        }
    }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        if (p[Manifest.permission.ACCESS_FINE_LOCATION] == true) viewModel.sendSticker(chatId, "location")
    }

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = isSearching) {
            TextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search messages...") }, trailingIcon = { IconButton(onClick = { isSearching = false; searchQuery = "" }) { Icon(Icons.Default.Close, null) } }, colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        }

        Box(modifier = Modifier.weight(1f)) {
            StarryBackground()
            val displayMessages = if (searchQuery.isBlank()) messages else messages.filter { it.content.contains(searchQuery, ignoreCase = true) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(displayMessages, key = { it.id }) { msg -> MessageBubble(msg, msg.senderId == myId, viewModel) }
            }
            if (!isSearching) {
                IconButton(onClick = { isSearching = true }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Icon(Icons.Default.Search, null, tint = Color.White.copy(0.3f)) }
            }
        }
        
        AnimatedVisibility(visible = showStickers, enter = expandVertically(), exit = shrinkVertically()) {
            Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                LazyRow(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { StickerPickerItem(Icons.Default.Schedule, "Time", MaterialTheme.colorScheme.primaryContainer) { viewModel.sendSticker(chatId, "time"); showStickers = false } }
                    item { StickerPickerItem(Icons.Default.CalendarToday, "Date", MaterialTheme.colorScheme.secondaryContainer) { viewModel.sendSticker(chatId, "date"); showStickers = false } }
                    item { StickerPickerItem(Icons.Default.LocationOn, "Location", MaterialTheme.colorScheme.tertiaryContainer) { locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); showStickers = false } }
                    item { StickerPickerItem(Icons.Default.BatteryChargingFull, "Battery", MaterialTheme.colorScheme.primaryContainer) { viewModel.sendSticker(chatId, "battery"); showStickers = false } }
                    item { StickerPickerItem(Icons.Default.Smartphone, "Device", MaterialTheme.colorScheme.secondaryContainer) { viewModel.sendSticker(chatId, "device"); showStickers = false } }
                    item { StickerPickerItem(Icons.Default.WavingHand, "Hello", MaterialTheme.colorScheme.tertiaryContainer) { viewModel.sendSticker(chatId, "greet"); showStickers = false } }
                    item { StickerPickerItem(Icons.Default.VerifiedUser, "Status", MaterialTheme.colorScheme.primaryContainer) { viewModel.sendSticker(chatId, "status"); showStickers = false } }
                }
            }
        }

        Surface(tonalElevation = 2.dp) {
            Row(modifier = Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showStickers = !showStickers }) { Icon(if (showStickers) Icons.Default.Close else Icons.Default.StickyNote2, null) }
                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) { Icon(Icons.Default.Image, null) }
                IconButton(onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); Toast.makeText(context, "Voice recording...", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Mic, null) }
                TextField(value = messageText, onValueChange = { messageText = it; viewModel.updateDraft(chatId, it) }, modifier = Modifier.weight(1f), placeholder = { Text("Message") }, shape = RoundedCornerShape(24.dp), colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                Spacer(Modifier.width(8.dp))
                val interact = remember { MutableInteractionSource() }; val pressed by interact.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.85f else 1f, BouncySpring, label = "")
                FloatingActionButton(onClick = { if (messageText.isNotBlank()) { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.sendMessage(chatId, messageText); messageText = ""; viewModel.updateDraft(chatId, "") } }, modifier = Modifier.size(52.dp).graphicsLayer { scaleX = scale; scaleY = scale }, shape = NineSidedShape, containerColor = MaterialTheme.colorScheme.primaryContainer, interactionSource = interact) { Icon(Icons.AutoMirrored.Filled.Send, null) }
            }
        }
    }
}

@Composable
fun StickerPickerItem(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Surface(modifier = Modifier.size(80.dp).clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null); Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun StarryBackground() {
    val sColor = MaterialTheme.colorScheme.onSurface.copy(0.1f); val pColor = MaterialTheme.colorScheme.primary.copy(0.05f)
    Spacer(modifier = Modifier.fillMaxSize().drawWithCache { onDrawBehind { val r = Random(42); repeat(100) { drawCircle(sColor, 1.dp.toPx(), Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) }; drawCircle(pColor, size.width, Offset(size.width / 2, size.height + size.width * 0.4f)) } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(msg: Message, isMe: Boolean, viewModel: MessengerViewModel) {
    val color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val clipboard = LocalClipboardManager.current; val context = LocalContext.current; val uri = LocalUriHandler.current
    val showWarn by viewModel.showLinkWarning.collectAsState(); var pendingUrl by remember { mutableStateOf<String?>(null) }
    val animScale = remember { Animatable(0.7f) }; LaunchedEffect(Unit) { animScale.animateTo(1f, EntranceSpring) }

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).graphicsLayer { scaleX = animScale.value; scaleY = animScale.value; alpha = (animScale.value - 0.7f) / 0.3f; transformOrigin = TransformOrigin(if (isMe) 1f else 0f, 1f) }, contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(modifier = Modifier.widthIn(max = 300.dp).clip(if (msg.msgType == "sticker") RoundedCornerShape(0) else RoundedCornerShape(20.dp)).background(if (msg.msgType == "sticker") Color.Transparent else color).combinedClickable(onClick = {}, onLongClick = { if (msg.msgType == "text") { clipboard.setText(AnnotatedString(msg.content)); Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show() } }).padding(if (msg.msgType == "sticker") 0.dp else 12.dp)) {
            if (!isMe && msg.msgType != "sticker") Text(msg.senderName, style = MaterialTheme.typography.labelSmall, color = textColor.copy(0.7f))
            when (msg.msgType) {
                "image" -> {
                    val bmp = remember(msg.content) { try { val b = Base64.decode(msg.content, Base64.DEFAULT); BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } catch (e: Exception) { null } }
                    if (bmp != null) Image(bmp, null, modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth)
                }
                "sticker" -> StickerView(msg.content)
                else -> {
                    val text = rememberLinkifiedText(msg.content, isMe)
                    ClickableText(text, style = MaterialTheme.typography.bodyLarge.copy(color = textColor), onClick = { offset -> text.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { if (showWarn) pendingUrl = it.item else uri.openUri(it.item) } })
                }
            }
            if (msg.msgType != "sticker") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                    Text(text = formatTime(msg.timestamp.toLong()), color = textColor.copy(0.6f), fontSize = 10.sp)
                    if (isMe) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Done, null, modifier = Modifier.size(12.dp), tint = textColor.copy(0.6f))
                    }
                }
            }
        }
    }
    if (pendingUrl != null) AlertDialog(onDismissRequest = { pendingUrl = null }, title = { Text("Link") }, text = { Text(pendingUrl!!) }, confirmButton = { Button(onClick = { uri.openUri(pendingUrl!!); pendingUrl = null }) { Text("Open") } }, dismissButton = { TextButton(onClick = { pendingUrl = null }) { Text("Cancel") } })
}

fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat(if (System.currentTimeMillis() - timestamp < 86400000) "HH:mm" else "MMM dd, HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

fun formatTimeShort(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = SimpleDateFormat(if (System.currentTimeMillis() - timestamp < 86400000) "HH:mm" else "MMM dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
fun StickerView(content: String) {
    val type = content.takeWhile { it != ':' }; val value = content.dropWhile { it != ':' }.drop(1)
    val color = when (type) {
        "TIME", "BATT", "STAT" -> MaterialTheme.colorScheme.primaryContainer
        "DATE", "DEV" -> MaterialTheme.colorScheme.secondaryContainer
        "LOC", "GREET" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val icon = when (type) {
        "TIME" -> Icons.Default.Schedule
        "DATE" -> Icons.Default.CalendarToday
        "LOC" -> Icons.Default.LocationOn
        "BATT" -> Icons.Default.BatteryChargingFull
        "DEV" -> Icons.Default.Smartphone
        "GREET" -> Icons.Default.WavingHand
        "STAT" -> Icons.Default.VerifiedUser
        else -> Icons.Default.StickyNote2
    }
    Surface(color = color, shape = RoundedCornerShape(100), tonalElevation = 4.dp, modifier = Modifier.padding(4.dp).wrapContentWidth()) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = contentColorFor(color), modifier = Modifier.size(28.dp))
            AutoScalingText(text = value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = contentColorFor(color))
        }
    }
}

@Composable
fun AutoScalingText(text: String, style: TextStyle, color: Color) {
    var multiplier by remember { mutableStateOf(1f) }
    Text(text = text, style = style.copy(fontSize = style.fontSize * multiplier), color = color, maxLines = 1, softWrap = false, onTextLayout = { result -> if (result.hasVisualOverflow && multiplier > 0.5f) { multiplier *= 0.9f } }, textAlign = TextAlign.Center)
}

@Composable
fun rememberLinkifiedText(text: String, isMe: Boolean): AnnotatedString {
    val color = if (isMe) Color.White else MaterialTheme.colorScheme.primary
    return remember(text, color) { buildAnnotatedString {
        val p = Pattern.compile("(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)(([\\w\\-]+\\.){1,224}[a-z]{2,10}(:[0-9]{1,5})?)(\\/[^\\s]*)?", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(text); var last = 0
        while (m.find()) {
            val start = m.start(1); val end = m.end()
            append(text.substring(last, start))
            val url = text.substring(start, end); val full = if (url.startsWith("www")) "http://$url" else url
            pushStringAnnotation("URL", full)
            withStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) { append(url) }
            pop(); last = end
        }
        append(text.substring(last))
    } }
}
