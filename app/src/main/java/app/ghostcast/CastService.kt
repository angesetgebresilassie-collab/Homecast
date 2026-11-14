package app.ghostcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Does the whole job for one link or local video: resolve -> serve -> cast. Runs as a foreground
 * service with a Wi-Fi lock and wake lock so Android doesn't kill the servers mid-cast.
 */
class CastService : Service() {
    companion object {
        const val EXTRA_LINK = "link"
        const val EXTRA_URI = "uri"          // a video picked from the phone's storage
        const val EXTRA_NAME = "rName"
        const val EXTRA_CONTROL = "rControl"
        const val ACTION_STOP = "app.ghostcast.STOP"
        private const val CHANNEL = "cast"
        private const val PORT_720 = 8090
        private const val PORT_1080 = 8100   // also uses 8101 and 8102 on loopback
        private const val PORT_LOCAL = 8110
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var rangeServer: RangeProxyServer? = null
    private var tsServer: TsStreamServer? = null
    private var localServer: UriFileServer? = null
    private var control: String? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            startFg()
            job?.cancel()
            scope.launch { stopAll(); stopSelf() }
            return START_NOT_STICKY
        }
        val link = intent?.getStringExtra(EXTRA_LINK)
        val uriStr = intent?.getStringExtra(EXTRA_URI)
        val ctl = intent?.getStringExtra(EXTRA_CONTROL)
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "receiver"
        if ((link == null && uriStr == null) || ctl == null) { stopSelf(); return START_NOT_STICKY }

        startFg()
        job?.cancel()
        job = scope.launch {
            try {
                if (uriStr != null) castLocal(Uri.parse(uriStr), ctl, name)
                else cast(link!!, ctl, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CastState.status.value = "Error: ${e.message}"
                stopAll()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Video from internal storage: served as-is (H.264 or H.265), with seeking. */
    private suspend fun castLocal(uri: Uri, ctl: String, name: String) {
        closeServers()
        acquireLocks()
        CastState.casting.value = true

        val ip = localIp() ?: error("Not connected to Wi-Fi")
        CastState.status.value = "Reading video..."
        val info = LocalVideo.inspect(this, uri)
        val server = UriFileServer(this, uri, info.mime, PORT_LOCAL).also { it.start() }
        localServer = server

        CastState.status.value = "Casting to $name..."
        UpnpCaster.cast(ctl, server.source(ip, PORT_LOCAL, info), info.name)
        control = ctl
        val codec = when {
            info.isHevc -> "H.265"
            info.isH264 -> "H.264"
            else -> "video"
        }
        CastState.status.value = "Playing ${info.name} on $name ($codec, seeking on)"
    }

    private suspend fun cast(raw: String, ctl: String, name: String) {
        closeServers()
        acquireLocks()
        CastState.casting.value = true

        val link = Regex("https?://\\S+").find(raw)?.value ?: error("No link found in that text")
        val ip = localIp() ?: error("Not connected to Wi-Fi")
        val prefer1080 = Prefs(this).prefer1080

        CastState.status.value = "Finding stream..."
        val attempts = listOf<(String, String) -> CastSource?>(::start720, ::start1080)
            .let { if (prefer1080) it.reversed() else it }
        var source: CastSource? = null
        var lastError: Exception? = null
        for (attempt in attempts) {
            try {
                source = attempt(link, ip)
                if (source != null) break
            } catch (e: Exception) {
                lastError = e
            }
        }
        source ?: throw (lastError ?: IllegalStateException("No playable H.264 stream for this video"))

        CastState.status.value = "Casting to $name..."
        UpnpCaster.cast(ctl, source, "Homecast")
        control = ctl
        CastState.status.value = "Playing on $name (" +
            (if (source.seekable) "720p, seeking on)" else "1080p, no seeking)")
    }

    private fun start720(link: String, ip: String): CastSource? {
        val url = Resolver720.resolve(link) ?: return null
        val server = RangeProxyServer(url, PORT_720).also { it.start() }
        rangeServer = server
        return server.source(ip, PORT_720)
    }

    private fun start1080(link: String, ip: String): CastSource? {
        val (video, audio) = Resolver.resolve(link)
        val server = TsStreamServer(this, video.content, audio.content, PORT_1080).also { it.start() }
        tsServer = server
        return server.source(ip, PORT_1080)
    }

    private suspend fun stopAll() {
        control?.let { runCatching { UpnpCaster.stop(it) } }
        control = null
        closeServers()
        releaseLocks()
        CastState.casting.value = false
        CastState.status.value = "Stopped"
    }

    private fun closeServers() {
        runCatching { rangeServer?.stop() }
        runCatching { tsServer?.shutdown() }
        runCatching { localServer?.stop() }
        rangeServer = null; tsServer = null; localServer = null
    }

    private fun acquireLocks() {
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homecast:cast").apply { acquire(4 * 60 * 60 * 1000L) }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "homecast:wifi").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wake = null; wifiLock = null
    }

    /** The phone's Wi-Fi IPv4 address (prefers wlan interfaces). */
    private fun localIp(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Homecast")
            .setContentText("Casting to your receiver")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onDestroy() {
        closeServers()
        releaseLocks()
        CastState.casting.value = false
        scope.cancel()
        super.onDestroy()
    }
}
