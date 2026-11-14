package app.ghostcast

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class Renderer(val name: String, val controlUrl: String)

/** Virtual receiver for testing without the real one: serves the stream and opens it in VLC. */
const val LOCAL_PREFIX = "local://"
val LOCAL_RENDERER = Renderer("This phone (VLC test)", "${LOCAL_PREFIX}vlc")
val Renderer.isLocal: Boolean get() = controlUrl.startsWith(LOCAL_PREFIX)

/** Shared between the UI and the cast service. */
object CastState {
    val status = MutableStateFlow("Search YouTube or paste a link")
    val casting = MutableStateFlow(false)

    /** Set by the service in VLC-test mode: the HTTP URL VLC (on this phone or a PC) can open. */
    val testUrl = MutableStateFlow<String?>(null)
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("homecast", Context.MODE_PRIVATE)

    var receiver: Renderer?
        get() {
            val n = sp.getString("rName", null)
            val c = sp.getString("rControl", null)
            return if (n != null && c != null) Renderer(n, c) else null
        }
        set(v) {
            sp.edit().apply {
                if (v == null) { remove("rName"); remove("rControl") }
                else { putString("rName", v.name); putString("rControl", v.controlUrl) }
            }.apply()
        }

    var prefer1080: Boolean
        get() = sp.getBoolean("p1080", false)
        set(v) { sp.edit().putBoolean("p1080", v).apply() }

    /** Optional manual receiver IP, used as an extra unicast target during discovery. */
    var receiverIp: String
        get() = sp.getString("rIp", "") ?: ""
        set(v) { sp.edit().putString("rIp", v).apply() }
}
