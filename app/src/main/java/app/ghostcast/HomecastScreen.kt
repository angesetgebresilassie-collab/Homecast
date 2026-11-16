package app.ghostcast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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

private val LINK_RE = Regex("https?://\\S+")

@Composable
fun HomecastScreen(incoming: MutableState<String?>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { PlaylistStore(ctx) }
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf(prefs.receiver) }
    var receiverIp by remember { mutableStateOf(prefs.receiverIp) }
    var found by remember { mutableStateOf<List<Renderer>>(emptyList()) }
    var remuxDirect by remember { mutableStateOf(prefs.remuxDirect) }
    var searching by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf(store.load()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val status by CastState.status.collectAsState()
    val casting by CastState.casting.collectAsState()
    val testUrl by CastState.testUrl.collectAsState()
    val nowPlaying by CastState.nowPlaying.collectAsState()
    val isLink = link.contains("http")
    val busy = searching || adding
    val cs = MaterialTheme.colorScheme

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

    fun startQueue(r: Renderer, items: List<PlaylistItem>, index: Int = 0) {
        CastState.status.value = "Starting..."
        ctx.startForegroundService(
            Intent(ctx, CastService::class.java)
                .putExtra(CastService.EXTRA_QUEUE, PlaylistItem.listToJson(items))
                .putExtra(CastService.EXTRA_INDEX, index)
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
        val url = LINK_RE.find(text)?.value
        if (url == null) {
            CastState.status.value = "No link found in that text"
            return
        }
        val r = pickReceiver() ?: return
        startQueue(r, listOf(PlaylistItem(url, DirectLink.nameOf(url))))
    }

    suspend fun playList(list: Playlist, index: Int = 0) {
        if (list.items.isEmpty()) return
        val r = pickReceiver() ?: return
        startQueue(r, list.items, index)
    }

    fun savePlaylists(updated: List<Playlist>) {
        playlists = updated
        store.save(updated)
    }

    /** Checks the link, looks up movie info from its file name, and files it in a playlist. */
    suspend fun addLink(text: String, listId: String?, newName: String?) {
        val url = LINK_RE.find(text)?.value
        if (url == null) {
            CastState.status.value = "No link found in that text"
            return
        }
        adding = true
        CastState.status.value = "Adding to playlist..."
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (DirectLink.isYouTube(url)) error("YouTube links are not supported. Use a direct video link.")
                val info = DirectLink.probe(url) ?: error("That is not a direct video link")
                PlaylistItem(url, info.name, MovieLookup.lookup(info.name))
            }
        }
        adding = false
        result.onSuccess { item ->
            val target: String
            if (listId != null) {
                target = listId
                savePlaylists(playlists.map { if (it.id == listId) it.copy(items = it.items + item) else it })
            } else {
                val created = Playlist.create(newName?.takeIf { it.isNotBlank() } ?: "Playlist ${playlists.size + 1}", listOf(item))
                target = created.id
                savePlaylists(playlists + created)
            }
            expandedId = target
            link = ""
            CastState.status.value = if (item.meta != null) "Added ${item.displayTitle}"
            else "Added ${item.fileName} (no movie info found for that file name)"
        }.onFailure {
            CastState.status.value = it.message ?: "Could not add that link"
        }
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

    if (showAdd) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showAdd = false },
            onPick = { id -> showAdd = false; scope.launch { addLink(link, id, null) } },
            onCreate = { n -> showAdd = false; scope.launch { addLink(link, null, n) } }
        )
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IconBubble(Icons.Filled.Cast, cs.primaryContainer, cs.onPrimaryContainer, size = 60.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Homecast", style = MaterialTheme.typography.headlineLarge)
                    Surface(shape = CircleShape, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                if (receiver?.isLocal == true) Icons.Filled.PhoneAndroid else Icons.Filled.Tv,
                                null, Modifier.size(16.dp)
                            )
                            Text(receiver?.name ?: "No receiver yet", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }

            // What to play
            SectionCard {
                TextField(
                    value = link, onValueChange = { link = it },
                    placeholder = { Text("Paste a video link") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = roundedFieldColors(),
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            if (!t.isNullOrBlank()) link = t
                        }) { Icon(Icons.Filled.ContentPaste, contentDescription = "Paste") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // Connected button group
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExpressiveButton(
                        text = "Cast",
                        icon = Icons.Filled.Cast,
                        onClick = { scope.launch { castLink(link) } },
                        enabled = isLink && !busy,
                        innerEnd = true,
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveButton(
                        text = if (adding) "Adding..." else "Add to list",
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        onClick = { showAdd = true },
                        enabled = isLink && !busy,
                        loading = adding,
                        container = cs.secondaryContainer,
                        content = cs.onSecondaryContainer,
                        innerStart = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                ExpressiveButton(
                    text = "From phone",
                    icon = Icons.Filled.VideoLibrary,
                    onClick = { videoPicker.launch(arrayOf("video/*")) },
                    container = cs.tertiaryContainer,
                    content = cs.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Status + now playing
            SectionCard(container = if (casting) cs.primaryContainer else cs.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (busy) {
                        ExpressiveLoader(Modifier.size(28.dp))
                    } else {
                        Icon(
                            if (casting) Icons.Filled.CastConnected else Icons.Filled.Info,
                            contentDescription = null,
                            tint = if (casting) cs.onPrimaryContainer else cs.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (casting) cs.onPrimaryContainer else cs.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                nowPlaying?.takeIf { casting }?.let { np ->
                    NowPlayingRow(
                        np,
                        onPrev = { ctx.startService(Intent(ctx, CastService::class.java).setAction(CastService.ACTION_PREV)) },
                        onNext = { ctx.startService(Intent(ctx, CastService::class.java).setAction(CastService.ACTION_NEXT)) }
                    )
                }
                AnimatedVisibility(visible = casting) {
                    ExpressiveButton(
                        text = "Stop casting",
                        icon = Icons.Filled.Stop,
                        onClick = { ctx.startService(Intent(ctx, CastService::class.java).setAction(CastService.ACTION_STOP)) },
                        container = cs.errorContainer,
                        content = cs.onErrorContainer,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                testUrl?.let { url ->
                    Text(url, style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ExpressiveButton(
                            text = "Open in VLC",
                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                            onClick = { openInVlc(ctx, url) },
                            innerEnd = true,
                            modifier = Modifier.weight(1f)
                        )
                        ExpressiveButton(
                            text = "Copy URL",
                            icon = Icons.Filled.ContentCopy,
                            onClick = {
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("Homecast stream", url))
                                CastState.status.value = "Stream URL copied. In VLC on a PC: Media > Open Network Stream."
                            },
                            container = cs.secondaryContainer,
                            content = cs.onSecondaryContainer,
                            innerStart = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Playlists
            SectionCard(title = "Playlists", icon = Icons.AutoMirrored.Filled.PlaylistPlay) {
                if (playlists.isEmpty()) {
                    Text(
                        "Paste a link and tap Add to list. Movie info and posters are looked up from the file name.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                }
                playlists.forEach { list ->
                    PlaylistCard(
                        list = list,
                        expanded = expandedId == list.id,
                        onToggle = { expandedId = if (expandedId == list.id) null else list.id },
                        onPlayAll = { scope.launch { playList(list, 0) } },
                        onPlayItem = { i -> scope.launch { playList(list, i) } },
                        onRemoveItem = { i ->
                            savePlaylists(playlists.map {
                                if (it.id == list.id) it.copy(items = it.items.filterIndexed { j, _ -> j != i }) else it
                            })
                        },
                        onDelete = {
                            savePlaylists(playlists.filter { it.id != list.id })
                            if (expandedId == list.id) expandedId = null
                        }
                    )
                }
            }

            // Receivers
            SectionCard(title = "Receivers", icon = Icons.Filled.Tv) {
                (found + LOCAL_RENDERER).forEach { r ->
                    val selected = receiver?.controlUrl == r.controlUrl
                    Surface(
                        onClick = { receiver = r; prefs.receiver = r },
                        shape = MaterialTheme.shapes.large,
                        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            IconBubble(
                                if (r.isLocal) Icons.Filled.PhoneAndroid else Icons.Filled.Tv,
                                if (selected) cs.primary else cs.secondaryContainer,
                                if (selected) cs.onPrimary else cs.onSecondaryContainer
                            )
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium)
                                if (r.isLocal) {
                                    Text(
                                        "Plays in VLC, no receiver needed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.onSurfaceVariant
                                    )
                                }
                            }
                            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = cs.onPrimaryContainer)
                        }
                    }
                }
                TextField(
                    value = receiverIp,
                    onValueChange = { receiverIp = it; prefs.receiverIp = it },
                    placeholder = { Text("Receiver IP (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = roundedFieldColors(),
                    leadingIcon = { Icon(Icons.Filled.Router, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                ExpressiveButton(
                    text = if (searching) "Searching..." else "Search for receivers",
                    icon = Icons.Filled.Refresh,
                    onClick = { scope.launch { search() } },
                    enabled = !searching,
                    loading = searching,
                    container = cs.secondaryContainer,
                    content = cs.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Settings
            SectionCard(title = "Playback", icon = Icons.Filled.Tune) {
                SwitchRow(
                    Icons.Filled.Tune,
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
