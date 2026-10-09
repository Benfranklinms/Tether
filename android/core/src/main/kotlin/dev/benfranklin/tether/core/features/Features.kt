package dev.benfranklin.tether.core.features

import dev.benfranklin.tether.core.proto.Battery
import dev.benfranklin.tether.core.proto.VideoFrame
import kotlinx.serialization.Serializable

/**
 * Decides which battery snapshots go to the Mac. ACTION_BATTERY_CHANGED fires for every
 * voltage and temperature wiggle, so this keeps traffic to what the Mac shows:
 * - the first snapshot after a (re)connect, and charging/plug changes, go at once;
 * - crossing the low-battery thresholds goes at once (the Mac alerts on them);
 * - other level or network changes go at most every [minIntervalMs];
 * - an unchanged state is repeated every [heartbeatMs] so a stale value can't linger.
 * Single-threaded.
 */
class BatteryThrottle(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val heartbeatMs: Long = HEARTBEAT_MS,
) {
    private var last: Battery? = null
    private var lastAt = 0L

    /** Forget what was sent (the link reconnected): the next snapshot is sent. */
    fun reset() {
        last = null
    }

    /** Returns true when [b] should be sent now (and records it as sent). */
    fun offer(b: Battery, now: Long): Boolean {
        val prev = last
        val send = when {
            prev == null -> true
            prev.charging != b.charging || prev.plug != b.plug -> true
            THRESHOLDS.any { t -> (prev.level > t) != (b.level > t) } -> true
            now - lastAt >= heartbeatMs -> true
            now - lastAt < minIntervalMs -> false
            else -> prev.level != b.level || prev.net != b.net || prev.gen != b.gen || prev.signal != b.signal
        }
        if (send) {
            last = b
            lastAt = now
        }
        return send
    }

    companion object {
        /** Levels whose crossing is sent immediately (the Mac's low-battery alert is at 20). */
        val THRESHOLDS = listOf(20, 10, 5)
        const val MIN_INTERVAL_MS = 60_000L
        const val HEARTBEAT_MS = 600_000L
    }
}

/** Durable state of [MediaLedger] (persisted as JSON by the app). */
@Serializable
data class MediaLedgerState(
    /** MediaStore DATE_ADDED (seconds) of the newest item already considered. */
    val watermark: Long = -1,
    /** MediaStore _ID of that item (ties on the same second). */
    val lastId: Long = -1,
    /** SHA-256 (hex) of media already sent, newest last, capped. */
    val sent: List<String> = emptyList(),
)

/**
 * Remembers which screenshots/photos were already handled so the Mac gets each one once:
 * items older than the watermark are skipped (enabling the feature never uploads the whole
 * gallery), and content already sent (by hash) is skipped even when it reappears under a new
 * MediaStore id (edits that don't change bytes, re-scans, duplicates). Single-threaded.
 */
class MediaLedger(state: MediaLedgerState = MediaLedgerState(), private val maxHashes: Int = 2000) {
    enum class Decision { SEND, SKIP_OLD, SKIP_DUPLICATE }

    private var watermark = state.watermark
    private var lastId = state.lastId
    private val sent = LinkedHashSet(state.sent.map { it.lowercase() })

    val state: MediaLedgerState get() = MediaLedgerState(watermark, lastId, sent.toList())

    val started: Boolean get() = watermark >= 0

    /** Start watching from now: anything added at or before [nowSeconds] counts as old. */
    fun startAt(nowSeconds: Long) {
        if (watermark < nowSeconds) {
            watermark = nowSeconds
            lastId = Long.MAX_VALUE
        }
    }

    /** Is the MediaStore item ([id], [dateAdded] seconds) newer than what we've seen? */
    fun isNew(id: Long, dateAdded: Long): Boolean =
        dateAdded > watermark || (dateAdded == watermark && id > lastId && lastId != Long.MAX_VALUE)

    /** Mark an item as considered (sent or skipped) so a re-scan doesn't revisit it. */
    fun advance(id: Long, dateAdded: Long) {
        if (dateAdded > watermark || (dateAdded == watermark && (id > lastId || lastId == Long.MAX_VALUE))) {
            watermark = dateAdded
            lastId = id
        }
    }

    fun alreadySent(sha256: String): Boolean = sha256.lowercase() in sent

    fun markSent(sha256: String) {
        val h = sha256.lowercase()
        sent.remove(h)
        sent.add(h)
        while (sent.size > maxHashes) sent.remove(sent.first())
    }

    /** Decide for one item once its hash is known; advances the watermark either way. */
    fun decide(id: Long, dateAdded: Long, sha256: String): Decision {
        val decision = when {
            !isNew(id, dateAdded) -> Decision.SKIP_OLD
            alreadySent(sha256) -> Decision.SKIP_DUPLICATE
            else -> Decision.SEND
        }
        if (decision != Decision.SKIP_OLD) advance(id, dateAdded)
        if (decision == Decision.SEND) markSent(sha256)
        return decision
    }
}

