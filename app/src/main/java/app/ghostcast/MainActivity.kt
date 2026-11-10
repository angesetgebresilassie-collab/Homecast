package app.ghostcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { HomecastScreen() } }
    }
}

private fun resolveSummary(url: String, is720: Boolean): String =
    if (is720) {
        if (Resolver720.resolve(url) != null) "720p stream found (seeking works)"
        else "No 720p/360p muxed stream for this video. Try 1080p."
    } else {
        val (v, a) = Resolver.resolve(url)
        "1080p path: ${v.height}p H.264 + audio ${a.averageBitrate} kbps (no seeking)"
    }

@androidx.compose.runtime.Composable
fun HomecastScreen() {
    var url by remember { mutableStateOf("") }
    var is720 by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Paste a YouTube link") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Homecast", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("YouTube link") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row { RadioButton(selected = is720, onClick = { is720 = true }); Text("720p, seeking", Modifier.padding(top = 12.dp)) }
        Row { RadioButton(selected = !is720, onClick = { is720 = false }); Text("1080p, no seeking", Modifier.padding(top = 12.dp)) }
        Button(onClick = {
            scope.launch {
                status = "Resolving..."
                status = withContext(Dispatchers.IO) {
                    runCatching { resolveSummary(url, is720) }.getOrElse { "Error: ${it.message}" }
                }
            }
        }) { Text("Resolve") }
        Spacer(Modifier.height(16.dp))
        Text(status)
    }
}
