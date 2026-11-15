package app.ghostcast

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

/** What the UPnP controller needs to cast. */
data class CastSource(val url: String, val protocolInfo: String, val seekable: Boolean)

/** Same browser identity NewPipe uses; googlevideo rejects OkHttp's default user agent more often. */
private const val UPSTREAM_UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

/* ---------- Direct media links (.mkv, .mp4, ... on any web server) ---------- */

object DirectLink {
    data class Info(val url: String, val mime: String, val name: String)

    private val EXT = setOf("mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "flv", "wmv", "mpg", "mpeg", "3gp")
    private val http = OkHttpClient()

    private fun isYouTube(url: String): Boolean {
        val h = Uri.parse(url).host?.lowercase() ?: return false
        return h.endsWith("youtube.com") || h.endsWith("youtu.be") || h.endsWith("youtube-nocookie.com")
    }

    fun mimeFor(ext: String): String? = when (ext) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mpeg"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "mpg", "mpeg" -> "video/mpeg"
        "3gp" -> "video/3gpp"
        else -> null
    }

    fun extFor(mime: String): String = when (mime) {
        "video/x-matroska" -> "mkv"
        "video/webm" -> "webm"
        "video/x-msvideo" -> "avi"
        "video/quicktime" -> "mov"
        "video/mpeg" -> "ts"
        "video/x-flv" -> "flv"
        "video/x-ms-wmv" -> "wmv"
        "video/3gpp" -> "3gp"
        else -> "mp4"
    }

    /** Info if [url] is a direct video file link, otherwise null (YouTube links are never direct). */
    fun probe(url: String): Info? {
        if (isYouTube(url)) return null
        val last = Uri.parse(url).lastPathSegment ?: ""
        val name = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last).ifBlank { "Video" }
        val ext = name.substringAfterLast('.', "").lowercase()

        // Many hosts reject HEAD, so ask for the first byte instead.
        var contentType: String? = null
        runCatching {
            http.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use {
                contentType = it.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            }
        }
        val ct = contentType
        val byExt = ext in EXT
        val byType = ct != null && (ct.startsWith("video/") || ct == "application/x-matroska")
        if (!byExt && !byType) return null

        val mime = mimeFor(ext) ?: ct?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        return Info(url, mime, name)
    }
}

/* ---------- YouTube stream helpers ---------- */

object Resolver720 {
    /** Fast mode: the muxed stream (itag 22 = 720p if it exists, otherwise itag 18 = 640x360). */
    fun resolve(pageUrl: String): String? {
        val info = StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
        val s = info.videoStreams.firstOrNull { it.itag == 22 }
            ?: info.videoStreams.firstOrNull { it.itag == 18 }
        return s?.content
    }
}

object UpstreamCheck {
    private val http = OkHttpClient()

    /** Throws a readable error if YouTube refuses to hand out this stream with our headers. */
    fun verify(url: String, label: String) {
        val sep = if (url.contains('?')) "&" else "?"
        val req = Request.Builder()
            .url(if (url.contains("googlevideo.com")) "$url${sep}range=0-1" else url)
            .header("User-Agent", UPSTREAM_UA)
            .apply { if (!url.contains("googlevideo.com")) header("Range", "bytes=0-1") }
            .build()
        http.newCall(req).execute().use {
            check(it.code == 200 || it.code == 206) { "YouTube refused the $label stream (HTTP ${it.code})" }
        }
    }
}

