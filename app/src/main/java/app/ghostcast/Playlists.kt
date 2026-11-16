package app.ghostcast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One link in a playlist, plus whatever movie info was found for its file name. */
data class PlaylistItem(val url: String, val fileName: String, val meta: MovieMeta? = null) {
    val title: String
        get() = meta?.title ?: FileNameParser.parse(fileName).title.ifBlank { fileName }

    val displayTitle: String
        get() = meta?.let { m -> m.year?.let { "${m.title} ($it)" } ?: m.title } ?: title

    /** "2010 · Sci-Fi · 148 min", or the raw file name when no movie info was found. */
    val subtitle: String?
        get() = meta?.let {
            listOfNotNull(it.year?.toString(), it.genre, it.runtimeMin?.let { m -> "$m min" })
                .joinToString(" · ").ifBlank { null }
        } ?: fileName

    fun toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("file", fileName)
        .putOpt("meta", meta?.toJson())

    companion object {
        fun fromJson(o: JSONObject) = PlaylistItem(
            url = o.getString("url"),
            fileName = o.getString("file"),
            meta = o.optJSONObject("meta")?.let { MovieMeta.fromJson(it) }
        )

        fun listToJson(items: List<PlaylistItem>): String {
            val a = JSONArray()
            items.forEach { a.put(it.toJson()) }
            return a.toString()
        }

        fun listFromJson(s: String): List<PlaylistItem> = runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

data class Playlist(val id: String, val name: String, val items: List<PlaylistItem>) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })

    companion object {
        fun create(name: String, items: List<PlaylistItem> = emptyList()) =
            Playlist(UUID.randomUUID().toString(), name, items)

        fun fromJson(o: JSONObject): Playlist {
            val a = o.optJSONArray("items") ?: JSONArray()
            return Playlist(
                o.getString("id"),
                o.getString("name"),
                (0 until a.length()).map { PlaylistItem.fromJson(a.getJSONObject(it)) }
            )
        }
    }
}

/** Playlists live in SharedPreferences as one JSON array. */
class PlaylistStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("homecast_playlists", Context.MODE_PRIVATE)

    fun load(): List<Playlist> = runCatching {
        val a = JSONArray(sp.getString("lists", "[]"))
        (0 until a.length()).map { Playlist.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(lists: List<Playlist>) {
        val a = JSONArray()
        lists.forEach { a.put(it.toJson()) }
        sp.edit().putString("lists", a.toString()).apply()
    }
}
