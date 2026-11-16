package app.ghostcast

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Reads the receiver's playback state so a playlist can move on when a video ends. */
object UpnpStatus {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** [state] is e.g. PLAYING, STOPPED, NO_MEDIA_PRESENT; [position] and [duration] are in seconds (0 = unknown). */
    data class Playback(val state: String, val position: Long, val duration: Long)

    suspend fun read(ctx: Context, controlUrl: String): Playback? = withContext(Dispatchers.IO) {
        runCatching {
            val b = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
            UpnpCaster.wifiNetwork(ctx)?.let { b.socketFactory(it.socketFactory) }
            val http = b.build()

            val transport = call(http, controlUrl, "GetTransportInfo")
            val state = tag(transport, "CurrentTransportState") ?: return@runCatching null
            val pos = runCatching { call(http, controlUrl, "GetPositionInfo") }.getOrNull()
            Playback(
                state.uppercase(Locale.ROOT),
                seconds(pos?.let { tag(it, "RelTime") }),
                seconds(pos?.let { tag(it, "TrackDuration") })
            )
        }.getOrNull()
    }

    private fun call(http: OkHttpClient, controlUrl: String, action: String): String {
        val envelope = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:$action></s:Body></s:Envelope>"
        val req = Request.Builder().url(controlUrl)
            .header("SOAPACTION", "\"$AVT#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        return http.newCall(req).execute().use {
            check(it.isSuccessful) { "$action failed: HTTP ${it.code}" }
            it.body?.string().orEmpty()
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim()

    private fun seconds(t: String?): Long {
        val m = t?.let { Regex("(\\d+):(\\d+):(\\d+)").find(it) } ?: return 0L
        val (h, min, s) = m.destructured
        return h.toLong() * 3600 + min.toLong() * 60 + s.toLong()
    }
}
