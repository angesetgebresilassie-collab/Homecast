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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Plays a queue of direct video links (a single link is a queue of one) or one video from the
 * phone's storage. Runs as a foreground service with a Wi-Fi lock and wake lock so Android
 * doesn't kill the servers mid-cast.
 *
 * With a real receiver it watches the UPnP transport state and starts the next item when the
 * current one ends. When the chosen receiver is the virtual "VLC test" renderer, it skips UPnP and
 * publishes the stream URL in [CastState.testUrl]; the UI opens it in VLC (Next/Previous are manual there).
 */
class CastService : Service() {
    companion object {
        const val EXTRA_QUEUE = "queue"      // JSON array of PlaylistItem
        const val EXTRA_INDEX = "index"      // where in the queue to start
        const val EXTRA_URI = "uri"          // a video picked from the phone's storage
        const val EXTRA_NAME = "rName"
        const val EXTRA_CONTROL = "rControl"
        const val ACTION_STOP = "app.ghostcast.STOP"
        const val ACTION_NEXT = "app.ghostcast.NEXT"
        const val ACTION_PREV = "app.ghostcast.PREV"
        private const val CHANNEL = "cast"
        private const val PORT_DIRECT = 8090
        private const val PORT_TS = 8100     // also uses 8101 on loopback
        private const val PORT_LOCAL = 8110
    }

