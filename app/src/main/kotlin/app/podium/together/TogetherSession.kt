package app.podium.together

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import app.podium.AppGraph
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.NowPlayingItem
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackStatus
import app.podium.player.api.QueueEntry
import app.podium.sources.youtubemusic.YouTubeMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Listen Together — local-network synchronized listening for Podium, wire-compatible with both
 * Podium and Prism devices on the same Wi-Fi or personal hotspot.
 *
 * One device hosts a TCP server on [PORT] (fallback to an ephemeral port if taken) and advertises
 * `_prismlisten._tcp` via Android NSD (Bonjour/mDNS). Guests discover it automatically or enter
 * the IP address manually, then enter the 4-digit session code on the iPod combination tumbler.
 */
sealed interface TogetherState {
    data object Idle : TogetherState

    data class Hosting(
        val code: String,
        val address: String,
        val port: Int,
        val guests: List<GuestPeer> = emptyList(),
        val guestsControl: Boolean = false,
    ) : TogetherState {
        data class GuestPeer(val id: Int, val name: String)
    }

    data class Joining(val hostName: String) : TogetherState

    data class Guest(
        val hostName: String,
        val hostSong: Track? = null,
        val hostQueue: List<Track> = emptyList(),
        val hostPlaying: Boolean = false,
        val peerCount: Int = 1,
        val canControl: Boolean = false,
        /** True when this Podium plays the audio too; false when it acts as a remote queue controller. */
        val listenHere: Boolean = true,
        /** False when the guest manually paused or changed tracks locally; [TogetherSession.resync] restores sync. */
        val inSync: Boolean = true,
    ) : TogetherState
}

data class NearbySession(
    val serviceName: String,
    val displayName: String,
    val host: String,
    val port: Int,
)

