package app.ghostcast

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HomecastColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF05285E),
    primaryContainer = Color(0xFF1B3A6E),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondaryContainer = Color(0xFF232B3A),
    onSecondaryContainer = Color(0xFFD9E2F5),
    errorContainer = Color(0xFF4A1F24),
    onErrorContainer = Color(0xFFFFD9DC),
    background = Color(0xFF0A0C10),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF14181F),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1C222C),
    onSurfaceVariant = Color(0xFF98A2B3),
    outline = Color(0xFF2E3646)
)

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        readShared(intent)
        setContent { MaterialTheme(colorScheme = HomecastColors) { HomecastScreen(incoming) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShared(intent)
    }

    /** A link shared from the YouTube app (or any app) lands here and casts straight away. */
    private fun readShared(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { incoming.value = it }
        }
    }
}

private fun formatDuration(s: Long): String =
    if (s < 0) "LIVE" else if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)

/** Opens the test stream in VLC; falls back to the system app chooser if VLC isn't installed. */
private fun openInVlc(ctx: Context, url: String) {
    val base = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(url), "video/*")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(Intent(base).setPackage("org.videolan.vlc"))
    } catch (_: ActivityNotFoundException) {
        runCatching {
            ctx.startActivity(Intent.createChooser(base, "Open test stream").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun HomecastScreen(incoming: MutableState<String?>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf(prefs.receiver) }
    var receiverIp by remember { mutableStateOf(prefs.receiverIp) }
    var found by remember { mutableStateOf<List<Renderer>>(emptyList()) }
    var prefer1080 by remember { mutableStateOf(prefs.prefer1080) }
    var remuxDirect by remember { mutableStateOf(prefs.remuxDirect) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<VideoHit>>(emptyList()) }
    var searchingVideos by remember { mutableStateOf(false) }
    val status by CastState.status.collectAsState()
    val casting by CastState.casting.collectAsState()
    val testUrl by CastState.testUrl.collectAsState()
    val isLink = link.contains("http")
    val busy = searching || searchingVideos

    suspend fun search(): List<Renderer> {
        searching = true
        CastState.status.value = "Searching for receivers..."
        val d = runCatching { UpnpCaster.discover(ctx, prefs.receiverIp) }.getOrNull()
        searching = false
        found = d?.renderers ?: emptyList()
        if (d == null) {
            CastState.status.value = "Receiver search failed. Is Wi-Fi on?"
        } else if (d.renderers.isEmpty()) {
            CastState.status.value = "No receiver found (${d.responses} network replies" +
                (if (d.seen.isNotEmpty()) "; not video players: ${d.seen.joinToString()}" else "") +
                "). Same Wi-Fi? Try entering the receiver IP, or test with VLC below."
        } else {
            CastState.status.value = "Found ${d.renderers.size} receiver(s)"
        }
        return found
    }

    suspend fun searchVideos(q: String) {
        searchingVideos = true
        CastState.status.value = "Searching YouTube..."
        runCatching { withContext(Dispatchers.IO) { YtSearch.search(q) } }
            .onSuccess {
                results = it
                CastState.status.value = if (it.isEmpty()) "No videos found" else "Tap a video to cast it"
            }
            .onFailure { CastState.status.value = "Search failed: ${it.message}" }
        searchingVideos = false
    }

    /** The saved receiver, or discover one. Null (status already set) if none can be chosen yet. */
    suspend fun pickReceiver(): Renderer? {
        var r = receiver
        if (r == null) {
            val list = search()
            when {
                list.isEmpty() -> return null
                list.size == 1 -> { r = list[0]; receiver = r; prefs.receiver = r }
                else -> { CastState.status.value = "Pick your receiver below, then tap Cast"; return null }
            }
        }
        return r
    }

    fun startCast(r: Renderer, text: String) {
        CastState.status.value = "Starting..."
        ctx.startForegroundService(
            Intent(ctx, CastService::class.java)
                .putExtra(CastService.EXTRA_LINK, text)
                .putExtra(CastService.EXTRA_NAME, r.name)
                .putExtra(CastService.EXTRA_CONTROL, r.controlUrl)
        )
    }

    fun startLocalCast(r: Renderer, uri: Uri) {
        CastState.status.value = "Starting..."
        ctx.startForegroundService(
            Intent(ctx, CastService::class.java)
                .putExtra(CastService.EXTRA_URI, uri.toString())
                .putExtra(CastService.EXTRA_NAME, r.name)
                .putExtra(CastService.EXTRA_CONTROL, r.controlUrl)
        )
    }

    suspend fun castLink(text: String) {
        val r = pickReceiver() ?: return
        startCast(r, text)
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch { pickReceiver()?.let { startLocalCast(it, uri) } }
        }
    }

    LaunchedEffect(incoming.value) {
        incoming.value?.let {
            link = it
            incoming.value = null
            castLink(it)
        }
    }

    // VLC test mode: open the stream in VLC as soon as the service has it ready.
    LaunchedEffect(testUrl) { testUrl?.let { openInVlc(ctx, it) } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                Text("Homecast", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Casting to ${receiver?.name ?: "no receiver yet"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // What to play
            SectionCard {
                OutlinedTextField(
                    value = link, onValueChange = { link = it },
                    placeholder = { Text("Search YouTube or paste a link") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        TextButton(onClick = {
                            val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            if (!t.isNullOrBlank()) link = t
                        }) { Text("Paste") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { scope.launch { if (isLink) castLink(link) else searchVideos(link) } },
                        enabled = link.isNotBlank() && !busy,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text(if (isLink) "Cast" else "Search", fontWeight = FontWeight.SemiBold) }
                    FilledTonalButton(
                        onClick = { videoPicker.launch(arrayOf("video/*")) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text("From phone") }
                }
            }

            // Status
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(status, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
                if (casting) {
                    FilledTonalButton(
                        onClick = { ctx.startService(Intent(ctx, CastService::class.java).setAction(CastService.ACTION_STOP)) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Stop casting") }
                }
                testUrl?.let { url ->
                    Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { openInVlc(ctx, url) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("Open in VLC") }
                        OutlinedButton(
                            onClick = {
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("Homecast stream", url))
                                CastState.status.value = "Stream URL copied. In VLC on a PC: Media > Open Network Stream."
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("Copy URL") }
                    }
                }
            }

            // Search results
            if (results.isNotEmpty()) {
                SectionCard("Results") {
                    results.forEach { v ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { scope.launch { castLink(v.url) } }
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                Text(
                                    "${v.channel} · ${formatDuration(v.seconds)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Receivers
            SectionCard("Receivers") {
                (found + LOCAL_RENDERER).forEach { r ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { receiver = r; prefs.receiver = r }
                    ) {
                        RadioButton(
                            selected = receiver?.controlUrl == r.controlUrl,
                            onClick = { receiver = r; prefs.receiver = r }
                        )
                        Column {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge)
                            if (r.isLocal) {
                                Text(
                                    "Plays in VLC, no receiver needed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = receiverIp,
                    onValueChange = { receiverIp = it; prefs.receiverIp = it },
                    label = { Text("Receiver IP (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                FilledTonalButton(
                    onClick = { scope.launch { search() } },
                    enabled = !searching,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(if (searching) "Searching..." else "Search for receivers") }
            }

            // Settings
            SectionCard("Playback") {
                SwitchRow(
                    "Prefer 1080p",
                    if (prefer1080) "1080p when available, otherwise 720p with seeking."
                    else "720p with seeking when available, otherwise 1080p without seeking.",
                    prefer1080
                ) { prefer1080 = it; prefs.prefer1080 = it }
                SwitchRow(
                    "Remux direct links",
                    if (remuxDirect) "Video files (.mkv etc.) are converted to TS for fussy receivers. No seeking."
                    else "Video files (.mkv etc.) play as they are, with seeking.",
                    remuxDirect
                ) { remuxDirect = it; prefs.remuxDirect = it }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
