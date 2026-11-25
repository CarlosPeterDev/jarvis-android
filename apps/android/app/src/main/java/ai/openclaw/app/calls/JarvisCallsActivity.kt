package ai.openclaw.app.calls

import ai.openclaw.app.MainActivity
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors

/** Call entry point; upstream chat, pairing, Talk and voice settings remain reachable. */
class JarvisCallsActivity : Activity() {
  private val handler = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()
  private lateinit var prefs: CallSettings
  private var settingsVisible = false
  private var pendingPermissionAction: String? = null
  private val refresh =
    object : Runnable {
      override fun run() {
        if (!settingsVisible) dashboard()
        handler.postDelayed(this, 1000)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    prefs = CallSettings(this)
    // Files exist only while a recording/player is alive. A previous process may
    // have been killed without onDestroy; remove its abandoned private clips.
    if (JarvisCallService.instance == null) cacheDir.listFiles()?.filter { it.name.startsWith("jarvis-mic-") || it.name.startsWith("jarvis-reply-") }?.forEach { it.delete() }
    handle(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handle(intent)
  }

  private fun handle(intent: Intent) {
    dashboard()
    if (intent.action == JarvisCallService.ANSWER && intent.component?.className == javaClass.name && intent.getStringExtra("callId") == JarvisCallService.snapshot.value.callId) withMicrophone(JarvisCallService.ANSWER)
  }

  override fun onResume() {
    super.onResume()
    handler.post(refresh)
  }

  override fun onPause() {
    handler.removeCallbacks(refresh)
    super.onPause()
  }

  override fun onDestroy() {
    worker.shutdownNow()
    super.onDestroy()
  }

  private fun container(): LinearLayout {
    val column =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 56, 32, 32)
        setBackgroundColor(Color.rgb(12, 15, 22))
      }
    setContentView(
      ScrollView(this).apply {
        isFillViewport = true
        addView(column)
      },
    )
    return column
  }

  private fun LinearLayout.label(
    value: String,
    size: Float = 18f,
  ): TextView =
    TextView(this@JarvisCallsActivity).apply {
      text = value
      textSize = size
      setTextColor(Color.WHITE)
      setPadding(0, 12, 0, 16)
      this@label.addView(this)
    }

