package app.ghostcast

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

internal fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)
internal fun JSONObject.int(k: String): Int? = if (isNull(k)) null else getInt(k)

data class MovieMeta(
    val title: String,
    val year: Int?,
    val overview: String?,
    val genre: String?,
    val director: String?,
    val poster: String?,
    val runtimeMin: Int?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .putOpt("year", year)
        .putOpt("overview", overview)
        .putOpt("genre", genre)
        .putOpt("director", director)
        .putOpt("poster", poster)
        .putOpt("runtime", runtimeMin)

    companion object {
        fun fromJson(o: JSONObject) = MovieMeta(
            title = o.getString("title"),
            year = o.int("year"),
            overview = o.str("overview"),
            genre = o.str("genre"),
            director = o.str("director"),
            poster = o.str("poster"),
            runtimeMin = o.int("runtime")
        )
    }
}

/** Turns "The.Matrix.1999.1080p.BluRay.x264-GRP.mkv" into title "The Matrix" + year 1999. */
object FileNameParser {
    data class Parsed(val title: String, val year: Int?, val episode: String?)

    private val JUNK = Regex(
        "(?i)\\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd|blu-?ray|bdrip|brrip|web-?rip|web-?dl|hdrip|hdtv|dvdrip|dvdscr|remux|" +
            "x264|x265|h ?26[45]|hevc|avc|xvid|divx|hdr10|hdr|10bit|aac|ac3|eac3|dts|ddp?5|atmos|truehd|extended|unrated|" +
            "remastered|directors? cut|imax|proper|repack|multi|dual|yify|yts|rarbg|amzn|nf|hmax|dsnp)\\b"
    )
    private val YEAR = Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)")
    private val EPISODE = Regex("(?i)\\bS\\d{1,2}[ ._-]?E\\d{1,3}\\b")

    fun parse(fileName: String): Parsed {
        var s = fileName.trim()
        val dot = s.lastIndexOf('.')
        if (dot > 0 && s.substring(dot + 1).lowercase() in DirectLink.EXT) s = s.substring(0, dot)

        s = s.replace(Regex("^\\s*\\[[^\\]]*]\\s*"), "")          // leading [release group]
            .replace('.', ' ').replace('_', ' ')
            .replace(Regex("[\\[\\](){}]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        EPISODE.find(s)?.let { m ->
            val t = s.substring(0, m.range.first).trim(' ', '-')
            if (t.isNotEmpty()) return Parsed(t, null, m.value.uppercase())
        }

        val junkAt = JUNK.findAll(s).map { it.range.first }.firstOrNull { it > 0 } ?: s.length
        // The release year is the last year before the first junk tag ("Blade Runner 2049 2017 1080p").
        val yearHit = YEAR.findAll(s).filter { it.range.first in 1 until junkAt }.lastOrNull()
        val cut = yearHit?.range?.first ?: junkAt
        val title = s.substring(0, cut).trim(' ', '-')
        return Parsed(title.ifEmpty { s }, yearHit?.value?.toInt(), null)
    }
}

/** Finds poster, plot, genre and director through the keyless iTunes Search API. Blocking: call off the main thread. */
object MovieLookup {
    private val http = OkHttpClient()

    fun lookup(fileName: String): MovieMeta? {
        val p = FileNameParser.parse(fileName)
        if (p.episode != null || p.title.isBlank()) return null
        return runCatching {
            search(p.title, p.year)
                // "Blade Runner 2049" with no separate year: retry with the year as part of the title
                ?: p.year?.let { search("${p.title} $it", null) }
        }.getOrNull()
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun search(title: String, year: Int?): MovieMeta? {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", title)
            .addQueryParameter("media", "movie")
            .addQueryParameter("entity", "movie")
            .addQueryParameter("limit", "15")
            .build()
        val body = http.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        } ?: return null
        val arr = JSONObject(body).optJSONArray("results") ?: return null

        val q = norm(title)
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("kind") != "feature-movie") continue
            val n = norm(o.optString("trackName"))
            var score = when {
                n == q -> 3
                n.isNotEmpty() && (n.startsWith(q) || q.startsWith(n)) -> 1
                else -> 0
            }
            if (score == 0) continue
            val y = o.optString("releaseDate").take(4).toIntOrNull()
            if (year != null && y != null) score += when (abs(y - year)) { 0 -> 2; 1 -> 1; else -> 0 }
            if (score > bestScore) { best = o; bestScore = score }
        }
        val o = best ?: return null

        return MovieMeta(
            title = o.optString("trackName"),
            year = o.optString("releaseDate").take(4).toIntOrNull(),
            overview = o.optString("longDescription").ifBlank { o.optString("shortDescription") }.ifBlank { null },
            genre = o.optString("primaryGenreName").ifBlank { null },
            director = o.optString("artistName").ifBlank { null },
            poster = o.optString("artworkUrl100").ifBlank { null }?.replace("100x100bb", "600x600bb"),
            runtimeMin = o.optLong("trackTimeMillis", 0L).takeIf { it > 0 }?.let { (it / 60000).toInt() }
        )
    }
}
