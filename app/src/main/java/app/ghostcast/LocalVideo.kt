package app.ghostcast

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream

data class LocalInfo(val name: String, val mime: String, val videoMime: String?, val size: Long) {
    val isH264: Boolean get() = videoMime == "video/avc"
    val isHevc: Boolean get() = videoMime == "video/hevc"
}

object LocalVideo {
    fun inspect(ctx: Context, uri: Uri): LocalInfo {
        val cr = ctx.contentResolver
        var name = "Video"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = if (c.isNull(1)) -1L else c.getLong(1)
            }
        }
        val mime = cr.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        var videoMime: String? = null
        runCatching {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (m?.startsWith("video/") == true) { videoMime = m; break }
            }
            ex.release()
        }
        return LocalInfo(name, mime, videoMime, size)
    }

    fun protocolInfo(i: LocalInfo): String = when {
        i.mime == "video/mp4" && i.isH264 ->
            "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD_AAC;DLNA.ORG_OP=01"
        // HEVC has no universally supported DLNA profile name: leave PN out and keep the real mime
        i.isHevc -> "http-get:*:${i.mime}:DLNA.ORG_OP=01;DLNA.ORG_CI=0"
        else -> "http-get:*:${i.mime}:DLNA.ORG_OP=01"
    }
}

/** Serves a video the user picked from the phone, with Range support so the receiver can seek. */
class UriFileServer(
    private val ctx: Context,
    private val uri: Uri,
    private val mime: String,
    port: Int
) : NanoHTTPD(port) {

    private val length: Long = run {
        val s = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        if (s >= 0) s else LocalVideo.inspect(ctx, uri).size
    }

    override fun serve(session: IHTTPSession): Response {
        if (length <= 0) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "unknown size")

        var start = 0L
        var end = length - 1
        var partial = false
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val p = range.removePrefix("bytes=").split("-")
            start = p[0].toLongOrNull() ?: 0L
            end = p.getOrNull(1)?.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
            partial = true
            if (start >= length) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                    .apply { addHeader("Content-Range", "bytes */$length") }
            }
        }

        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "cannot open file")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        (stream as FileInputStream).channel.position(start)

        val r = newFixedLengthResponse(
            if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            mime, stream, end - start + 1
        )
        r.addHeader("Accept-Ranges", "bytes")
        if (partial) r.addHeader("Content-Range", "bytes $start-$end/$length")
        r.addHeader("transferMode.dlna.org", "Streaming")
        r.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        return r
    }

    fun source(phoneIp: String, port: Int, info: LocalInfo) = CastSource(
        url = "http://$phoneIp:$port/video",
        protocolInfo = LocalVideo.protocolInfo(info),
        seekable = true
    )
}
