package ai.openclaw.app.calls

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle

class JarvisConnectionService : ConnectionService() {
  override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
    val owner = JarvisCallService.instance ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
    if (request.extras?.getString("callId") != JarvisCallService.snapshot.value.callId) return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
    return JarvisConnection().also { owner.connection = it; it.setRinging() }
  }
  override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
    val owner = JarvisCallService.instance ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
    if (JarvisCallService.snapshot.value.callId == null) return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
    return JarvisConnection().also { owner.connection = it; it.setDialing(); owner.activate() }
  }
  override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) { JarvisCallService.instance?.hangup() }
  override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) { JarvisCallService.instance?.hangup() }
}
class JarvisConnection : Connection() {
  init {
    connectionProperties = PROPERTY_SELF_MANAGED
    setAudioModeIsVoip(true)
    setCallerDisplayName("Jarvis", android.telecom.TelecomManager.PRESENTATION_ALLOWED)
    connectionCapabilities = CAPABILITY_MUTE
  }
  override fun onShowIncomingCallUi() { JarvisCallService.instance?.showIncoming() }
  override fun onAnswer() { JarvisCallService.instance?.answer() }
  override fun onAnswer(videoState: Int) { onAnswer() }
  override fun onReject() { JarvisCallService.instance?.let { owner -> owner.startService(android.content.Intent(owner, JarvisCallService::class.java).setAction(JarvisCallService.DECLINE).putExtra("callId", JarvisCallService.snapshot.value.callId)) } }
  override fun onDisconnect() { JarvisCallService.instance?.hangup() }
  override fun onAbort() { onDisconnect() }
}
