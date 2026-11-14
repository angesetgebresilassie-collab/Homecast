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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        readShared(intent)
        setContent { MaterialTheme { HomecastScreen(incoming) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShared(intent)
    }

    /** A link shared from the YouTube app lands here and casts straight away. */
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
fun HomecastScreen(incoming: MutableState<String?>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf(prefs.receiver) }
    var receiverIp by remember { mutableStateOf(prefs.receiverIp) }
    var found by remember { mutableStateOf<List<Renderer>>(emptyList()) }
    var prefer1080 by remember { mutableStateOf(prefs.prefer1080) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<VideoHit>>(emptyList()) }
    var searchingVideos by remember { mutableStateOf(false) }
    val status by CastState.status.collectAsState()
    val casting by CastState.casting.collectAsState()
    val testUrl by CastState.testUrl.collectAsState()
    val isLink = link.contains("http")

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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Homecast", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = link, onValueChange = { link = it },
            label = { Text("Search YouTube or paste a link") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { scope.launch { if (isLink) castLink(link) else searchVideos(link) } },
            enabled = link.isNotBlank() && !searching && !searchingVideos,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isLink) "Cast" else "Search") }
        OutlinedButton(
            onClick = { videoPicker.launch(arrayOf("video/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Cast a video from this phone") }
        if (casting) {
            OutlinedButton(
                onClick = { ctx.startService(Intent(ctx, CastService::class.java).setAction(CastService.ACTION_STOP)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Stop") }
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)

        testUrl?.let { url ->
            Text(url, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openInVlc(ctx, url) }) { Text("Open in VLC") }
                OutlinedButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Homecast stream", url))
                    CastState.status.value = "Stream URL copied. In VLC on a PC: Media > Open Network Stream."
                }) { Text("Copy URL") }
            }
        }

        results.forEach { v ->
            Column(
                Modifier.fillMaxWidth()
                    .clickable { scope.launch { castLink(v.url) } }
                    .padding(vertical = 8.dp)
            ) {
                Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text("${v.channel} · ${formatDuration(v.seconds)}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Receiver: ${receiver?.name ?: "none chosen yet"}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = receiverIp,
            onValueChange = { receiverIp = it; prefs.receiverIp = it },
            label = { Text("Receiver IP (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { scope.launch { search() } }, enabled = !searching) {
            Text(if (searching) "Searching..." else "Search for receivers")
        }
        (found + LOCAL_RENDERER).forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = receiver?.controlUrl == r.controlUrl,
                    onClick = { receiver = r; prefs.receiver = r }
                )
                Text(r.name)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = prefer1080, onCheckedChange = { prefer1080 = it; prefs.prefer1080 = it })
            Text("  Prefer 1080p (no seeking)")
        }
        Text(
            if (prefer1080) "1080p when available, otherwise 720p with seeking."
            else "720p with seeking when available, otherwise 1080p without seeking.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
