package com.raihan.coverdeck.privileged

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puts the inner display's live content onto a Surface that lives on the cover screen.
 *
 * Two engines, tried in order:
 *
 *  1. [VirtualDisplayEngine] asks SurfaceFlinger for a mirroring VirtualDisplay and
 *     lets it composite straight into our Surface. No encode, no decode, no copy.
 *
 *  2. [ScreenrecordEngine] spawns /system/bin/screenrecord, parses the raw H.264
 *     stream and decodes it into the same Surface. Roughly 150 ms of latency and real
 *     CPU cost, but it only depends on a stable shell binary.
 *
 * An engine only reports success once it has proven it works, so the fallback order
 * means something. The first on-device run showed why that matters: engine 2 used to
 * say "started" immediately while silently failing in a loop.
 */
internal interface MirrorEngine {
    val name: String
    fun start(surface: Surface, sourceDisplayId: Int, width: Int, height: Int, densityDpi: Int): Boolean
    fun stop()
}

internal class VirtualDisplayEngine(private val context: Context?) : MirrorEngine {

    override val name = "virtual-display"

    private var display: VirtualDisplay? = null

    override fun start(
        surface: Surface,
        sourceDisplayId: Int,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean {
        stop()

        // Android 14 added a hidden static overload that mirrors a display by id.
        // scrcpy relies on it from the same shell-uid position we are in.
        display = attempt("static mirror-by-id") {
            DisplayManager::class.java.getMethod(
                "createVirtualDisplay",
                String::class.java, Int::class.java, Int::class.java, Int::class.java, Surface::class.java,
            ).invoke(null, NAME, width, height, sourceDisplayId, surface) as VirtualDisplay?
        }

        // Public API with AUTO_MIRROR, but through a DisplayManager that claims
        // com.android.shell. The plain system context failed the uid/package check.
        if (display == null) {
            display = attempt("shell-context auto-mirror") {
                Hidden.shellDisplayManager(context)?.createVirtualDisplay(
                    NAME, width, height, densityDpi, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or AUTO_MIRROR,
                )
            }
        }

        return display != null
    }

    private inline fun attempt(label: String, block: () -> VirtualDisplay?): VirtualDisplay? =
        runCatching(block)
            .onSuccess { if (it != null) Log.i(Hidden.TAG, "virtual display via $label") }
            .onFailure { Log.w(Hidden.TAG, "$label failed: ${it.cause ?: it}") }
            .getOrNull()

    override fun stop() {
        runCatching { display?.surface = null }
        runCatching { display?.release() }
        display = null
    }

    private companion object {
        const val NAME = "CoverDeck-Mirror"

        // Hidden, but (1 shl 4) since API 19 and gated on CAPTURE_VIDEO_OUTPUT.
        // Deliberately paired with no OWN_CONTENT_ONLY, which is what makes it mirror.
        const val AUTO_MIRROR = 1 shl 4
    }
}

internal class ScreenrecordEngine : MirrorEngine {

    override val name = "screenrecord"

    private val running = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    private var worker: Thread? = null

