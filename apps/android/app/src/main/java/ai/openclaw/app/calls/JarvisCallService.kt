package ai.openclaw.app.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** Owns signaling and audio across activities; a ringing call never opens the mic. */
class JarvisCallService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var settings: CallSettings
  private lateinit var audio: CallAudio
  private var socket: WebSocket? = null
  private var client: okhttp3.OkHttpClient? = null
  private var stopped = false
  private var ready = false
  private var outgoing = false
  private var outgoingRequest = ""
  private var retries = 0
  private var reconnectPending = false
  var connection: JarvisConnection? = null
  private val notifications by lazy { getSystemService(NotificationManager::class.java) }
  private val telecom by lazy { getSystemService(TelecomManager::class.java) }

  override fun onCreate() {
    super.onCreate()
    instance = this
    settings = CallSettings(this)
    audio =
      CallAudio(this, { bytes -> if (socket?.send(bytes.toByteString()) != true) problem("Audio transport unavailable") }, {
        hangup()
        problem("Audio unavailable; check microphone and audio route")
      })
    notifications.createNotificationChannel(NotificationChannel(PRESENCE, "Jarvis connection", NotificationManager.IMPORTANCE_LOW))
    notifications.createNotificationChannel(
      NotificationChannel(CALLS, "Jarvis calls", NotificationManager.IMPORTANCE_HIGH).apply {
        setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        enableVibration(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      },
    )
    // ConnectionService self-managed accounts remain necessary on Android 14.
    @Suppress("DEPRECATION")
    val selfManaged = PhoneAccount.CAPABILITY_SELF_MANAGED
    telecom.registerPhoneAccount(
      PhoneAccount
        .builder(account(this), "Jarvis")
        .setCapabilities(selfManaged)
        .setSupportedUriSchemes(listOf("jarvis"))
        .build(),
    )
    foreground(false)
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    when (intent?.action) {
      STOP -> {
        hangup()
        stopped = true
        stopSelf()
        return START_NOT_STICKY
      }

      OUTGOING -> {
        if (state.value.callId == null) {
          outgoing = true
          outgoingRequest =
            java.util.UUID
              .randomUUID()
              .toString()
          update("Calling Jarvis…")
          if (ready) send(JSONObject().put("type", "outgoing").put("requestId", outgoingRequest))
        }
      }

      ANSWER -> {
        if (intent.getStringExtra("callId") == state.value.callId && state.value.ringing) answer()
      }

      DECLINE -> {
        if (intent.getStringExtra("callId") == state.value.callId) {
          sendCall("decline")
          end("Declined")
        }
      }

      HANGUP -> {
        hangup()
      }

      MUTE -> {
        audio.muted = !audio.muted
        state.value = state.value.copy(muted = audio.muted)
      }

      INTERRUPT -> {
        sendCall("interrupt")
        audio.interrupt()
        update("Listening")
      }
    }
    if (socket == null && !stopped) connect()
    return START_NOT_STICKY
  }

  private fun connect() {
    if (settings.endpoint.isEmpty() || settings.token.isEmpty()) {
      problem("Pair the tablet in settings first")
      return
    }
    update("Connecting…")
    try {
      client?.dispatcher?.executorService?.shutdown()
      client?.connectionPool?.evictAll()
      client = settings.client()
      val request =
        Request
          .Builder()
          .url(settings.endpoint.replaceFirst("https:", "wss:") + "/v1/listen")
          .header("Authorization", "Bearer " + settings.token)
          .build()
      socket =
        client!!.newWebSocket(
          request,
          object : WebSocketListener() {
            override fun onMessage(
              webSocket: WebSocket,
              text: String,
            ) {
              handler.post { if (socket === webSocket) runCatching { event(JSONObject(text)) }.onFailure { problem("Invalid server event") } }
            }

            override fun onMessage(
              webSocket: WebSocket,
              bytes: ByteString,
            ) {
              handler.post { if (socket === webSocket && state.value.active) audio.play(bytes.toByteArray()) }
            }

            override fun onFailure(
              webSocket: WebSocket,
              t: Throwable,
              response: Response?,
            ) {
              handler.post { if (socket === webSocket) disconnected(response?.code == 401 || response?.code == 403 || t is javax.net.ssl.SSLHandshakeException || t is javax.net.ssl.SSLPeerUnverifiedException) }
            }

            override fun onClosed(
              webSocket: WebSocket,
              code: Int,
              reason: String,
            ) {
              handler.post { if (socket === webSocket) disconnected(code == 4001 || code == 4003) }
            }
          },
        )
    } catch (_: Exception) {
      disconnected(true)
    }
  }

  private fun disconnected(authFailure: Boolean) {
    socket?.cancel()
    socket = null
    ready = false
    audio.stop()
    update(if (authFailure) "Connection rejected; check pairing and TLS fingerprint" else "Reconnecting…")
    if (authFailure) {
      end("Connection rejected; check pairing and TLS fingerprint")
      return
    }
    if (stopped || reconnectPending) return
    reconnectPending = true
    handler.postDelayed({
      reconnectPending = false
      if (!stopped && socket == null) connect()
    }, (1000L shl retries.coerceAtMost(4)).also { retries++ })
  }

  private fun event(value: JSONObject) {
    when (value.getString("type")) {
      "ready" -> {
        ready = true
        if (state.value.callId != null && value.optString("activeCallId") != state.value.callId) end("Call expired during disconnection")
        retries = 0
        update("Tablet connected")
        if (outgoing) send(JSONObject().put("type", "outgoing").put("requestId", outgoingRequest))
      }

      "invite" -> {
        val id = value.getString("callId")
        val expires = value.getLong("expires")
        if (expires <= System.currentTimeMillis() || expires - System.currentTimeMillis() > 125_000 || state.value.callId == id) return
        if (state.value.callId != null) {
          send(JSONObject().put("type", "decline").put("callId", id))
          return
        }
        state.value = CallSnapshot(callId = id, ringing = true, status = "Incoming call")
        try {
          telecom.addNewIncomingCall(account(this), Bundle().apply { putString("callId", id) })
        } catch (_: Exception) {
          sendCall("decline")
          end("Android could not present the call")
        }
        handler.postDelayed({
          if (state.value.callId == id && state.value.ringing) {
            end("Missed call")
          }
        }, expires - System.currentTimeMillis())
      }

      "active" -> {
        val id = value.getString("callId")
        if (state.value.callId != null && state.value.callId != id) return
        if (outgoing) {
          outgoing = false
          state.value = CallSnapshot(callId = id, status = "Establishing call")
          try {
            telecom.placeCall("jarvis:assistant".toUri(), Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account(this@JarvisCallService)) })
          } catch (_: SecurityException) {
            hangup()
            problem("Android denied permission to place this call")
          } catch (_: Exception) {
            hangup()
            problem("Android could not start the call")
          }
        } else if (connection != null && state.value.callId == id) {
          activate()
        } else {
          send(JSONObject().put("type", "hangup").put("callId", id))
          end("Call ended; start a new call")
        }
      }

      "ended" -> {
        if (value.optString("callId") == state.value.callId) end(value.optString("outcome", "Call ended"))
      }

      "progress" -> {
        update(
          when (value.optString("stage")) {
            "transcribing" -> "Transcribing on tablet"
            "synthesizing" -> "Preparing voice"
            else -> "Jarvis is working"
          },
        )
      }

      "metrics" -> {
        state.value = state.value.copy(metrics = "Tablet response: %.1f s".format(value.optDouble("responseMs") / 1000))
      }

      "interrupted" -> {
        audio.ready()
      }

      "error" -> {
        outgoing = false
        audio.ready()
        update(value.optString("message", "Call error"))
      }
    }
  }

  fun answer() {
    if (!state.value.ringing) return
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      problem("Open Jarvis and allow microphone access before answering")
      return
    }
    sendCall("answer")
  }

  fun activate() {
    val runtime = (application as ai.openclaw.app.NodeApp).peekRuntime()
    if (!state.value.active && runtime != null && !runtime.setJarvisCallAudioActive(true)) {
      hangup()
      problem("Microphone is busy in chat or Talk Mode")
      return
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      hangup()
      return
    }
    state.value = state.value.copy(ringing = false, active = true, started = state.value.started.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(), status = "Listening")
    connection?.setActive()
    notifications.cancel(RING_ID)
    try {
      foreground(true)
      audio.start()
    } catch (_: Exception) {
      hangup()
      problem("Android blocked microphone access")
    }
  }

  fun showIncoming() {
    val snapshot = state.value
    if (!snapshot.ringing) return
    val launch = activityIntent(0, "show")
    val answer = activityIntent(1, ANSWER)
    val decline = serviceIntent(2, DECLINE)
    val person =
      Person
        .Builder()
        .setName("Jarvis")
        .setImportant(true)
        .build()
    val notification =
      Notification
        .Builder(this, CALLS)
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setContentTitle("Jarvis")
        .setContentText("Incoming call")
        .setCategory(Notification.CATEGORY_CALL)
        .setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
        .setFullScreenIntent(launch, true)
        .setContentIntent(launch)
        .setOngoing(true)
        .setTimeoutAfter(125_000)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .build()
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33) notifications.notify(RING_ID, notification)
  }

  private fun foreground(active: Boolean) {
    val notification =
      Notification
        .Builder(this, PRESENCE)
        .setSmallIcon(android.R.drawable.sym_call_outgoing)
        .setContentTitle("Jarvis")
        .setContentText(if (active) "Voice call in progress" else "Listening for private call invitations")
        .setContentIntent(activityIntent(3, "show"))
        .setOngoing(true)
        .addAction(Notification.Action.Builder(null, if (active) "End call" else "Disconnect", serviceIntent(4, if (active) HANGUP else STOP)).build())
        .build()
    val type =
      if (active) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      } else if (Build.VERSION.SDK_INT >= 34) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      } else {
        0
      }
    startForeground(SERVICE_ID, notification, type)
  }

  private fun activityIntent(
    requestCode: Int,
    action: String,
  ): PendingIntent = PendingIntent.getActivity(this, requestCode, Intent(this, JarvisCallsActivity::class.java).setAction(action).putExtra("callId", state.value.callId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

  private fun serviceIntent(
    requestCode: Int,
    action: String,
  ): PendingIntent = PendingIntent.getService(this, requestCode, Intent(this, JarvisCallService::class.java).setAction(action).putExtra("callId", state.value.callId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

  private fun send(value: JSONObject) {
    if (socket?.send(value.toString()) != true) problem("Tablet is disconnected")
  }

  private fun sendCall(type: String) {
    state.value.callId?.let { send(JSONObject().put("type", type).put("callId", it)) }
  }

  fun hangup() {
    sendCall("hangup")
    end("Call ended")
  }

  private fun end(reason: String) {
    audio.stop()
    (application as ai.openclaw.app.NodeApp).peekRuntime()?.setJarvisCallAudioActive(false)
    audio.muted = false
    notifications.cancel(RING_ID)
    val current = connection
    connection = null
    current?.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
    current?.destroy()
    outgoing = false
    state.value = CallSnapshot(status = reason)
    if (!stopped) foreground(false)
  }

  private fun update(status: String) {
    state.value = state.value.copy(status = status, connected = ready)
  }

  private fun problem(message: String) {
    update(message)
  }

  fun routes() = audio.routes()

  fun route(id: Int) = audio.route(id)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    stopped = true
    handler.removeCallbacksAndMessages(null)
    socket?.cancel()
    socket = null
    ready = false
    end("Disconnected")
    client?.dispatcher?.executorService?.shutdown()
    client?.connectionPool?.evictAll()
    instance = null
    super.onDestroy()
  }

  companion object {
    const val OUTGOING = "jarvis.OUTGOING"
    const val ANSWER = "jarvis.ANSWER"
    const val DECLINE = "jarvis.DECLINE"
    const val HANGUP = "jarvis.HANGUP"
    const val MUTE = "jarvis.MUTE"
    const val INTERRUPT = "jarvis.INTERRUPT"
    const val STOP = "jarvis.STOP"
    const val CALLS = "jarvis_calls"
    const val PRESENCE = "jarvis_presence"
    private const val SERVICE_ID = 7510
    private const val RING_ID = 7511
    private val state = MutableStateFlow(CallSnapshot())
    val snapshot = state.asStateFlow()
    var instance: JarvisCallService? = null
      private set

    fun account(context: android.content.Context) = PhoneAccountHandle(ComponentName(context, JarvisConnectionService::class.java), "jarvis")
  }
}

data class CallSnapshot(
  val callId: String? = null,
  val ringing: Boolean = false,
  val active: Boolean = false,
  val connected: Boolean = false,
  val muted: Boolean = false,
  val started: Long = 0,
  val status: String = "Disconnected",
  val metrics: String = "",
)
