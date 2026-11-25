package ai.openclaw.app.calls

import ai.openclaw.app.NodeApp
import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

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

  fun client(): OkHttpClient {
    val builder = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS)
    val pin = fingerprint
    if (pin.isNotEmpty()) {
      // Explicit trust anchor for this configured endpoint. Date and hostname
      // checks remain enabled; no permissive HostnameVerifier is installed.
      val trust =
        object : X509TrustManager {
          override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

          override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
          ): Unit = throw CertificateException("Client certificates not accepted")

          override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
          ) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Missing certificate")
            leaf.checkValidity()
            val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
            if (actual != pin) throw CertificateException("Tablet certificate fingerprint mismatch")
          }
        }
      val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
      builder.sslSocketFactory(tls.socketFactory, trust)
    }
    return builder.build()
  }

  companion object {
    fun normalizeEndpoint(value: String): String {
      val url = value.trim().toHttpUrl()
      require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) { "Use https://tablet-host:port with no path or credentials" }
      return url.toString().trimEnd('/')
    }
  }
}
