package dev.martin.cubicai.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * The phone's side of a call's sound: the microphone (16 kHz, with the phone's echo canceller, so the voice
 * coming out of the speaker isn't heard as the user) and the voice (24 kHz), played sentence by sentence.
 *
 * The player knows which sentences were actually heard, so a reply cut short by the user keeps only those.
 */
class CallAudio(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var focus: AudioFocusRequest? = null
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    @Volatile private var running = false

    /** Microphone audio, 20 ms at a time (called on the microphone's thread). */
    @Volatile var onMic: ((ByteArray, Int) -> Unit)? = null
    @Volatile var muted = false

    /** Loudness of the user (0..1) and of the voice, for the cube. */
    @Volatile var micLevel = 0f
        private set
    @Volatile var voiceLevel = 0f
        private set

    /** A sentence starts playing; a turn has been played to the end; whether anything is playing. */
    @Volatile var onSentence: ((turn: Int, text: String) -> Unit)? = null
    @Volatile var onTurnPlayed: ((turn: Int) -> Unit)? = null
    @Volatile var onPlaying: ((Boolean) -> Unit)? = null

    private sealed interface Item {
        data class Sentence(val turn: Int, val text: String) : Item
        class Pcm(val turn: Int, val bytes: ByteArray) : Item
        data class End(val turn: Int) : Item
    }

    private val queue = LinkedBlockingQueue<Item>()
    /** Bumped by [flush]: whatever the player was busy with is dropped. */
    @Volatile private var generation = 0
    @Volatile private var paused = false
    /** The turn whose audio is arriving, and turns that were cut off (their late audio is dropped). */
    @Volatile private var audioTurn = 0
    @Volatile private var droppedBelow = 0
    private val heard = mutableMapOf<Int, StringBuilder>()
    @Volatile private var playing = false

    @SuppressLint("MissingPermission") // asked for before a call starts
    fun start(speaker: Boolean) {
        if (running) return
        running = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).build().also { audio.requestAudioFocus(it) }
        setSpeaker(speaker)

        val inSize = maxOf(AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), IN_RATE / 5 * 2)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inSize)
        record = rec
        if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }

        val outSize = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        val player = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(outSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = player
        player.play()

        micThread = Thread({ micLoop(rec) }, "call-mic").apply { priority = Thread.MAX_PRIORITY; start() }
        playThread = Thread({ playLoop(player) }, "call-voice").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        queue.clear()
        queue.offer(Item.End(-1)) // wakes the player
        micThread?.join(500)
        playThread?.join(500)
        runCatching { record?.stop() }
        record?.release()
        echo?.release()
        noise?.release()
        runCatching { track?.stop() }
        track?.release()
        record = null; track = null; echo = null; noise = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = false }
        audio.mode = AudioManager.MODE_NORMAL
        micLevel = 0f
        voiceLevel = 0f
    }

    /** Loudspeaker, or the earpiece (a headset when one is connected). */
    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in HEADSETS }
            val target = if (on) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (target != null) audio.setCommunicationDevice(target) else audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = on
        }
    }

    /** Whether a headset is connected (then the call starts without the loudspeaker). */
    fun headsetConnected(): Boolean = if (Build.VERSION.SDK_INT >= 31) audio.availableCommunicationDevices.any { it.type in HEADSETS }
    else audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS }

    // --- The voice ---

    fun sentence(turn: Int, text: String) {
        audioTurn = turn
        if (turn >= droppedBelow) queue.offer(Item.Sentence(turn, text))
    }

    fun pcm(bytes: ByteArray) {
        val turn = audioTurn
        if (turn >= droppedBelow) queue.offer(Item.Pcm(turn, bytes))
    }

    fun turnEnd(turn: Int) {
        if (turn >= droppedBelow) queue.offer(Item.End(turn))
    }

    /** Holds the voice while the user may be starting to talk. */
    fun pause() {
        if (paused) return
        paused = true
        runCatching { track?.pause() }
    }

    fun resume() {
        if (!paused) return
        paused = false
        runCatching { track?.play() }
    }

    /**
     * Drops everything not yet played, including audio of turns up to [upTo] still on its way, and returns the
     * words of [turn] that were heard.
     */
    fun flush(turn: Int, upTo: Int): String {
        droppedBelow = upTo + 1
        generation++
        queue.clear()
        runCatching {
            track?.pause()
            track?.flush()
            track?.play()
        }
        paused = false
        setPlaying(false)
        voiceLevel = 0f
        return synchronized(heard) { heard.remove(turn)?.toString()?.trim().orEmpty() }
    }

    /** Something is playing or waiting to be played. */
    val busy get() = playing || queue.isNotEmpty()

    fun heardOf(turn: Int): String = synchronized(heard) { heard[turn]?.toString()?.trim().orEmpty() }

    private fun setPlaying(on: Boolean) {
        if (playing == on) return
        playing = on
        onPlaying?.invoke(on)
    }

    private fun micLoop(rec: AudioRecord) {
        rec.startRecording()
        val buffer = ByteArray(IN_RATE / 50 * 2) // 20 ms
        while (running) {
            val n = rec.read(buffer, 0, buffer.size)
            if (n <= 0) continue
            if (muted) {
                micLevel = 0f
                continue
            }
            micLevel = level(buffer, n, micLevel)
            onMic?.invoke(buffer, n)
        }
    }

    private fun playLoop(player: AudioTrack) {
        var written = 0L // frames written since the last flush
        var flushedAt = generation
        while (running) {
            val item = queue.poll(200, TimeUnit.MILLISECONDS)
            if (generation != flushedAt) {
                flushedAt = generation
                written = 0
            }
            if (item == null) {
                if (queue.isEmpty() && playing && !paused && player.playbackHeadPosition.toLong() >= written) setPlaying(false)
                voiceLevel *= 0.5f
                continue
            }
            when (item) {
                is Item.Sentence -> {
                    if (item.turn < droppedBelow) continue
                    // Counted as heard when it starts: the user hears it while deciding to interrupt.
                    synchronized(heard) { heard.getOrPut(item.turn) { StringBuilder() }.append(item.text).append(' ') }
                    setPlaying(true)
                    onSentence?.invoke(item.turn, item.text)
                }
                is Item.Pcm -> {
                    if (item.turn < droppedBelow) continue
                    val gen = generation
                    var offset = 0
                    while (offset < item.bytes.size && running && gen == generation) {
                        if (paused) {
                            Thread.sleep(20)
                            continue
                        }
                        val n = player.write(item.bytes, offset, minOf(item.bytes.size - offset, 1920), AudioTrack.WRITE_NON_BLOCKING)
                        if (n < 0) break
                        if (n == 0) {
                            Thread.sleep(10)
                            continue
                        }
                        voiceLevel = level(item.bytes, n, voiceLevel, offset)
                        offset += n
                        written += n / 2
                    }
                }
                is Item.End -> {
                    if (item.turn < 0 || item.turn < droppedBelow) continue
                    val gen = generation
                    // Wait for the last of it to come out of the speaker before saying the turn was heard.
                    while (running && gen == generation && (paused || player.playbackHeadPosition.toLong() < written)) {
                        if (queue.isNotEmpty() && !paused) break // the next turn is already here
                        Thread.sleep(20)
                    }
                    if (gen == generation) {
                        if (queue.isEmpty()) setPlaying(false)
                        synchronized(heard) { heard.keys.removeAll { it < item.turn } }
                        onTurnPlayed?.invoke(item.turn)
                    }
                }
            }
        }
    }

    private fun level(bytes: ByteArray, n: Int, previous: Float, offset: Int = 0): Float {
        var sum = 0.0
        var count = 0
        var i = offset
        while (i + 1 < offset + n) {
            val s = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            count++
            i += 2
        }
        if (count == 0) return previous
        val rms = (sqrt(sum / count) / 32768.0).toFloat()
        val now = (rms * 6f).coerceIn(0f, 1f)
        return if (now > previous) now else previous * 0.8f + now * 0.2f
    }

    companion object {
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        private val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
