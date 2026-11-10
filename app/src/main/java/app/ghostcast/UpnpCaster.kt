package app.ghostcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** What a search found. [seen] = devices that answered but can't play video; [responses] = raw SSDP replies. */
data class Discovery(val renderers: List<Renderer>, val seen: List<String>, val responses: Int)

/**
 * Minimal UPnP/DLNA controller: SSDP discovery + AVTransport SOAP calls. No external library.
 * All LAN traffic is bound to the Wi-Fi network, so it still works when Android prefers
 * mobile data (e.g. the receiver's Wi-Fi has no internet).
 */
object UpnpCaster {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        AVT,
        "upnp:rootdevice",
        "ssdp:all"
    )

    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun client(net: Network?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
        if (net != null) b.socketFactory(net.socketFactory)
        return b.build()
    }

    suspend fun discover(ctx: Context, extraIp: String? = null, timeoutMs: Int = 6000): Discovery =
        withContext(Dispatchers.IO) {
            val net = wifiNetwork(ctx)
            val http = client(net)
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("homecast").apply { setReferenceCounted(false); acquire() }
            val locations = linkedSetOf<String>()
            var responses = 0
            try {
                MulticastSocket(null as InetSocketAddress?).use { sock ->
                    sock.reuseAddress = true
                    runCatching { net?.bindSocket(sock) }
                    sock.bind(InetSocketAddress(0))
                    sock.timeToLive = 4
                    sock.soTimeout = 500

                    val targets = mutableListOf(InetAddress.getByName("239.255.255.250"))
                    extraIp?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        runCatching { targets += InetAddress.getByName(it) }
                    }

                    fun burst() {
                        for (st in SEARCH_TARGETS) for (t in targets) {
                            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                                "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n").toByteArray()
                            runCatching { sock.send(DatagramPacket(msg, msg.size, t, 1900)) }
                        }
                    }

                    val start = System.currentTimeMillis()
                    var nextBurst = start
                    val buf = ByteArray(4096)
                    while (System.currentTimeMillis() < start + timeoutMs) {
                        if (System.currentTimeMillis() >= nextBurst) { burst(); nextBurst += 2000 }
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            sock.receive(p)
                            responses++
                            val text = String(p.data, 0, p.length, Charsets.UTF_8)
                            Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { locations += it }
                        } catch (_: SocketTimeoutException) { /* keep listening */ }
                    }
                }
            } finally {
                lock.release()
            }

            val renderers = mutableListOf<Renderer>()
            val seen = mutableListOf<String>()
            for (loc in locations) {
                val d = runCatching { describe(http, loc) }.getOrNull()
                when {
                    d == null -> seen += "unreachable device at ${runCatching { URL(loc).host }.getOrDefault(loc)}"
                    d.second != null -> renderers += Renderer(d.first, d.second!!)
                    else -> seen += d.first
                }
            }
            Discovery(renderers.distinctBy { it.controlUrl }, seen.distinct(), responses)
        }

    /** Returns (friendlyName, AVTransport controlURL or null). */
    private fun describe(http: OkHttpClient, location: String): Pair<String, String?>? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() } ?: return null
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val name = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.trim() ?: "Device"
        val base = doc.getElementsByTagName("URLBase").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val el = services.item(i) as? Element ?: continue
            val type = el.getElementsByTagName("serviceType").item(0)?.textContent ?: continue
            if (type.contains("AVTransport")) {
                val ctl = el.getElementsByTagName("controlURL").item(0)?.textContent?.trim() ?: continue
                return name to URL(URL(base), ctl).toString()
            }
        }
        return name to null
    }

    suspend fun cast(ctx: Context, controlUrl: String, source: CastSource, title: String) =
        withContext(Dispatchers.IO) {
            val http = client(wifiNetwork(ctx))
            runCatching { soap(http, controlUrl, "Stop", "<InstanceID>0</InstanceID>") }   // clear anything playing
            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"${esc(source.protocolInfo)}\">${esc(source.url)}</res></item></DIDL-Lite>"
            soap(http, controlUrl, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${esc(source.url)}</CurrentURI>" +
                    "<CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
            delay(500)
            soap(http, controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        }

    suspend fun stop(ctx: Context, controlUrl: String) = withContext(Dispatchers.IO) {
        soap(client(wifiNetwork(ctx)), controlUrl, "Stop", "<InstanceID>0</InstanceID>")
    }

    private fun soap(http: OkHttpClient, controlUrl: String, action: String, args: String) {
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
