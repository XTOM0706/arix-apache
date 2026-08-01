 package com.arix.app
 
 import android.Manifest
 import android.content.pm.PackageManager
 import android.media.AudioFormat
 import android.media.AudioRecord
 import android.media.MediaRecorder
 import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
 import androidx.activity.ComponentActivity
 import androidx.activity.compose.setContent
 import androidx.activity.compose.rememberLauncherForActivityResult
 import androidx.activity.result.contract.ActivityResultContracts
 import androidx.compose.animation.core.animateFloatAsState
 import androidx.compose.foundation.ExperimentalFoundationApi
 import androidx.compose.foundation.background
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.combinedClickable
 import androidx.compose.foundation.horizontalScroll
 import androidx.compose.foundation.layout.Arrangement
 import androidx.compose.foundation.layout.Box
 import androidx.compose.foundation.layout.Column
 import androidx.compose.foundation.layout.Row
 import androidx.compose.foundation.layout.Spacer
 import androidx.compose.foundation.layout.fillMaxSize
 import androidx.compose.foundation.layout.fillMaxWidth
 import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
 import androidx.compose.foundation.layout.padding
 import androidx.compose.foundation.layout.size
 import androidx.compose.foundation.layout.width
 import androidx.compose.foundation.layout.widthIn
 import androidx.compose.foundation.lazy.LazyColumn
 import androidx.compose.foundation.lazy.items
 import androidx.compose.foundation.lazy.rememberLazyListState
 import androidx.compose.foundation.rememberScrollState
 import androidx.compose.foundation.shape.CircleShape
 import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.foundation.text.KeyboardOptions
 import androidx.compose.foundation.verticalScroll
 import androidx.compose.material.icons.Icons
 import androidx.compose.material.icons.automirrored.outlined.ArrowBack
 import androidx.compose.material.icons.automirrored.outlined.Chat
 import androidx.compose.material.icons.outlined.Add
 import androidx.compose.material.icons.outlined.History
 import androidx.compose.material.icons.outlined.Menu
 import androidx.compose.material.icons.outlined.Mic
 import androidx.compose.material.icons.outlined.Settings
 import androidx.compose.material3.Button
 import androidx.compose.material3.ButtonDefaults
 import androidx.compose.material3.Card
 import androidx.compose.material3.CardDefaults
 import androidx.compose.material3.DrawerValue
 import androidx.compose.material3.DropdownMenu
 import androidx.compose.material3.DropdownMenuItem
 import androidx.compose.material3.ExperimentalMaterial3Api
 import androidx.compose.material3.HorizontalDivider
 import androidx.compose.material3.Icon
 import androidx.compose.material3.IconButton
 import androidx.compose.material3.LinearProgressIndicator
 import androidx.compose.material3.MaterialTheme
 import androidx.compose.material3.ModalDrawerSheet
 import androidx.compose.material3.ModalNavigationDrawer
 import androidx.compose.material3.NavigationDrawerItem
 import androidx.compose.material3.NavigationDrawerItemDefaults
 import androidx.compose.material3.OutlinedTextField
 import androidx.compose.material3.OutlinedTextFieldDefaults
 import androidx.compose.material3.Scaffold
 import androidx.compose.material3.Switch
 import androidx.compose.material3.SwitchDefaults
 import androidx.compose.material3.Text
 import androidx.compose.material3.TextButton
 import androidx.compose.material3.TopAppBar
 import androidx.compose.material3.TopAppBarDefaults
 import androidx.compose.material3.rememberDrawerState
 import androidx.compose.runtime.Composable
 import androidx.compose.runtime.DisposableEffect
 import androidx.compose.runtime.Immutable
 import androidx.compose.runtime.LaunchedEffect
 import androidx.compose.runtime.collectAsState
 import androidx.compose.runtime.derivedStateOf
 import androidx.compose.runtime.getValue
 import androidx.compose.runtime.key
 import androidx.compose.runtime.mutableLongStateOf
 import androidx.compose.runtime.mutableStateListOf
 import androidx.compose.runtime.mutableStateOf
 import androidx.compose.runtime.remember
 import androidx.compose.runtime.rememberCoroutineScope
 import androidx.compose.runtime.setValue
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.draw.alpha
 import androidx.compose.ui.draw.clip
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.platform.LocalClipboardManager
 import androidx.compose.ui.platform.LocalContext
 import androidx.compose.ui.text.font.FontFamily
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.text.input.PasswordVisualTransformation
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
 import androidx.core.content.ContextCompat
 import com.arix.cloudapi.CloudApiClient
 import com.arix.cloudapi.CloudApiConfig
 import com.arix.cloudapi.WhisperClient
 import com.arix.cloudapi.model.ChatMessage
 import android.content.Intent
 import androidx.compose.material.icons.outlined.Warning
 import com.arix.tool.PackageManager as XtomPackageManager
import com.arix.tool.PackageDef
 import com.arix.tool.OperitCompat
 import com.arix.tool.CloudMarketplace
 import com.arix.tool.ImportExport
 import com.arix.tool.PluginCreatorTool
import com.arix.tool.TtsTool
import com.arix.tool.ShellTool
 import java.io.File
 import com.arix.stt.LanguageModel
 import com.arix.stt.SttEngine
 import com.arix.stt.SttModelManager
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.delay
 import kotlinx.coroutines.isActive
 import kotlinx.coroutines.launch
 import kotlinx.coroutines.withContext
 import org.json.JSONObject
 import java.text.SimpleDateFormat
 import java.util.Date
 import java.util.Locale

 
 // ============================================================
 // Models
 // ============================================================
 
 sealed class LogEntry {
     data class Info(val text: String) : LogEntry()
     data class Request(val text: String) : LogEntry()
     data class Chunk(val text: String) : LogEntry()
     data class Complete(val text: String) : LogEntry()
     data class Error(val text: String) : LogEntry()
 }
 
 // 进程内单调递增，给每个气泡一个稳定唯一 id（供 LazyColumn key 用，避免用 index 导致
 // 中间插删时状态错位/展开态串位）。copy() 默认保留原 id，回填 usage/耗时不会改 id。
 private val chatBubbleIdSeq = java.util.concurrent.atomic.AtomicLong(0L)

 @Immutable
 data class ChatBubble(
     val role: String,
     val text: String,
     val reasoning: String? = null,
     val usage: CloudApiClient.Usage? = null,
     val tokensPerSec: Double? = null,
     val elapsedMs: Long? = null,
     val toolCalls: List<ChatMessage.ToolCallMsg>? = null,  // role=="assistant" 的工具调用
     val toolCallId: String? = null,                        // role=="tool" 的结果回指
     val attachments: List<String>? = null,                 // 用户消息附带的附件 URI（供缩略图展示）
     val model: String? = null,                             // 产出这条消息的模型（按当时模型算花费）
     val id: Long = chatBubbleIdSeq.incrementAndGet()
 )