    override fun start(
        surface: Surface,
        sourceDisplayId: Int,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean {
        stop()

        // Without a physical id screenrecord records the *primary* panel, which is the
        // cover while folded: it would mirror the cover onto itself. Refuse instead.
        val physicalId = Hidden.physicalDisplayId(sourceDisplayId) ?: run {
            Log.w(Hidden.TAG, "no physical id for display $sourceDisplayId; screenrecord unusable")
            return false
        }

        val firstFrame = CountDownLatch(1)
        val streaming = AtomicBoolean(false)
        running.set(true)
        worker = Thread(
            { pump(surface, physicalId, width, height, streaming, firstFrame) },
            "coverdeck-mirror",
        ).apply {
            isDaemon = true
            start()
        }

        firstFrame.await(FIRST_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!streaming.get()) {
            Log.w(Hidden.TAG, "screenrecord produced no stream within ${FIRST_FRAME_TIMEOUT_MS}ms")
            stop()
            return false
        }
        return true
    }

    private fun pump(
        surface: Surface,
        physicalId: String,
        width: Int,
        height: Int,
        streaming: AtomicBoolean,
        firstFrame: CountDownLatch,
    ) {
        val cmd = "/system/bin/screenrecord --display-id $physicalId --output-format=h264 " +
            "--size ${width}x$height --bit-rate 8000000 --time-limit 175 -"
        var quickFailures = 0

        // screenrecord caps a session at 180 s, so a healthy run ends and is restarted.
        // A run that dies young or yields nothing is a failure; three in a row and the
        // engine gives up rather than spawning processes in a hot loop.
        while (running.get()) {
            val startedAt = SystemClock.elapsedRealtime()
            var codec: MediaCodec? = null
            var proc: Process? = null
            var gotData = false
            var stderr = ""
            try {
                proc = ProcessBuilder("sh", "-c", cmd).start().also { process = it }
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(
                        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height),
                        surface,
                        null,
                        0,
                    )
                    start()
                }
                gotData = feed(proc.inputStream, codec) {
                    if (!streaming.getAndSet(true)) firstFrame.countDown()
                }
            } catch (t: Throwable) {
                if (running.get()) Log.e(Hidden.TAG, "screenrecord session threw", t)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                stderr = runCatching {
                    proc?.errorStream?.bufferedReader()?.readText()?.take(300).orEmpty()
                }.getOrDefault("")
                runCatching { proc?.destroy() }
            }

            if (!running.get()) break
            val lived = SystemClock.elapsedRealtime() - startedAt
            if (!gotData || lived < HEALTHY_SESSION_MS) {
                quickFailures++
                Log.w(Hidden.TAG, "screenrecord ended after ${lived}ms (data=$gotData): ${stderr.trim()}")
                if (quickFailures >= MAX_QUICK_FAILURES) {
                    Log.e(Hidden.TAG, "screenrecord failed $quickFailures times in a row; giving up")
                    running.set(false)
                    break
                }
                runCatching { Thread.sleep(500L * quickFailures) }
            } else {
                quickFailures = 0
            }
        }
        firstFrame.countDown()
    }

    /**
     * Splits the Annex-B stream on start codes and hands whole NAL units to the
     * decoder. Returns whether anything decodable was queued.
     */
    private fun feed(input: InputStream, codec: MediaCodec, onFirstData: () -> Unit): Boolean {
        val chunk = ByteArray(1 shl 16)
        val pending = ByteArrayOutputStream(1 shl 18)
        val info = MediaCodec.BufferInfo()
        var sawConfig = false
        var queuedAny = false

        while (running.get()) {
            val read = input.read(chunk)
            if (read <= 0) return queuedAny
            pending.write(chunk, 0, read)

            val bytes = pending.toByteArray()
            var consumed = 0
            var start = nextStartCode(bytes, 0)
            while (start >= 0) {
                val next = nextStartCode(bytes, start + 3)
                if (next < 0) break
                val nal = bytes.copyOfRange(start, next)
                val type = nal.getOrNull(startCodeLen(nal))?.toInt()?.and(0x1f) ?: 0
                val isConfig = type == NAL_SPS || type == NAL_PPS
                if (isConfig) sawConfig = true
                // Anything before the first SPS is undecodable; drop it rather than
                // letting MediaCodec throw on a mid-GOP start.
                if (sawConfig) {
                    queue(codec, nal, if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                    if (!queuedAny) {
                        queuedAny = true
                        onFirstData()
                    }
                }
                consumed = next
                start = next
            }
            pending.reset()
            pending.write(bytes, consumed, bytes.size - consumed)

            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                codec.releaseOutputBuffer(out, true)
            }
        }
        return queuedAny
    }

    private fun queue(codec: MediaCodec, nal: ByteArray, flags: Int) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(nal)
        codec.queueInputBuffer(index, 0, nal.size, System.nanoTime() / 1000, flags)
    }

    private fun startCodeLen(nal: ByteArray): Int =
        if (nal.size >= 4 && nal[0] == ZERO && nal[1] == ZERO && nal[2] == ZERO && nal[3] == ONE) 4 else 3

    private fun nextStartCode(b: ByteArray, from: Int): Int {
        var i = from
        while (i + 2 < b.size) {
            if (b[i] == ZERO && b[i + 1] == ZERO && b[i + 2] == ONE) {
                return if (i > 0 && b[i - 1] == ZERO) i - 1 else i
            }
            i++
        }
        return -1
    }

    override fun stop() {
        running.set(false)
        runCatching { process?.destroy() }
        process = null
        runCatching { worker?.join(800) }
        worker = null
    }

    private companion object {
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val ZERO: Byte = 0
        const val ONE: Byte = 1
        const val FIRST_FRAME_TIMEOUT_MS = 2500L
        const val HEALTHY_SESSION_MS = 3000L
        const val MAX_QUICK_FAILURES = 3
    }
}