/** Reads googlevideo's range-limited chunks one after another so the stream isn't throttled. */
private class ChunkedUpstream(
    private val http: OkHttpClient,
    private val url: String,
    start: Long,
    private val end: Long
) : InputStream() {
    private companion object { const val CHUNK = 9_900_000L }

    private var pos = start
    private var cur: InputStream? = null
    private var curResp: okhttp3.Response? = null

    private fun openNext() {
        val chunkEnd = minOf(end, pos + CHUNK - 1)
        val req = Request.Builder().url("$url&range=$pos-$chunkEnd").header("User-Agent", UPSTREAM_UA).build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) { resp.close(); throw IOException("YouTube stream refused (HTTP ${resp.code})") }
        curResp = resp
        cur = resp.body?.byteStream() ?: throw IOException("Empty YouTube response")
    }

    private fun closeCur() {
        runCatching { cur?.close() }
        runCatching { curResp?.close() }
        cur = null; curResp = null
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            if (pos > end) return -1
            var fresh = false
            if (cur == null) { openNext(); fresh = true }
            val n = cur!!.read(b, off, len)
            if (n > 0) { pos += n; return n }
            closeCur()
            if (fresh) return -1          // an empty chunk means the stream is over (avoids looping forever)
        }
    }

    override fun close() = closeCur()
}

/**
 * Proxies one upstream URL to the receiver / FFmpeg with Range support.
 * googlevideo streams (which carry their size in the clen parameter) are read in chunks.
 */
class RangeProxyServer(
    private val upstreamUrl: String,
    port: Int,
    host: String? = null,            // "127.0.0.1" = loopback only (used to feed FFmpeg)
    private val mime: String = "video/mp4"
) : NanoHTTPD(host, port) {
    private val http = OkHttpClient()
    private val clen: Long? =
        if (upstreamUrl.contains("googlevideo.com"))
            Regex("[?&]clen=(\\d+)").find(upstreamUrl)?.groupValues?.get(1)?.toLongOrNull()
        else null

    private fun decorate(resp: Response): Response {
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("transferMode.dlna.org", "Streaming")
        resp.addHeader("contentFeatures.dlna.org",
            if (mime == "video/mp4")
                "DLNA.ORG_PN=AVC_MP4_HP_HD_AAC;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            else
                "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        return resp
    }

    override fun serve(session: IHTTPSession): Response {
        clen?.let { return serveChunked(session, it) }

        val req = Request.Builder().url(upstreamUrl).header("User-Agent", UPSTREAM_UA).apply {
            session.headers["range"]?.let { header("Range", it) }
            if (session.method == Method.HEAD) head()
        }.build()

        val up = http.newCall(req).execute()
        val status = when (up.code) {
            200 -> Response.Status.OK
            206 -> Response.Status.PARTIAL_CONTENT
            416 -> Response.Status.RANGE_NOT_SATISFIABLE
            else -> { up.close(); return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "upstream ${up.code}") }
        }
        val len = up.header("Content-Length")?.toLongOrNull() ?: -1L
        val body = up.body
        val resp = when {
            session.method == Method.HEAD || body == null -> {
                up.close()
                newFixedLengthResponse(status, mime, null, 0).also { if (len >= 0) it.addHeader("Content-Length", len.toString()) }
            }
            len < 0 -> newChunkedResponse(status, mime, body.byteStream())
            else -> newFixedLengthResponse(status, mime, body.byteStream(), len)
        }
        up.header("Content-Range")?.let { resp.addHeader("Content-Range", it) }
        return decorate(resp)
    }

    private fun serveChunked(session: IHTTPSession, total: Long): Response {
        var start = 0L
        var end = total - 1
        var partial = false
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val p = range.removePrefix("bytes=").split("-")
            start = p[0].toLongOrNull() ?: 0L
            end = p.getOrNull(1)?.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
            partial = true
            if (start >= total) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                    .apply { addHeader("Content-Range", "bytes */$total") }
            }
        }
        val len = end - start + 1
        val status = if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val resp = if (session.method == Method.HEAD) {
            newFixedLengthResponse(status, mime, null, 0).also { it.addHeader("Content-Length", len.toString()) }
        } else {
            newFixedLengthResponse(status, mime, ChunkedUpstream(http, upstreamUrl, start, end), len)
        }
        if (partial) resp.addHeader("Content-Range", "bytes $start-$end/$total")
        return decorate(resp)
    }

    fun source(phoneIp: String, port: Int) = CastSource(
        url = "http://$phoneIp:$port/v.${DirectLink.extFor(mime)}",
        protocolInfo = if (mime == "video/mp4") "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD_AAC;DLNA.ORG_OP=01"
        else "http-get:*:$mime:DLNA.ORG_OP=01",
        seekable = true
    )
}

