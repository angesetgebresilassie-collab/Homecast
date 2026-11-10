package app.ghostcast

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Minimal UPnP/DLNA controller: SSDP discovery + AVTransport SOAP calls. No external library. */
object UpnpCaster {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun discover(ctx: Context, timeoutMs: Int = 4000): List<Renderer> = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("homecast").apply { setReferenceCounted(false); acquire() }
        val locations = linkedSetOf<String>()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 800
                val group = InetAddress.getByName("239.255.255.250")
                for (st in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", AVT, "ssdp:all")) {
                    repeat(2) {
                        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n").toByteArray()
                        sock.send(DatagramPacket(msg, msg.size, group, 1900))
                    }
                }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val text = String(p.data, 0, p.length, Charsets.UTF_8)
                        Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { locations += it }
                    } catch (_: SocketTimeoutException) { /* keep listening until timeout */ }
                }
            }
        } finally {
            lock.release()
        }
        locations.mapNotNull { runCatching { describe(it) }.getOrNull() }.distinctBy { it.controlUrl }
    }

    private fun describe(location: String): Renderer? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() } ?: return null
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val name = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.trim() ?: "Receiver"
        val base = doc.getElementsByTagName("URLBase").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val el = services.item(i) as? Element ?: continue
            val type = el.getElementsByTagName("serviceType").item(0)?.textContent ?: continue
            if (type.contains("AVTransport")) {
                val ctl = el.getElementsByTagName("controlURL").item(0)?.textContent?.trim() ?: continue
                return Renderer(name, URL(URL(base), ctl).toString())
            }
        }
        return null
    }

    suspend fun cast(controlUrl: String, source: CastSource, title: String) = withContext(Dispatchers.IO) {
        runCatching { stop(controlUrl) }          // clear anything already playing
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"${esc(source.protocolInfo)}\">${esc(source.url)}</res></item></DIDL-Lite>"
        soap(controlUrl, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${esc(source.url)}</CurrentURI>" +
                "<CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
        delay(500)
        soap(controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    suspend fun stop(controlUrl: String) = withContext(Dispatchers.IO) {
        soap(controlUrl, "Stop", "<InstanceID>0</InstanceID>")
    }

    private fun soap(controlUrl: String, action: String, args: String) {
        val envelope = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$AVT\">$args</u:$action></s:Body></s:Envelope>"
        val req = Request.Builder().url(controlUrl)
            .header("SOAPACTION", "\"$AVT#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(req).execute().use {
            check(it.isSuccessful) { "$action failed: HTTP ${it.code} ${it.body?.string()?.take(160)}" }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