    private enum class Watch { ENDED, STOPPED, UNKNOWN }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var rangeServer: RangeProxyServer? = null
    private var tsServer: TsStreamServer? = null
    private var localServer: UriFileServer? = null
    private var target: String? = null        // receiver control URL
    private var targetName: String = "receiver"
    private var queue: List<PlaylistItem> = emptyList()
    private var index = 0
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            startFg()
            job?.cancel()
            scope.launch { stopAll(); stopSelf() }
            return START_NOT_STICKY
        }
        if (action == ACTION_NEXT || action == ACTION_PREV) {
            if (queue.isEmpty() || target == null) { stopSelf(); return START_NOT_STICKY }
            val to = index + if (action == ACTION_NEXT) 1 else -1
            if (to in queue.indices) play(to)
            return START_NOT_STICKY
        }

        val queueJson = intent?.getStringExtra(EXTRA_QUEUE)
        val uriStr = intent?.getStringExtra(EXTRA_URI)
        val ctl = intent?.getStringExtra(EXTRA_CONTROL)
        if ((queueJson == null && uriStr == null) || ctl == null) { stopSelf(); return START_NOT_STICKY }

        target = ctl
        targetName = intent.getStringExtra(EXTRA_NAME) ?: "receiver"
        queue = if (queueJson != null) PlaylistItem.listFromJson(queueJson) else emptyList()
        startFg()

        if (uriStr != null) {
            val prev = job
            job = scope.launch {
                prev?.cancelAndJoin()
                try {
                    castLocal(Uri.parse(uriStr), ctl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e)
                }
            }
        } else if (queue.isEmpty()) {
            stopSelf()
        } else {
            play((intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(queue.indices))
        }
        return START_NOT_STICKY
    }

    private fun isLocalTarget(ctl: String?) = ctl?.startsWith(LOCAL_PREFIX) == true

    /** Replaces whatever is playing and runs the queue from [from] on. */
    private fun play(from: Int) {
        val q = queue
        val prev = job
        job = scope.launch {
            prev?.cancelAndJoin()
            try {
                runQueue(q, from)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun runQueue(q: List<PlaylistItem>, from: Int) {
        var i = from
        var skipped = 0
        while (i in q.indices) {
            index = i
            val item = q[i]
            try {
                playItem(item, i, q.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (q.size == 1) throw e
                skipped++
                CastState.status.value = "Skipped ${item.displayTitle}: ${e.message}"
                i++
                continue
            }
            if (isLocalTarget(target)) return          // VLC test: Next/Previous are manual
            when (watch()) {
                Watch.ENDED -> i++
                Watch.STOPPED -> { endSession("Stopped on the receiver"); return }
                Watch.UNKNOWN -> return                // can't follow the receiver: leave the cast running
            }
        }
        endSession(
            when {
                q.size == 1 -> "Finished"
                skipped > 0 -> "Playlist finished ($skipped skipped)"
                else -> "Playlist finished"
            }
        )
    }

    /** Waits for the current item to end. ENDED = go on, STOPPED = the user stopped it on the receiver. */
    private suspend fun watch(): Watch {
        val c = target ?: return Watch.UNKNOWN
        var played = false
        var pos = 0L
        var dur = 0L
        var misses = 0
        var polls = 0
        while (true) {
            delay(3000)
            polls++
            val p = UpnpStatus.read(this, c)
            if (p == null) {
                if (++misses >= 10) return Watch.UNKNOWN
                continue
            }
            misses = 0
            when (p.state) {
                "PLAYING" -> {
                    played = true
                    pos = p.position
                    if (p.duration > 0) dur = p.duration
                }
                "STOPPED", "NO_MEDIA_PRESENT" -> if (played) {
                    // Stopped near the end (or the receiver reports no duration) = finished by itself.
                    return if (dur <= 0 || pos >= dur - 20) Watch.ENDED else Watch.STOPPED
                }
            }
            if (!played && polls >= 40) return Watch.UNKNOWN
        }
    }

    /** Real receiver: UPnP cast. VLC test: publish the URL for the UI to open. */
    private suspend fun deliver(ctl: String, source: CastSource, title: String) {
        if (isLocalTarget(ctl)) CastState.testUrl.value = source.url
        else UpnpCaster.cast(this, ctl, source, title)
    }

    /** One direct link: probe it, look up movie info from the file name, serve it and cast it. */
    private suspend fun playItem(item: PlaylistItem, i: Int, total: Int) {
        val ctl = target ?: error("No receiver chosen")
        val test = isLocalTarget(ctl)
        closeServers()
        acquireLocks()
        CastState.casting.value = true
        CastState.testUrl.value = null

        val ip = localIp() ?: if (test) "127.0.0.1" else error("Not connected to Wi-Fi")
        val prefs = Prefs(this)

        CastState.status.value = "Checking link..."
        if (DirectLink.isYouTube(item.url)) error("YouTube links are not supported. Use a direct video link.")
        val direct = DirectLink.probe(item.url) ?: error("That is not a direct video link")

        val shown = if (item.meta != null) item else item.copy(meta = MovieLookup.lookup(direct.name))
        CastState.nowPlaying.value = NowPlaying(shown.displayTitle, shown.subtitle, shown.meta?.poster, i + 1, total)

        CastState.status.value = "Opening ${shown.displayTitle}..."
        val source = startDirect(direct, ip, prefs.remuxDirect)
        val quality = if (source.seekable) "direct stream, seeking on" else "remuxed to TS, no seeking"

        CastState.status.value = "Casting to $targetName..."
        deliver(ctl, source, shown.displayTitle)
        CastState.status.value = if (test) "Test stream ready ($quality). Open it in VLC below."
        else "Playing on $targetName ($quality)"
    }

    /** Video from internal storage: served as-is (H.264 or H.265), with seeking. */
    private suspend fun castLocal(uri: Uri, ctl: String) {
        closeServers()
        acquireLocks()
        CastState.casting.value = true
        CastState.testUrl.value = null

        val test = isLocalTarget(ctl)
        val ip = localIp() ?: if (test) "127.0.0.1" else error("Not connected to Wi-Fi")
        CastState.status.value = "Reading video..."
        val info = LocalVideo.inspect(this, uri)
        val server = UriFileServer(this, uri, info.mime, PORT_LOCAL).also { it.start() }
        localServer = server

        CastState.status.value = "Casting to $targetName..."
        deliver(ctl, server.source(ip, PORT_LOCAL, info), info.name)
        val codec = when {
            info.isHevc -> "H.265"
            info.isH264 -> "H.264"
            else -> "video"
        }
        CastState.nowPlaying.value = NowPlaying(info.name, codec, null, 1, 1)
        CastState.status.value = if (test) "Test stream ready (${info.name}, $codec). Open it in VLC below."
        else "Playing ${info.name} on $targetName ($codec, seeking on)"
    }

    /** A plain video file on some web server: pass it through (seekable) or remux it to TS via FFmpeg. */
    private fun startDirect(info: DirectLink.Info, ip: String, remux: Boolean): CastSource =
        if (remux) {
            val server = TsStreamServer(this, info.url, null, PORT_TS).also { it.start() }
            tsServer = server
            server.source(ip, PORT_TS)
        } else {
            val server = RangeProxyServer(info.url, PORT_DIRECT, mime = info.mime).also { it.start() }
            rangeServer = server
            server.source(ip, PORT_DIRECT)
        }

    private suspend fun fail(e: Exception) {
        stopAll("Error: ${e.message}")
        stopSelf()
    }

    /** The queue is done (or the receiver stopped): release everything without sending Stop. */
    private fun endSession(message: String) {
        closeServers()
        releaseLocks()
        CastState.casting.value = false
        CastState.testUrl.value = null
        CastState.nowPlaying.value = null
        CastState.status.value = message
        stopSelf()
    }

    private suspend fun stopAll(message: String = "Stopped") {
        target?.takeIf { !isLocalTarget(it) }?.let { runCatching { UpnpCaster.stop(this, it) } }
        target = null
        queue = emptyList()
        closeServers()
        releaseLocks()
        CastState.casting.value = false
        CastState.testUrl.value = null
        CastState.nowPlaying.value = null
        CastState.status.value = message
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
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CastService::class.java).setAction(ACTION_STOP), flags
        )
        val next = PendingIntent.getService(
            this, 2, Intent(this, CastService::class.java).setAction(ACTION_NEXT), flags
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle("Homecast")
            .setContentText("Casting to your receiver")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
        if (queue.size > 1) builder.addAction(Notification.Action.Builder(null, "Next", next).build())
        ServiceCompat.startForeground(this, 1, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onDestroy() {
        closeServers()
        releaseLocks()
        CastState.casting.value = false
        CastState.testUrl.value = null
        CastState.nowPlaying.value = null
        scope.cancel()
        super.onDestroy()
    }
}