/* ---------- HD: video-only avc1 (up to 1080p) + AAC -> FFmpeg -c copy -> MPEG-TS, no seeking ---------- */
/* Also used to remux a single direct link (audioUrl = null) into TS for fussy receivers. */

class TsStreamServer(
    private val ctx: Context,
    private val videoUrl: String,
    private val audioUrl: String?,                      // null = audio comes from the same file as the video
    private val basePort: Int,                          // uses basePort, +1 and +2 (loopback inputs)
    private val reencodeAudioToAc3: Boolean = false   // flip on if the GS 9000 plays no sound
) : NanoHTTPD(basePort) {

    private var session: FFmpegSession? = null
    private var pipe: String? = null
    private var videoProxy: RangeProxyServer? = null
    private var audioProxy: RangeProxyServer? = null

    // The free FFmpeg build has no TLS, so it can't open https URLs itself.
    // OkHttp (inside RangeProxyServer) does the https fetch; FFmpeg reads plain http on loopback.
    private fun startInputs() {
        if (videoProxy == null) {
            videoProxy = RangeProxyServer(videoUrl, basePort + 1, "127.0.0.1").also { it.start() }
            audioProxy = audioUrl?.let { RangeProxyServer(it, basePort + 2, "127.0.0.1").also { s -> s.start() } }
        }
    }

    @Synchronized
    override fun serve(s: IHTTPSession): Response {
        if (s.method == Method.HEAD) return base(newFixedLengthResponse(Response.Status.OK, "video/mpeg", null, 0))

        stopFfmpeg()                       // one active cast at a time; a reconnect restarts cleanly
        startInputs()
        val p = FFmpegKitConfig.registerNewFFmpegPipe(ctx).also { pipe = it }

        val net = "-reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 5"
        val audioCodec = if (reencodeAudioToAc3) "-c:a ac3 -b:a 384k" else "-c:a copy"
        val vIn = "http://127.0.0.1:${basePort + 1}/v.mp4"
        val aIn = "http://127.0.0.1:${basePort + 2}/v.mp4"
        val cmd = if (audioUrl != null) {
            "$net -i $vIn $net -i $aIn " +
                "-map 0:v:0 -map 1:a:0 -c:v copy $audioCodec " +
                "-f mpegts -flush_packets 1 -muxdelay 0 -y $p"
        } else {
            "$net -i $vIn " +
                "-map 0:v:0 -map 0:a:0? -c:v copy $audioCodec " +
                "-f mpegts -flush_packets 1 -muxdelay 0 -y $p"
        }
        session = FFmpegKit.executeAsync(cmd) { /* completed; pipe closes -> stream ends */ }

        val stream = object : FilterInputStream(FileInputStream(p)) {
            override fun close() { super.close(); stopFfmpeg() }   // receiver disconnected
        }
        return base(newChunkedResponse(Response.Status.OK, "video/mpeg", stream))
    }

    private fun base(r: Response): Response {
        r.addHeader("transferMode.dlna.org", "Streaming")
        r.addHeader("contentFeatures.dlna.org",
            "DLNA.ORG_PN=AVC_TS_HD_50_AAC_ISO;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        return r
    }

    @Synchronized
    fun stopFfmpeg() {
        session?.let { FFmpegKit.cancel(it.sessionId) }
        pipe?.let { FFmpegKitConfig.closeFFmpegPipe(it) }
        session = null; pipe = null
    }

    /** Call when the cast ends: stops FFmpeg, the loopback proxies and this server. */
    fun shutdown() {
        stopFfmpeg()
        videoProxy?.stop(); audioProxy?.stop()
        videoProxy = null; audioProxy = null
        stop()
    }

    fun source(phoneIp: String, port: Int) = CastSource(
        url = "http://$phoneIp:$port/v.ts",
        protocolInfo = "http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_HD_50_AAC_ISO;DLNA.ORG_OP=00",
        seekable = false
    )
}