  private fun LinearLayout.button(
    value: String,
    action: () -> Unit,
  ) {
    addView(
      Button(this@JarvisCallsActivity).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
      },
    )
  }

  private fun dashboard() {
    settingsVisible = false
    val call = JarvisCallService.snapshot.value
    setShowWhenLocked(call.callId != null)
    setTurnScreenOn(call.ringing)
    val column = container()
    with(column) {
      label("◉", 64f)
      label("Jarvis", 36f)
      label(call.status)
      if (call.ringing) {
        label("Incoming private voice call")
        button("Answer") { withMicrophone(JarvisCallService.ANSWER) }
        button("Decline") { command(JarvisCallService.DECLINE) }
      } else if (call.active) {
        val seconds = (SystemClock.elapsedRealtime() - call.started) / 1000
        label("%02d:%02d".format(seconds / 60, seconds % 60), 28f)
        button(if (call.muted) "Unmute" else "Mute") { command(JarvisCallService.MUTE) }
        button("Speaker / earpiece / Bluetooth") {
          val owner = JarvisCallService.instance
          val routes = owner?.routes().orEmpty()
          AlertDialog
            .Builder(this@JarvisCallsActivity)
            .setTitle("Audio route")
            .setItems(routes.map { it.second }.toTypedArray()) { _, index -> owner?.route(routes[index].first) }
            .show()
        }
        button("Interrupt Jarvis") { command(JarvisCallService.INTERRUPT) }
        button("End call") { command(JarvisCallService.HANGUP) }
        if (call.metrics.isNotEmpty()) label(call.metrics, 14f)
      } else {
        button("Call Jarvis") { withMicrophone(JarvisCallService.OUTGOING) }
        button("Receive calls / reconnect") { command("connect") }
        button("Server & call settings") { settings() }
        button("Chat & Talk Mode") { startActivity(Intent(this@JarvisCallsActivity, MainActivity::class.java)) }
        button("Disconnect listener") { if (JarvisCallService.instance != null) command(JarvisCallService.STOP) }
        label("Calls use your tablet’s local speech engines. Keep Receive calls connected for incoming calls.", 14f)
      }
    }
  }

  private fun withMicrophone(action: String) {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      pendingPermissionAction = action
      requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 71)
    } else {
      command(action)
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == 71) {
      val action = pendingPermissionAction
      pendingPermissionAction = null
      if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && action != null) {
        command(action)
      } else {
        notice("Microphone access is required for calls. You can enable it in Android app settings.")
      }
    }
  }

  private fun command(action: String) {
    val intent = Intent(this, JarvisCallService::class.java).setAction(action).putExtra("callId", JarvisCallService.snapshot.value.callId)
    try {
      startForegroundService(intent)
    } catch (_: Exception) {
      notice("Android could not start calling. Check permissions and battery restrictions.")
    }
  }

  private fun notice(text: String) {
    if (!isFinishing) {
      AlertDialog
        .Builder(this)
        .setMessage(text)
        .setPositiveButton("OK", null)
        .show()
    }
  }

  private fun settings() {
    settingsVisible = true
    setShowWhenLocked(false)
    setTurnScreenOn(false)
    val column = container()
    with(column) {
      label("Tablet connection", 28f)
      label("Server address and port (HTTPS)", 14f)
      val endpoint =
        EditText(this@JarvisCallsActivity).apply {
          setText(prefs.endpoint)
          setTextColor(Color.WHITE)
          hint = "https://tablet-host:port"
          inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
          addView(this)
        }
      label("Certificate SHA-256 fingerprint (verify on tablet)", 14f)
      val fingerprint =
        EditText(this@JarvisCallsActivity).apply {
          setText(prefs.fingerprint)
          setTextColor(Color.WHITE)
          inputType = InputType.TYPE_CLASS_TEXT
          addView(this)
        }
      label("One-time pairing code", 14f)
      val code =
        EditText(this@JarvisCallsActivity).apply {
          setTextColor(Color.WHITE)
          inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
          addView(this)
        }

      fun save(): Boolean =
        try {
          val normalized = CallSettings.normalizeEndpoint(endpoint.text.toString())
          val pin =
            fingerprint.text
              .toString()
              .replace(":", "")
              .trim()
              .lowercase()
          require(pin.isEmpty() || pin.matches(Regex("[0-9a-f]{64}")))
          if (normalized != prefs.endpoint || pin != prefs.fingerprint) {
            if (JarvisCallService.instance != null) command(JarvisCallService.STOP)
            prefs.token = ""
          }
          prefs.fingerprint = pin
          prefs.endpoint = normalized
          true
        } catch (_: Exception) {
          notice("Enter an HTTPS host and port, plus an optional valid SHA-256 certificate fingerprint.")
          false
        }
      button("Pair with tablet") {
        if (!save()) return@button
        val pairingCode = code.text.toString()
        code.setText("")
        worker.execute {
          val result =
            runCatching {
              val payload = JSONObject().put("name", Build.MODEL).put("code", pairingCode).toString()
              val request =
                Request
                  .Builder()
                  .url(prefs.endpoint + "/v1/pair")
                  .post(payload.toRequestBody("application/json".toMediaType()))
                  .build()
              prefs.client().newCall(request).execute().use { response ->
                check(response.isSuccessful)
                val token = JSONObject(response.body.string()).getString("token")
                require(token.length >= 40)
                prefs.token = token
              }
            }
          handler.post { notice(if (result.isSuccess) "Paired. Enable Receive calls to connect." else "Pairing failed. Check the code, TLS fingerprint, address and Tailscale connection.") }
        }
      }
      button("Save & test connection") {
        if (!save()) return@button
        worker.execute {
          val ok =
            runCatching {
              prefs
                .client()
                .newCall(
                  Request
                    .Builder()
                    .url(prefs.endpoint + "/v1/health")
                    .header("Authorization", "Bearer " + prefs.token)
                    .build(),
                ).execute()
                .use { it.isSuccessful }
            }.getOrDefault(false)
          handler.post { notice(if (ok) "Authenticated tablet connection verified." else "Connection failed. Check pairing, tablet service, TLS and Tailscale.") }
        }
      }
      button("Call notifications & ringtone") { startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, JarvisCallService.CALLS)) }
      button("Allow notifications") { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 72) }
      button("Allow Bluetooth audio") { if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 73) }
      if (Build.VERSION.SDK_INT >= 34) button("Full-screen incoming call access") { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }
      button("Battery & app settings") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
      button("Recent calls") {
        worker.execute {
          val result =
            runCatching {
              prefs
                .client()
                .newCall(
                  Request
                    .Builder()
                    .url(prefs.endpoint + "/v1/history")
                    .header("Authorization", "Bearer " + prefs.token)
                    .build(),
                ).execute()
                .use { response ->
                  check(response.isSuccessful)
                  val rows = org.json.JSONArray(response.body.string())
                  (0 until rows.length())
                    .joinToString("\n") { index ->
                      val row = rows.getJSONObject(index)
                      "${java.util.Date(row.getLong("created"))}: ${row.getString("direction")} — ${row.getString("outcome")}"
                    }.ifEmpty { "No calls recorded" }
                }
            }.getOrDefault("Could not load call history")
          handler.post { notice(result) }
        }
      }
      val fullScreen = if (Build.VERSION.SDK_INT >= 34) getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else true
      label("Diagnostics: paired=${prefs.token.isNotEmpty()}, full-screen=$fullScreen. Incoming policy and quiet hours are controlled on the tablet. Force stop, disabled notifications and battery restrictions can block calls.", 14f)
      button("Done") { dashboard() }
    }
  }
}
