package app.ghostcast

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.launch

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

@Composable
fun HomecastScreen(incoming: MutableState<String?>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf(prefs.receiver) }
    var found by remember { mutableStateOf<List<Renderer>>(emptyList()) }
    var prefer1080 by remember { mutableStateOf(prefs.prefer1080) }
    var searching by remember { mutableStateOf(false) }
    val status by CastState.status.collectAsState()
    val casting by CastState.casting.collectAsState()

    suspend fun search(): List<Renderer> {
        searching = true
        CastState.status.value = "Searching for receivers..."
        val list = UpnpCaster.discover(ctx)
        found = list
        searching = false
        return list
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

    suspend fun castLink(text: String) {
        var r = receiver
        if (r == null) {
            val list = search()
            when {
                list.isEmpty() -> { CastState.status.value = "No receiver found. Is the phone on the same Wi-Fi?"; return }
                list.size == 1 -> { r = list[0]; receiver = r; prefs.receiver = r }
                else -> { CastState.status.value = "Pick your receiver below, then tap Cast"; return }
            }
        }
        startCast(r, text)
    }

    LaunchedEffect(incoming.value) {
        incoming.value?.let {
            link = it
            incoming.value = null
            castLink(it)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Homecast", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = link, onValueChange = { link = it },
            label = { Text("YouTube link") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { scope.launch { castLink(link) } },
            enabled = link.isNotBlank() && !searching,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Cast") }
        if (casting) {
            OutlinedButton(
                onClick = { ctx.startService(Intent(ctx, CastService::class.java).setAction(CastService.ACTION_STOP)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Stop") }
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(12.dp))
        Text("Receiver: ${receiver?.name ?: "none chosen yet"}", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { scope.launch { search() } }, enabled = !searching) {
            Text(if (searching) "Searching..." else "Search for receivers")
        }
        found.forEach { r ->
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
