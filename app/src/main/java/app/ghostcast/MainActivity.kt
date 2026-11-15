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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Typography
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ---------- Expressive-style theme: vibrant palette, big rounded shapes, bold type, springy motion ---------- */

private val HomecastColors = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF2E2063),
    primaryContainer = Color(0xFF4B3C94),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFFFFB1CC),
    onSecondary = Color(0xFF5C1135),
    secondaryContainer = Color(0xFF7A2A4D),
    onSecondaryContainer = Color(0xFFFFD9E6),
    tertiary = Color(0xFF80DBC0),
    onTertiary = Color(0xFF00382B),
    tertiaryContainer = Color(0xFF1C5143),
    onTertiaryContainer = Color(0xFFA0F7DB),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF110E17),
    onBackground = Color(0xFFE7E0EB),
    surface = Color(0xFF110E17),
    onSurface = Color(0xFFE7E0EB),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLow = Color(0xFF1A1721),
    surfaceContainer = Color(0xFF1E1A26),
    surfaceContainerHigh = Color(0xFF292431),
    surfaceContainerHighest = Color(0xFF342E3D),
    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454F)
)

private val HomecastShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

private val HomecastTypography = Typography(
    headlineLarge = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
)

private fun <T> bouncy() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

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
        setContent {
            MaterialTheme(colorScheme = HomecastColors, shapes = HomecastShapes, typography = HomecastTypography) {
                HomecastScreen(incoming)
            }
        }
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

/* ---------- Expressive building blocks ---------- */

/** Pill button that morphs to a squarer shape and squishes while pressed. Inner corners support connected groups. */
@Composable
private fun ExpressiveButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    innerStart: Boolean = false,
    innerEnd: Boolean = false,
    height: Dp = 56.dp,
    loading: Boolean = false
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val outer by animateDpAsState(if (pressed) 16.dp else height / 2, bouncy(), label = "outer")
    val inner by animateDpAsState(if (pressed) 16.dp else 8.dp, bouncy(), label = "inner")
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, bouncy(), label = "scale")
    val start = if (innerStart) inner else outer
    val end = if (innerEnd) inner else outer
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end),
        color = if (enabled) container else colors.onSurface.copy(alpha = 0.12f),
        contentColor = if (enabled) content else colors.onSurface.copy(alpha = 0.38f),
        interactionSource = source,
        modifier = modifier.height(height).graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) ExpressiveLoader(Modifier.size(20.dp), LocalContentColorFallback(enabled, content, colors.onSurface))
            else Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private fun LocalContentColorFallback(enabled: Boolean, content: Color, disabled: Color): Color =
    if (enabled) content else disabled.copy(alpha = 0.38f)

/** A rounded square that spins and morphs towards a circle and back (stand-in for the expressive loading indicator). */
@Composable
private fun ExpressiveLoader(modifier: Modifier = Modifier.size(22.dp), color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition(label = "loader")
    val rotation by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "rotation"
    )
    val round by t.animateFloat(
        initialValue = 0.28f, targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "round"
    )
    Box(
        modifier
            .graphicsLayer { rotationZ = rotation }
            .clip(RoundedCornerShape(percent = (round * 100).toInt()))
            .background(color)
    )
}

@Composable
private fun IconBubble(icon: ImageVector, container: Color, content: Color, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size / 2)) }
}

@Composable
private fun SectionCard(
    title: String? = null,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        modifier = Modifier.fillMaxWidth().animateContentSize(bouncy())
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        title.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBubble(icon, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            thumbContent = if (checked) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
            } else null
        )
    }
}

@Composable
private fun roundedFieldColors() = TextFieldDefaults.colors(
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
)

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
                    placeholder = { Text("Search YouTube or paste a link") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = roundedFieldColors(),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
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
                        text = if (isLink) "Cast" else "Search",
                        icon = if (isLink) Icons.Filled.Cast else Icons.Filled.Search,
                        onClick = { scope.launch { if (isLink) castLink(link) else searchVideos(link) } },
                        enabled = link.isNotBlank() && !busy,
                        innerEnd = true,
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveButton(
                        text = "From phone",
                        icon = Icons.Filled.VideoLibrary,
                        onClick = { videoPicker.launch(arrayOf("video/*")) },
                        container = cs.secondaryContainer,
                        content = cs.onSecondaryContainer,
                        innerStart = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Status
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

            // Search results
            if (results.isNotEmpty()) {
                SectionCard(title = "Results", icon = Icons.Filled.Search) {
                    results.forEach { v ->
                        Surface(
                            onClick = { scope.launch { castLink(v.url) } },
                            shape = MaterialTheme.shapes.large,
                            color = cs.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                IconBubble(Icons.Filled.Movie, cs.tertiaryContainer, cs.onTertiaryContainer)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                    Text(
                                        "${v.channel} · ${formatDuration(v.seconds)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Cast", tint = cs.primary)
                            }
                        }
                    }
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
                    Icons.Filled.HighQuality,
                    "Prefer 1080p",
                    if (prefer1080) "1080p when available, otherwise 720p with seeking."
                    else "720p with seeking when available, otherwise 1080p without seeking.",
                    prefer1080
                ) { prefer1080 = it; prefs.prefer1080 = it }
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