/**
 * Maps a point the Mac sends (normalised 0..1 in the video frame, which was captured with
 * the display at rotation [frameRotation]) to a pixel on the phone's display as it is now
 * (rotation [displayRotation], size [displayWidth] x [displayHeight] in that orientation).
 * Rotations are Surface.ROTATION_* in degrees (0, 90, 180, 270). When the phone rotated after
 * the frame was captured, the point is carried through the natural orientation so it still
 * lands on the same physical spot.
 */
object GestureMapper {
    data class Point(val x: Float, val y: Float)

    fun toDisplay(
        nx: Double,
        ny: Double,
        frameRotation: Int,
        displayRotation: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): Point {
        val (u, v) = toNatural(nx.coerceIn(0.0, 1.0), ny.coerceIn(0.0, 1.0), norm(frameRotation))
        val (x, y) = fromNatural(u, v, norm(displayRotation))
        val px = (x * displayWidth).coerceIn(0.0, (displayWidth - 1).toDouble().coerceAtLeast(0.0))
        val py = (y * displayHeight).coerceIn(0.0, (displayHeight - 1).toDouble().coerceAtLeast(0.0))
        return Point(px.toFloat(), py.toFloat())
    }

    private fun norm(r: Int): Int = ((r % FULL) + FULL) % FULL

    /** Natural-orientation coordinates -> coordinates on a display rotated by [r]. */
    internal fun fromNatural(u: Double, v: Double, r: Int): Pair<Double, Double> = when (r) {
        R90 -> v to 1 - u
        R180 -> 1 - u to 1 - v
        R270 -> 1 - v to u
        else -> u to v
    }

    /** Inverse of [fromNatural]. */
    internal fun toNatural(x: Double, y: Double, r: Int): Pair<Double, Double> = when (r) {
        R90 -> 1 - y to x
        R180 -> 1 - x to 1 - y
        R270 -> y to 1 - x
        else -> x to y
    }

    private const val R90 = 90
    private const val R180 = 180
    private const val R270 = 270
    private const val FULL = 360
}

/** H.264 Annex B helpers: NAL unit boundaries and types. */
object AnnexB {
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** (offset, length) of each NAL unit payload, start codes excluded. */
    fun nalUnits(data: ByteArray): List<IntRange> {
        val starts = ArrayList<Pair<Int, Int>>() // (start-code position, payload start)
        var i = 0
        while (i + 2 < data.size) {
            val len = startCodeAt(data, i)
            if (len > 0) starts += i to i + len
            i += if (len > 0) len else 1
        }
        return starts.mapIndexed { k, (_, payload) ->
            val end = if (k + 1 < starts.size) starts[k + 1].first else data.size
            payload until end
        }.filter { !it.isEmpty() }
    }

    /** Length of the start code (3 or 4) at [i], or 0. */
    private fun startCodeAt(d: ByteArray, i: Int): Int = when {
        d[i].toInt() != 0 || d[i + 1].toInt() != 0 -> 0
        d[i + 2].toInt() == 1 -> SHORT_START
        i + SHORT_START < d.size && d[i + 2].toInt() == 0 && d[i + SHORT_START].toInt() == 1 -> LONG_START
        else -> 0
    }

    fun nalTypes(data: ByteArray): List<Int> = nalUnits(data).map { data[it.first].toInt() and NAL_TYPE_MASK }

    fun isKeyframe(data: ByteArray): Boolean = NAL_IDR in nalTypes(data)

    fun hasConfig(data: ByteArray): Boolean = nalTypes(data).let { NAL_SPS in it && NAL_PPS in it }

    /** Annex B -> AVCC (each NAL prefixed by its 4-byte big-endian length). */
    fun toAvcc(data: ByteArray): ByteArray {
        val units = nalUnits(data)
        val out = ByteArray(units.sumOf { it.count() + LEN })
        var o = 0
        for (u in units) {
            val n = u.count()
            for (b in 0 until LEN) out[o + b] = (n ushr (BYTE * (LEN - 1 - b))).toByte()
            System.arraycopy(data, u.first, out, o + LEN, n)
            o += n + LEN
        }
        return out
    }

    private const val NAL_TYPE_MASK = 0x1F
    private const val LEN = 4
    private const val BYTE = 8
    private const val SHORT_START = 3
    private const val LONG_START = 4
}

/** Splits one encoded access unit into [VideoFrame] messages of at most [maxPart] bytes. */
object FrameSplitter {
    fun split(
        src: String,
        seq: Long,
        pts: Long,
        key: Boolean,
        config: Boolean,
        data: ByteArray,
        maxPart: Int = VideoFrame.MAX_PART,
    ): List<VideoFrame> {
        require(maxPart > 0)
        val parts = maxOf(1, (data.size + maxPart - 1) / maxPart)
        return (0 until parts).map { p ->
            val from = p * maxPart
            val to = minOf(data.size, from + maxPart)
            VideoFrame(src, seq, pts, key, config, p, parts, data.copyOfRange(from, to))
        }
    }
}

