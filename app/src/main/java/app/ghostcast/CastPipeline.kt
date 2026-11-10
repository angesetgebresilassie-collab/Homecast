package app.ghostcast

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import org.schabi.newpipe.extractor.MediaFormat as NpFormat

/** 1. Resolve: H.264 (avc1) only, <= 1080p, plus best AAC. Fails instead of falling back. */
object Resolver {
    fun resolve(pageUrl: String): Pair<VideoStream, AudioStream> {
        val info = StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
        val video = info.videoOnlyStreams
            .filter { it.codec?.startsWith("avc1") == true }
            .filter { it.format == NpFormat.MPEG_4 && it.height <= 1080 }
            .maxByOrNull { it.height }
            ?: error("No H.264 (avc1) stream available for this video")
        val audio = info.audioStreams
            .filter { it.format == NpFormat.M4A }
            .maxByOrNull { it.averageBitrate }
            ?: error("No AAC audio stream available")
        return video to audio
    }
}

/** 2. Download in ranged chunks (avoids YouTube throttling). Fallback path: download, remux, serve file. */
object Downloader {
    private val http = OkHttpClient()
    private const val CHUNK = 9_900_000L

    fun download(url: String, out: File, onProgress: (Long) -> Unit = {}) {
        val total = probeSize(url)
        out.outputStream().use { sink ->
            var start = 0L
            while (start < total) {
                val end = minOf(start + CHUNK - 1, total - 1)
                val req = Request.Builder().url("$url&range=$start-$end").build()
                http.newCall(req).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    val body = r.body ?: error("Empty body")
                    body.byteStream().copyTo(sink)
                }
                start = end + 1
                onProgress(start)
            }
        }
    }

    private fun probeSize(url: String): Long {
        val req = Request.Builder().url(url).head().build()
        http.newCall(req).execute().use { r ->
            return r.header("Content-Length")?.toLongOrNull() ?: error("Unknown size")
        }
    }
}

/** 3. Remux: copy samples, no re-encode. Codec stays H.264/AAC untouched. */
object Remuxer {
    fun mux(videoFile: File, audioFile: File, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vEx = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val aEx = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        val vIdx = pick(vEx, "video/")
        val aIdx = pick(aEx, "audio/")
        val vTrack = muxer.addTrack(vEx.getTrackFormat(vIdx))
        val aTrack = muxer.addTrack(aEx.getTrackFormat(aIdx))
        muxer.start()
        copy(vEx, muxer, vTrack)
        copy(aEx, muxer, aTrack)
        muxer.stop(); muxer.release(); vEx.release(); aEx.release()
    }

    private fun pick(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) {
                ex.selectTrack(i); return i
            }
        }
        error("No $prefix track")
    }

    private fun copy(ex: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buf = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            info.set(0, size, ex.sampleTime, ex.sampleFlags)
            muxer.writeSampleData(track, buf, info)
            ex.advance()
        }
    }
}

/** 4. Serve a finished MP4 file to the receiver with proper Range support. */
class FileServer(private val file: File, port: Int) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val len = file.length()
        val range = session.headers["range"]
        val dlna = mapOf(
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to
                "DLNA.ORG_PN=AVC_MP4_HP_HD_AAC;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        )
        if (range == null || !range.startsWith("bytes=")) {
            val r = newFixedLengthResponse(Response.Status.OK, "video/mp4", FileInputStream(file), len)
            r.addHeader("Accept-Ranges", "bytes")
            dlna.forEach { (k, v) -> r.addHeader(k, v) }
            return r
        }
        val parts = range.removePrefix("bytes=").split("-")
        val start = parts[0].toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.toLongOrNull() ?: (len - 1)
        if (start >= len) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                .apply { addHeader("Content-Range", "bytes */$len") }
        }
        val safeEnd = minOf(end, len - 1)
        val stream = FileInputStream(file).apply { skip(start) }
        val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", stream, safeEnd - start + 1)
        r.addHeader("Accept-Ranges", "bytes")
        r.addHeader("Content-Range", "bytes $start-$safeEnd/$len")
        dlna.forEach { (k, v) -> r.addHeader(k, v) }
        return r
    }
}
