package app.ghostcast

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Movie poster, or a film icon when no poster was found. */
@Composable
internal fun Poster(url: String?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Filled.Movie, contentDescription = null, tint = cs.onSurfaceVariant)
        }
    }
}

/** Poster, title and info of what is playing, with previous/next when it is part of a playlist. */
@Composable
internal fun NowPlayingRow(np: NowPlaying, onPrev: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Poster(np.poster, Modifier.width(56.dp).height(84.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(np.title, style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer, maxLines = 2)
            np.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer.copy(alpha = 0.8f), maxLines = 2)
            }
            if (np.total > 1) {
                Text(
                    "${np.position} of ${np.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
        if (np.total > 1) {
            Row {
                IconButton(onClick = onPrev, enabled = np.position > 1) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = cs.onPrimaryContainer)
                }
                IconButton(onClick = onNext, enabled = np.position < np.total) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = cs.onPrimaryContainer)
                }
            }
        }
    }
}

/** One playlist: header with Play all, and (expanded) its videos with posters and info. */
@Composable
internal fun PlaylistCard(
    list: Playlist,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().animateContentSize(bouncy())
    ) {
        Column {
            Surface(onClick = onToggle, color = Color.Transparent) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconBubble(Icons.AutoMirrored.Filled.PlaylistPlay, cs.tertiaryContainer, cs.onTertiaryContainer)
                    Column(Modifier.weight(1f)) {
                        Text(list.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            if (list.items.size == 1) "1 video" else "${list.items.size} videos",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onPlayAll, enabled = list.items.isNotEmpty()) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play all", tint = cs.primary)
                    }
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant
                    )
                }
            }
            if (expanded) {
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    list.items.forEachIndexed { i, item ->
                        Surface(
                            onClick = { onPlayItem(i) },
                            shape = MaterialTheme.shapes.medium,
                            color = cs.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Poster(item.meta?.poster, Modifier.width(48.dp).height(72.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                    item.subtitle?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                                    }
                                    item.meta?.overview?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
                                    }
                                }
                                IconButton(onClick = { onRemoveItem(i) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = cs.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = cs.error)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete playlist")
                    }
                }
            }
        }
    }
}

/** Pick an existing playlist or create a new one for the link being added. */
@Composable
internal fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                playlists.forEach { l ->
                    Surface(
                        onClick = { onPick(l.id) },
                        shape = MaterialTheme.shapes.large,
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = cs.primary)
                            Column(Modifier.weight(1f)) {
                                Text(l.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(
                                    if (l.items.size == 1) "1 video" else "${l.items.size} videos",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("New playlist name") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = roundedFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() || playlists.isEmpty()
            ) { Text("Create and add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
