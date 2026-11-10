package app.ghostcast

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class Renderer(val name: String, val controlUrl: String)

/** Shared between the UI and the cast service. */
object CastState {
    val status = MutableStateFlow("Paste or share a YouTube link")
    val casting = MutableStateFlow(false)
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
}
