package ai.openclaw.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/** Opus utterances: existing tablet STT/TTS are file based. No cloud recognizer. */
class CallAudio(private val context: Context, private val send: (ByteArray) -> Unit, private val failed: () -> Unit) {
  private val handler = Handler(Looper.getMainLooper())
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private var recorder: MediaRecorder? = null
  private var player: MediaPlayer? = null
  private var input: File? = null
  private var output: File? = null
  private var started = 0L
  private var lastVoice = 0L
  private var heardVoice = false
  private var active = false
  private var waiting = false
  var muted = false
    set(value) { field = value; if (value) discardInput() else listen() }
  private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    .setOnAudioFocusChangeListener { change -> if (change < 0) { stop(); failed() } }
    .build()
  private val poll = object : Runnable {
    override fun run() {
      val current = recorder ?: return
      try {
        val now = SystemClock.elapsedRealtime()
        if (current.maxAmplitude > 1400) { heardVoice = true; lastVoice = now }
        if ((heardVoice && now - lastVoice > 900 && now - started > 1200) || now - started > 20_000) {
          val file = input
          recorder = null; input = null
          current.stop(); current.release()
          if (heardVoice && file != null) {
            val bytes = file.readBytes(); file.delete(); waiting = true
            if (bytes.size <= 1_000_000) send(bytes) else { waiting = false; failed() }
          } else { file?.delete(); listen() }
        } else handler.postDelayed(this, 100)
      } catch (_: Exception) { discardInput(); failed() }
    }
  }
  fun start() {
    if (active) return
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { failed(); return }
    if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { failed(); return }
    active = true; waiting = false
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    listen()
  }
  private fun listen() {
    if (!active || muted || waiting || player != null || recorder != null) return
    try {
      val file = File.createTempFile("jarvis-mic-", ".ogg", context.cacheDir)
      input = file
      recorder = MediaRecorder(context).apply {
        setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        setOutputFormat(MediaRecorder.OutputFormat.OGG)
        setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        setAudioChannels(1); setAudioSamplingRate(48000); setAudioEncodingBitRate(24000)
        setOutputFile(file.absolutePath); prepare(); start()
      }
      started = SystemClock.elapsedRealtime(); lastVoice = started; heardVoice = false
      handler.postDelayed(poll, 100)
    } catch (_: Exception) { discardInput(); failed() }
  }
  fun play(bytes: ByteArray) {
    if (!active || bytes.size > 4_000_000) return
    discardInput(); stopPlayer(); waiting = false
    try {
      val file = File.createTempFile("jarvis-reply-", ".ogg", context.cacheDir).also { it.writeBytes(bytes) }
      output = file
      player = MediaPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        setDataSource(file.absolutePath)
        setOnCompletionListener { stopPlayer(); listen() }
        setOnErrorListener { _, _, _ -> stopPlayer(); failed(); true }
        prepare(); start()
      }
    } catch (_: Exception) { stopPlayer(); failed() }
  }
  fun ready() { waiting = false; listen() }
  fun interrupt() { stopPlayer(); waiting = false; listen() }
  fun route(deviceId: Int) {
    audioManager.availableCommunicationDevices.firstOrNull { it.id == deviceId }?.let { audioManager.setCommunicationDevice(it) }
  }
  fun routes(): List<Pair<Int, String>> = audioManager.availableCommunicationDevices.map { it.id to it.productName.toString() }
  private fun discardInput() {
    handler.removeCallbacks(poll)
    val current = recorder; recorder = null
    runCatching { current?.stop() }; current?.release()
    input?.delete(); input = null
  }
  private fun stopPlayer() { player?.release(); player = null; output?.delete(); output = null }
  fun stop() {
    active = false; waiting = false; discardInput(); stopPlayer()
    audioManager.clearCommunicationDevice(); audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.abandonAudioFocusRequest(focus)
  }
}