/**
 * Reassembles split [VideoFrame]s. A frame is only returned once all of its parts arrived in
 * order; a part from a newer frame abandons an incomplete older one (counted in [dropped]).
 */
class FrameAssembler {
    data class Frame(val seq: Long, val pts: Long, val key: Boolean, val config: Boolean, val data: ByteArray)

    private var seq = -1L
    private var next = 0
    private var buf = java.io.ByteArrayOutputStream()
    private var head: VideoFrame? = null

    var dropped = 0
        private set

    fun accept(f: VideoFrame): Frame? {
        if (f.parts == 1) {
            if (next != 0) abandon()
            return Frame(f.seq, f.pts, f.key, f.config, f.data)
        }
        if (f.seq != seq) {
            if (next != 0) abandon()
            if (f.part != 0) return null.also { dropped++ }
            seq = f.seq
            buf = java.io.ByteArrayOutputStream()
            head = f
        } else if (f.part != next) {
            abandon()
            return null
        }
        buf.write(f.data)
        next = f.part + 1
        if (next < f.parts) return null
        val h = head ?: f
        next = 0
        seq = -1
        return Frame(h.seq, h.pts, h.key, h.config, buf.toByteArray())
    }

    private fun abandon() {
        dropped++
        next = 0
        seq = -1
        head = null
    }
}

/**
 * Adaptive bitrate for the screen stream. Backs off quickly when the socket queue grows or
 * the Mac reports lag/drops; creeps back up after a calm period. Returns the new bitrate when
 * it changes. Single-threaded.
 */
class AdaptiveBitrate(
    private val minBps: Int = 1_000_000,
    private val maxBps: Int = 8_000_000,
    startBps: Int = 4_000_000,
) {
    var bitrate: Int = startBps.coerceIn(minBps, maxBps)
        private set
    private var lastChange = Long.MIN_VALUE / 2
    private var calmSince = -1L

    /**
     * [queuedBytes]: bytes waiting in our socket queue. [lagMs]/[dropped]: from the Mac's last
     * feedback (0 when none). Returns the new bitrate, or null when unchanged.
     */
    fun update(queuedBytes: Long, lagMs: Int, dropped: Int, now: Long): Int? {
        val congested = queuedBytes > CONGESTED_BYTES || lagMs > LAG_HIGH_MS || dropped > 0
        val calm = queuedBytes < CALM_BYTES && lagMs < LAG_LOW_MS && dropped == 0
        if (congested) {
            calmSince = -1
            if (now - lastChange < DOWN_EVERY_MS) return null
            return set(Math.round(bitrate * DOWN).toInt(), now)
        }
        if (!calm) {
            calmSince = -1
            return null
        }
        if (calmSince < 0) calmSince = now
        if (now - calmSince < CALM_FOR_MS || now - lastChange < UP_EVERY_MS) return null
        return set(Math.round(bitrate * UP).toInt(), now)
    }

    private fun set(v: Int, now: Long): Int? {
        val next = v.coerceIn(minBps, maxBps)
        if (next == bitrate) return null
        bitrate = next
        lastChange = now
        return next
    }

    /** Whether to skip a non-key frame because the socket is backed up (keep latency low). */
    fun shouldDrop(queuedBytes: Long, keyframe: Boolean): Boolean = !keyframe && queuedBytes > DROP_BYTES

    companion object {
        const val CONGESTED_BYTES = 256L * 1024
        const val CALM_BYTES = 48L * 1024
        const val DROP_BYTES = 768L * 1024
        const val LAG_HIGH_MS = 180
        const val LAG_LOW_MS = 70
        const val DOWN_EVERY_MS = 1_000L
        const val UP_EVERY_MS = 2_000L
        const val CALM_FOR_MS = 3_000L
        const val DOWN = 0.7
        const val UP = 1.15
    }
}

/** How long a ring lasts and when it should stop on its own. */
class RingTimer(private val maxMs: Long = 60_000) {
    private var startedAt = -1L

    val ringing: Boolean get() = startedAt >= 0

    /** Start (or restart) ringing; [timeoutS] from the Mac is capped at [maxMs]. */
    fun start(now: Long, timeoutS: Int): Long {
        startedAt = now
        return (timeoutS.toLong() * MS).coerceIn(MIN_MS, maxMs)
    }

    fun stop() {
        startedAt = -1
    }

    /** True once the ring has run past [limitMs]. */
    fun expired(now: Long, limitMs: Long): Boolean = startedAt >= 0 && now - startedAt >= limitMs

    private companion object {
        const val MS = 1000L
        const val MIN_MS = 5_000L
    }
}
