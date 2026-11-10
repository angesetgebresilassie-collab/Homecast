package app.ghostcast

import android.content.Context
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

/** What the UPnP controller needs to cast. */
data class CastSource(val url: String, val protocolInfo: String, val seekable: Boolean)

/* ---------- 720p: muxed H.264 (itag 22, fallback 18), range passthrough, seeking works ---------- */

object Resolver720 {
    /** Returns the direct muxed stream URL, or null if neither itag 22 nor 18 exists. */
    fun resolve(pageUrl: String): String? {
        val info = StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
        val s = info.videoStreams.firstOrNull { it.itag == 22 }
            ?: info.videoStreams.firstOrNull { it.itag == 18 }
        return s?.content
    }
}

class RangeProxyServer(
    private val upstreamUrl: String,
    port: Int,
    host: String? = null            // "127.0.0.1" = loopback only (used to feed FFmpeg)
) : NanoHTTPD(host, port) {
    private val http = OkHttpClient()

    override fun serve(session: IHTTPSession): Response {
        val req = Request.Builder().url(upstreamUrl).apply {
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
        val resp = if (session.method == Method.HEAD || body == null || len < 0) {
            up.close()
            newFixedLengthResponse(status, "video/mp4", null, 0).also { if (len >= 0) it.addHeader("Content-Length", len.toString()) }
        } else {
            newFixedLengthResponse(status, "video/mp4", body.byteStream(), len)
        }
        resp.addHeader("Accept-Ranges", "bytes")
        up.header("Content-Range")?.let { resp.addHeader("Content-Range", it) }
        resp.addHeader("transferMode.dlna.org", "Streaming")
        resp.addHeader("contentFeatures.dlna.org",
            "DLNA.ORG_PN=AVC_MP4_HP_HD_AAC;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        return resp
    }

    fun source(phoneIp: String, port: Int) = CastSource(
        url = "http://$phoneIp:$port/v.mp4",
        protocolInfo = "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD_AAC;DLNA.ORG_OP=01",
        seekable = true
    )
}

/* ---------- 1080p: video-only avc1 + AAC -> FFmpeg -c copy -> MPEG-TS, no seeking ---------- */

class TsStreamServer(
    private val ctx: Context,
    private val videoUrl: String,
    private val audioUrl: String,
    private val basePort: Int,                          // uses basePort, +1 and +2 (loopback inputs)
    private val reencodeAudioToAc3: Boolean = false   // flip on if the GS 9000 plays no sound
) : NanoHTTPD(basePort) {

    private var session: FFmpegSession? = null
    private var pipe: String? = null
    private var videoProxy: RangeProxyServer? = null
    private var audioProxy: RangeProxyServer? = null

    // The free FFmpeg build has no TLS, so it can't open YouTube's https URLs itself.
    // OkHttp (inside RangeProxyServer) does the https fetch; FFmpeg reads plain http on loopback.
    private fun startInputs() {
        if (videoProxy == null) {
            videoProxy = RangeProxyServer(videoUrl, basePort + 1, "127.0.0.1").also { it.start() }
            audioProxy = RangeProxyServer(audioUrl, basePort + 2, "127.0.0.1").also { it.start() }
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
        val cmd = "$net -i $vIn $net -i $aIn " +
            "-map 0:v:0 -map 1:a:0 -c:v copy $audioCodec " +
            "-f mpegts -flush_packets 1 -muxdelay 0 -y $p"
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