class TogetherSession(
    private val context: Context,
    private val graph: AppGraph,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsd: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifi: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _state = MutableStateFlow<TogetherState>(TogetherState.Idle)
    val state: StateFlow<TogetherState> = _state.asStateFlow()

    private val _nearby = MutableStateFlow<List<NearbySession>>(emptyList())
    val nearby: StateFlow<List<NearbySession>> = _nearby.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    private fun postNotice(text: String) {
        _notice.value = text
    }

    private val myName: String =
        Build.MODEL?.takeIf { it.isNotBlank() }?.take(28) ?: "Podium"

    // ─── Host state ──────────────────────────────────────────────────────────
    private var serverSocket: ServerSocket? = null
    private var hostJob: Job? = null
    private var watchJob: Job? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private val peers = CopyOnWriteArrayList<HostPeer>()
    private val nextPeerId = AtomicInteger(1)
    private var hostCode: String = ""

    private class HostPeer(val id: Int, val name: String, val link: Link)

    // ─── Guest state ─────────────────────────────────────────────────────────
    private var guestJob: Job? = null
    private var guestWatchJob: Job? = null
    @Volatile
    private var guestLink: Link? = null
    private val clockSync = ClockSync()
    private var lastHostState: Msg.State? = null
    private var lastHostStateLocalMs: Long = 0L

    /** Guard timestamp: player transitions within this window were caused by TogetherSession, not the user. */
    @Volatile
    private var applyingUntilMs: Long = 0L

    // ─── Discovery state ─────────────────────────────────────────────────────
    private var discListener: NsdManager.DiscoveryListener? = null

    // ═════════════════════════════════════════════════════════════════════════
    // Discovery (runs while the Listen Together screen is open)
    // ═════════════════════════════════════════════════════════════════════════

    fun startDiscovery() {
        val mgr = nsd ?: return
        if (discListener != null) return
        _nearby.value = emptyList()
        acquireMulticast()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                discListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {
                discListener = null
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                val name = service.serviceName ?: return
                if (name == "$SERVICE_PREFIX$myName" && _state.value is TogetherState.Hosting) return
                resolve(mgr, service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val name = service.serviceName ?: return
                _nearby.value = _nearby.value.filterNot { it.serviceName == name }
            }
        }
        discListener = listener
        runCatching { mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discListener = null }
    }

    private fun resolve(mgr: NsdManager, service: NsdServiceInfo) {
        runCatching {
            @Suppress("DEPRECATION")
            mgr.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress ?: return
                        if (host.startsWith("127.")) return
                        val sName = info.serviceName ?: return
                        val display = sName
                            .removePrefix(SERVICE_PREFIX)
                            .removePrefix(PRISM_SERVICE_PREFIX)
                            .ifBlank { host }
                        val entry = NearbySession(
                            serviceName = sName,
                            displayName = display,
                            host = host,
                            port = info.port,
                        )
                        val cur = _nearby.value.filterNot {
                            it.serviceName == sName || (it.host == host && it.port == info.port)
                        }
                        _nearby.value = cur + entry
                    }
                },
            )
        }
    }

    fun stopDiscovery() {
        val mgr = nsd
        val l = discListener
        discListener = null
        if (mgr != null && l != null) {
            runCatching { mgr.stopServiceDiscovery(l) }
        }
        if (_state.value is TogetherState.Idle) releaseMulticast()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Host
    // ═════════════════════════════════════════════════════════════════════════

    fun startHosting() {
        stop()
        acquireMulticast()

        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        }.getOrElse {
            runCatching { ServerSocket(0) }.getOrElse {
                postNotice("Couldn't open a network port")
                return
            }
        }
        serverSocket = server
        val port = server.localPort
        val code = "%04d".format(SecureRandom().nextInt(10_000))
        hostCode = code
        val ip = localIpv4() ?: "—"

        _state.value = TogetherState.Hosting(
            code = code,
            address = if (port == PORT) ip else "$ip:$port",
            port = port,
        )

        advertise(port)
        watchJob = scope.launch { watchHostPlayer() }
        hostJob = scope.launch {
            while (isActive && !server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                launch { handleGuest(sock) }
            }
        }
    }

    private suspend fun handleGuest(sock: Socket) {
        val link = Link(sock)
        var peer: HostPeer? = null
        try {
            sock.soTimeout = 10_000
            val first = link.read()
            if (first !is Msg.Hello || first.v != Msg.VERSION) {
                link.send(Msg.Bye("Incompatible app version"))
                return
            }
            if (first.code.trim() != hostCode) {
                delay(700)
                link.send(Msg.Bye("Wrong code"))
                return
            }
            if (peers.size >= MAX_PEERS) {
                link.send(Msg.Bye("Session is full"))
                return
            }
            sock.soTimeout = 45_000
            val cleanName = first.name.trim().take(28).ifBlank { "Guest" }
            peer = HostPeer(nextPeerId.getAndIncrement(), cleanName, link)
            peers.add(peer)
            publishGuestList()

            link.send(Msg.Welcome(hostName = myName, state = snapshotHostState()))

            while (scope.isActive) {
                val msg = link.read() ?: break
                when (msg) {
                    is Msg.Ping -> link.send(Msg.Pong(msg.t0, SystemClock.elapsedRealtime()))
                    is Msg.Add -> onGuestAdd(peer, msg)
                    is Msg.Control -> onGuestControl(msg)
                    is Msg.Bye -> break
                    else -> {}
                }
            }
        } catch (_: Throwable) {
        } finally {
            peer?.let {
                peers.remove(it)
                publishGuestList()
                broadcastState()
            }
            link.close()
        }
    }

    private suspend fun onGuestAdd(peer: HostPeer, msg: Msg.Add) {
        val track = msg.song.fromPeer()?.toPodiumTrack() ?: return
        graph.catalog.remember(listOf(track))
        withContext(Dispatchers.Main) {
            val snap = graph.playbackController.snapshot.value
            when {
                snap.item == null -> graph.playbackController.playContext(
                    tracks = listOf(track.id),
                    startIndex = 0,
                    contextLabel = "Listen Together",
                )
                msg.next -> graph.playbackController.playNext(listOf(track.id))
                else -> graph.playbackController.addToQueue(listOf(track.id))
            }
            postNotice("${peer.name} added ${track.title}")
        }
        delay(120)
        broadcastState()
    }

    private suspend fun onGuestControl(msg: Msg.Control) {
        val s = _state.value as? TogetherState.Hosting ?: return
        if (!s.guestsControl) return
        withContext(Dispatchers.Main) {
            val c = graph.playbackController
            when (msg.action) {
                "play" -> c.play()
                "pause" -> c.pause()
                "next" -> c.next()
                "previous" -> c.previous()
                "seek" -> msg.positionMs?.let { c.seekTo(it.coerceAtLeast(0L)) }
            }
        }
        delay(80)
        broadcastState()
    }

    fun setGuestsControl(allow: Boolean) {
        val s = _state.value as? TogetherState.Hosting ?: return
        _state.value = s.copy(guestsControl = allow)
        scope.launch { broadcastState() }
    }

    private fun publishGuestList() {
        val s = _state.value as? TogetherState.Hosting ?: return
        _state.value = s.copy(
            guests = peers.map { TogetherState.Hosting.GuestPeer(it.id, it.name) },
        )
    }

    private suspend fun watchHostPlayer() {
        var lastTrackId: TrackId? = null
        var lastPlaying = false
        var lastPos = 0L
        var lastQueueIds: List<TrackId> = emptyList()
        var ticksSinceSend = 0

        while (scope.isActive && _state.value is TogetherState.Hosting) {
            val snap = graph.playbackController.snapshot.value
            val q = graph.playbackController.queue.value
            val pos = withContext(Dispatchers.Main) { graph.playbackController.positionMs() }

            val curId = snap.item?.trackId
            val playing = snap.status is PlaybackStatus.Playing || snap.intent == PlayIntent.PLAY
            val qIds = q.upNext.take(40).map { it.trackId }

            val expectedPos = lastPos + if (lastPlaying) 400L else 0L
            val seeked = curId == lastTrackId && abs(pos - expectedPos) > 1_200L
            val changed = curId != lastTrackId || playing != lastPlaying || seeked || qIds != lastQueueIds

            lastTrackId = curId
            lastPlaying = playing
            lastPos = pos
            lastQueueIds = qIds
            ticksSinceSend++

            if (peers.isNotEmpty() && (changed || ticksSinceSend >= 8)) {
                ticksSinceSend = 0
                broadcastState()
            }
            delay(400)
        }
    }

    private suspend fun snapshotHostState(): Msg.State {
        val snap = graph.playbackController.snapshot.value
        val q = graph.playbackController.queue.value
        val pos = withContext(Dispatchers.Main) { graph.playbackController.positionMs() }
        val s = _state.value as? TogetherState.Hosting

        val currentWire = snap.item?.let { itemToWireSong(it) }
        val upNextWire = q.upNext.take(40).mapNotNull { entryToWireSong(it) }
        val isPlaying = snap.status is PlaybackStatus.Playing ||
            (snap.intent == PlayIntent.PLAY && snap.status !is PlaybackStatus.Paused)

        return Msg.State(
            songs = buildList {
                if (currentWire != null) add(currentWire)
                addAll(upNextWire)
            },
            playing = isPlaying,
            positionMs = pos.coerceAtLeast(0L),
            sentAt = SystemClock.elapsedRealtime(),
            guests = peers.map { it.name },
            canControl = s?.guestsControl == true,
        )
    }

    private fun resolveVideoId(trackId: TrackId): String? {
        if (trackId.sourceId == YouTubeMusicSource.SOURCE_ID) return trackId.providerKey
        val track = graph.catalog.cached(trackId) ?: return null
        return graph.equivalence.exactEquivalents(track)
            .firstOrNull { it.source.sourceId == YouTubeMusicSource.SOURCE_ID }
            ?.source?.providerKey
    }

    private fun itemToWireSong(item: NowPlayingItem): WireSong? {
        val videoId = resolveVideoId(item.trackId) ?: return null
        val cached = graph.catalog.cached(item.trackId)
        if (cached != null) return cached.toWireSong(overrideVideoId = videoId)
        return WireSong(
            id = videoId,
            title = item.title,
            artists = listOf(WireArtistRef(item.artistDisplay)),
            album = item.albumTitle?.let { WireAlbumRef(it) },
            durationSec = ((item.durationMs ?: 0L) / 1000L).toInt().coerceAtLeast(0),
            thumbnail = item.artworkUri?.takeIf { it.startsWith("https://") },
        )
    }

    private fun entryToWireSong(entry: QueueEntry): WireSong? {
        val videoId = resolveVideoId(entry.trackId) ?: return null
        val cached = graph.catalog.cached(entry.trackId)
        if (cached != null) return cached.toWireSong(overrideVideoId = videoId)
        return WireSong(
            id = videoId,
            title = entry.title,
            artists = listOf(WireArtistRef(entry.artistDisplay)),
            durationSec = ((entry.durationMs ?: 0L) / 1000L).toInt().coerceAtLeast(0),
            thumbnail = entry.artworkUri?.takeIf { it.startsWith("https://") },
        )
    }

    private suspend fun broadcastState() {
        if (peers.isEmpty()) return
        val st = snapshotHostState()
        for (p in peers) {
            if (!p.link.send(st)) {
                peers.remove(p)
                p.link.close()
            }
        }
    }

    private fun advertise(port: Int) {
        val mgr = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "$SERVICE_PREFIX$myName"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
        }
        regListener = l
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { regListener = null }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Guest
    // ═════════════════════════════════════════════════════════════════════════

    fun join(host: String, port: Int = PORT, code: String, displayName: String = host) {
        val cleanHost = host.trim()
        val cleanCode = code.trim()
        if (cleanHost.isEmpty() || cleanCode.length != 4) {
            postNotice("Enter the 4-digit session code")
            return
        }
        stop()
        _state.value = TogetherState.Joining(displayName)

        guestJob = scope.launch {
            var retries = 0
            while (isActive) {
                val res = runGuestOnce(cleanHost, port, cleanCode, displayName, isReconnect = retries > 0)
                when (res) {
                    GuestEnd.CLEAN, GuestEnd.REJECTED -> break
                    GuestEnd.DROPPED -> {
                        val g = _state.value as? TogetherState.Guest ?: break
                        retries++
                        if (retries > 3) {
                            postNotice("Disconnected from ${g.hostName}")
                            break
                        }
                        delay(1_200L * retries)
                    }
                }
            }
            if (_state.value !is TogetherState.Hosting) {
                _state.value = TogetherState.Idle
            }
        }
    }

    private enum class GuestEnd { CLEAN, REJECTED, DROPPED }

    private suspend fun runGuestOnce(
        host: String,
        port: Int,
        code: String,
        displayName: String,
        isReconnect: Boolean,
    ): GuestEnd {
        val sock = Socket()
        try {
            runCatching {
                sock.connect(InetSocketAddress(host, port), 5_000)
                sock.soTimeout = 20_000
                sock.tcpNoDelay = true
            }.onFailure {
                if (!isReconnect) postNotice("Couldn't reach $displayName")
                return if (isReconnect) GuestEnd.DROPPED else GuestEnd.REJECTED
            }

            val link = Link(sock)
            guestLink = link
            if (!isReconnect) clockSync.reset()

            link.send(Msg.Hello(name = myName, code = code))
            when (val reply = link.read()) {
                is Msg.Welcome -> {
                    val prev = _state.value as? TogetherState.Guest
                    _state.value = TogetherState.Guest(
                        hostName = reply.hostName,
                        listenHere = prev?.listenHere ?: true,
                        inSync = prev?.inSync ?: true,
                    )
                    // Burst 3 pings so the first seek uses a tight clock offset
                    repeat(3) { link.send(Msg.Ping(SystemClock.elapsedRealtime())) }
                    val pingLoop = scope.launch {
                        while (isActive) {
                            delay(5_000)
                            if (!link.send(Msg.Ping(SystemClock.elapsedRealtime()))) break
                        }
                    }
                    guestWatchJob?.cancel()
                    guestWatchJob = scope.launch { watchGuestPlayer() }

                    reply.state?.let { applyHostState(it, forceSeek = true) }

                    try {
                        while (scope.isActive) {
                            val msg = link.read() ?: return GuestEnd.DROPPED
                            when (msg) {
                                is Msg.Pong -> clockSync.onPong(msg.t0, msg.tHost, SystemClock.elapsedRealtime())
                                is Msg.State -> applyHostState(msg, forceSeek = false)
                                is Msg.Bye -> {
                                    postNotice(msg.reason)
                                    return GuestEnd.CLEAN
                                }
                                else -> {}
                            }
                        }
                        return GuestEnd.CLEAN
                    } finally {
                        pingLoop.cancel()
                    }
                }
                is Msg.Bye -> {
                    postNotice(reply.reason)
                    return GuestEnd.REJECTED
                }
                else -> {
                    if (!isReconnect) postNotice("Connection closed")
                    return if (isReconnect) GuestEnd.DROPPED else GuestEnd.REJECTED
                }
            }
        } finally {
            guestLink = null
            runCatching { sock.close() }
        }
    }

    private suspend fun applyHostState(st: Msg.State, forceSeek: Boolean) {
        lastHostState = st
        lastHostStateLocalMs = SystemClock.elapsedRealtime()

        val g = _state.value as? TogetherState.Guest ?: return
        val hostTrack = st.song?.fromPeer()?.toPodiumTrack()
        val hostQ = st.queue.mapNotNull { it.fromPeer()?.toPodiumTrack() }

        val allTracks = buildList {
            if (hostTrack != null) add(hostTrack)
            addAll(hostQ)
        }
        if (allTracks.isNotEmpty()) {
            graph.catalog.remember(allTracks)
        }

        _state.value = g.copy(
            hostSong = hostTrack,
            hostQueue = hostQ,
            hostPlaying = st.playing,
            peerCount = st.peers.size + 1,
            canControl = st.guestsControl,
        )

        if (!g.listenHere || !g.inSync || hostTrack == null) return

        withContext(Dispatchers.Main) {
            applyingUntilMs = SystemClock.elapsedRealtime() + 2_200L
            val c = graph.playbackController
            val snap = c.snapshot.value
            val curId = snap.item?.trackId
            val trackChanged = curId != hostTrack.id

            if (trackChanged) {
                val contextIds = buildList {
                    add(hostTrack.id)
                    addAll(hostQ.map { it.id })
                }
                c.playContext(
                    tracks = contextIds,
                    startIndex = 0,
                    contextLabel = "${g.hostName}'s Session",
                )
            } else {
                val curUpNext = c.queue.value.upNext.map { it.trackId }
                val targetUpNext = hostQ.map { it.id }
                if (curUpNext != targetUpNext) {
                    c.clearUpcoming()
                    if (targetUpNext.isNotEmpty()) {
                        c.addToQueue(targetUpNext)
                    }
                }
            }

            // Target position now: host's position + time elapsed since the host stamped it
            val now = SystemClock.elapsedRealtime()
            val targetPos = if (st.playing) {
                val age = if (clockSync.ready) {
                    (clockSync.hostNow(now) - st.atMs).coerceIn(0L, 10_000L)
                } else {
                    (now - lastHostStateLocalMs).coerceIn(0L, 2_000L)
                }
                st.positionMs + age
            } else {
                st.positionMs
            }.coerceAtLeast(0L)

            if (trackChanged) {
                // Wait briefly for the player to load the new context before seeking/pausing
                scope.launch(Dispatchers.Main) {
                    var waited = 0
                    while (waited < 40) {
                        val s = c.snapshot.value.status
                        if (s is PlaybackStatus.Playing || s is PlaybackStatus.Paused) break
                        delay(150)
                        waited++
                    }
                    applyingUntilMs = SystemClock.elapsedRealtime() + 1_800L
                    val freshNow = SystemClock.elapsedRealtime()
                    val freshTarget = if (st.playing) {
                        targetPos + (freshNow - now).coerceAtLeast(0L)
                    } else {
                        st.positionMs
                    }
                    if (freshTarget > 800L) c.seekTo(freshTarget)
                    if (!st.playing) c.pause()
                }
            } else {
                val isPlaying = snap.status is PlaybackStatus.Playing || snap.intent == PlayIntent.PLAY
                if (st.playing != isPlaying) {
                    if (st.playing) c.play() else c.pause()
                }
                val drift = abs(c.positionMs() - targetPos)
                val tolerance = if (forceSeek) 250L else DRIFT_TOLERANCE_MS
                val statusReady = snap.status is PlaybackStatus.Playing || snap.status is PlaybackStatus.Paused
                if (statusReady && drift > tolerance) {
                    c.seekTo(targetPos)
                }
            }
        }
    }

    /**
     * Watches the local player while acting as a Guest. If the user manually pauses or picks a
     * different song locally (outside our [applyingUntilMs] window), we drop out of auto-sync
     * gently and offer a 1-click "Resync with host" action.
     */
    private suspend fun watchGuestPlayer() {
        combine(
            graph.playbackController.snapshot,
            _state,
        ) { snap, st -> snap to st }.collect { (snap, st) ->
            val g = st as? TogetherState.Guest ?: return@collect
            if (!g.listenHere || !g.inSync) return@collect
            if (SystemClock.elapsedRealtime() < applyingUntilMs) return@collect
            val hostSong = g.hostSong ?: return@collect

            val curId = snap.item?.trackId
            val userChangedSong = curId != null && curId != hostSong.id
            val userPaused = g.hostPlaying && snap.status is PlaybackStatus.Paused && snap.intent == PlayIntent.PAUSE
            if (userChangedSong || userPaused) {
                _state.value = g.copy(inSync = false)
            }
        }
    }

    fun setListenHere(enabled: Boolean) {
        val g = _state.value as? TogetherState.Guest ?: return
        _state.value = g.copy(listenHere = enabled, inSync = if (enabled) true else g.inSync)
        if (enabled) {
            lastHostState?.let { scope.launch { applyHostState(it, forceSeek = true) } }
        } else {
            scope.launch(Dispatchers.Main) {
                applyingUntilMs = SystemClock.elapsedRealtime() + 1_000L
                graph.playbackController.pause()
            }
        }
    }

    fun resync() {
        val g = _state.value as? TogetherState.Guest ?: return
        _state.value = g.copy(inSync = true, listenHere = true)
        lastHostState?.let { scope.launch { applyHostState(it, forceSeek = true) } }
    }

    /** Send a track to the host's queue (`next = true` → Play Next, `false` → Add to Queue). */
    fun suggest(track: Track, next: Boolean = false): Boolean {
        val link = guestLink ?: return false
        val videoId = resolveVideoId(track.id)
        val wire = track.toWireSong(overrideVideoId = videoId)
        if (wire == null) {
            postNotice("Only YouTube Music songs can be shared in Listen Together")
            return false
        }
        scope.launch {
            if (link.send(Msg.Add(wire, next))) {
                postNotice(if (next) "Playing next on host" else "Added to session queue")
            } else {
                postNotice("Couldn't reach host")
            }
        }
        return true
    }

    /** Remote transport control when the host has enabled [TogetherState.Hosting.guestsControl]. */
    fun control(action: String, positionMs: Long? = null) {
        val link = guestLink ?: return
        scope.launch { link.send(Msg.Control(action, positionMs ?: 0L)) }
    }

    val isGuest: Boolean get() = _state.value is TogetherState.Guest
    val isActive: Boolean get() = _state.value !is TogetherState.Idle

    // ═════════════════════════════════════════════════════════════════════════
    // Teardown & network helpers
    // ═════════════════════════════════════════════════════════════════════════

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        hostJob?.cancel()
        hostJob = null
        guestWatchJob?.cancel()
        guestWatchJob = null
        guestJob?.cancel()
        guestJob = null

        regListener?.let { l -> runCatching { nsd?.unregisterService(l) } }
        regListener = null

        runCatching { serverSocket?.close() }
        serverSocket = null

        val bye = Msg.Bye("Session ended")
        val peersCopy = peers.toList()
        peers.clear()
        val gLink = guestLink
        guestLink = null

        scope.launch {
            peersCopy.forEach {
                it.link.send(bye)
                it.link.close()
            }
            gLink?.let {
                it.send(Msg.Bye("Left session"))
                it.close()
            }
        }

        _state.value = TogetherState.Idle
        if (discListener == null) releaseMulticast()
    }

    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        multicastLock = runCatching {
            wifi?.createMulticastLock("podium.together")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseMulticast() {
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    private fun localIpv4(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val addresses = interfaces
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { iface.name.lowercase() to (it.hostAddress ?: "") }
            }
        // Prefer Wi-Fi / hotspot interfaces (wlan*, ap*, swlan*) over cellular/VPN
        addresses.firstOrNull { (name, _) ->
            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
        }?.second ?: addresses.firstOrNull()?.second
    }.getOrNull()

    companion object {
        const val PORT = 47_312
        /** Shared service type so Podium and Prism sessions discover each other seamlessly. */
        private const val SERVICE_TYPE = "_prismlisten._tcp."
        private const val SERVICE_PREFIX = "Podium · "
        private const val PRISM_SERVICE_PREFIX = "Prism · "
        private const val MAX_PEERS = 8
        private const val DRIFT_TOLERANCE_MS = 650L
    }
}
