package app.ghostcast

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

internal fun <T> bouncy() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

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

    /** A link shared from any app lands here and casts straight away. */
    private fun readShared(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { incoming.value = it }
        }
    }
}

/** Opens the test stream in VLC; falls back to the system app chooser if VLC isn't installed. */
internal fun openInVlc(ctx: Context, url: String) {
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
internal fun ExpressiveButton(
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
            if (loading) ExpressiveLoader(Modifier.size(20.dp), contentColorFallback(enabled, content, colors.onSurface))
            else Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private fun contentColorFallback(enabled: Boolean, content: Color, disabled: Color): Color =
    if (enabled) content else disabled.copy(alpha = 0.38f)

/** A rounded square that spins and morphs towards a circle and back (stand-in for the expressive loading indicator). */
@Composable
internal fun ExpressiveLoader(modifier: Modifier = Modifier.size(22.dp), color: Color = MaterialTheme.colorScheme.primary) {
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
internal fun IconBubble(icon: ImageVector, container: Color, content: Color, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size / 2)) }
}

@Composable
internal fun SectionCard(
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
internal fun SwitchRow(icon: ImageVector, title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun roundedFieldColors() = TextFieldDefaults.colors(
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
)
