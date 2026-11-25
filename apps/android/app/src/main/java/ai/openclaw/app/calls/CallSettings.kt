package ai.openclaw.app.calls

import ai.openclaw.app.NodeApp
import ai.openclaw.app.gateway.GatewayTlsParams
import ai.openclaw.app.gateway.buildGatewayTlsConfig
import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/** Separate, Keystore-backed credentials grant only the companion's call API. */
class CallSettings(
  context: Context,
) {
  private val prefs = (context.applicationContext as NodeApp).prefs
  var endpoint: String
    get() = prefs.getString("jarvis.calls.endpoint") ?: ""
    set(value) {
      prefs.putString("jarvis.calls.endpoint", normalizeEndpoint(value))
    }
  var fingerprint: String
    get() = prefs.getString("jarvis.calls.fingerprint") ?: ""
    set(value) {
      val clean = value.replace(":", "").trim().lowercase()
      require(clean.isEmpty() || clean.matches(Regex("[0-9a-f]{64}"))) { "Use a SHA-256 certificate fingerprint" }
      prefs.putString("jarvis.calls.fingerprint", clean)
    }
  var token: String
    get() = prefs.getString("jarvis.calls.token") ?: ""
    set(value) {
      prefs.putString("jarvis.calls.token", value)
    }

  fun client(): OkHttpClient = createCallHttpClient(fingerprint)

  companion object {
    fun normalizeEndpoint(value: String): String {
      val url = value.trim().toHttpUrl()
      require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) { "Use https://tablet-host:port with no path or credentials" }
      return url.toString().trimEnd('/')
    }
  }
}

/** Reuse upstream pin/chain validation, retaining OkHttp's hostname verification. */
internal fun createCallHttpClient(fingerprint: String): OkHttpClient {
  val builder = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS)
  if (fingerprint.isNotEmpty()) {
    val tls = requireNotNull(buildGatewayTlsConfig(GatewayTlsParams(fingerprint)))
    builder.sslSocketFactory(tls.sslSocketFactory, tls.trustManager)
    // Upstream's explicit pin is the trust anchor. Also enforce expiry before
    // sending HTTP authorization; retain the default OkHttp hostname verifier.
    builder.addNetworkInterceptor { chain ->
      val leaf =
        chain
          .connection()
          ?.handshake()
          ?.peerCertificates
          ?.firstOrNull() as? X509Certificate
      requireNotNull(leaf) { "Missing tablet certificate" }.checkValidity()
      chain.proceed(chain.request())
    }
  }
  return builder.build()
}
