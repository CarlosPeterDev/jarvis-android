package ai.openclaw.app.calls

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Separate, Keystore-backed credentials grant only the companion's call API. */
class CallSettings(context: Context) {
  private val prefs = EncryptedSharedPreferences.create(
    context,
    "jarvis_calls",
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
  )
  var endpoint: String
    get() = prefs.getString("endpoint", "") ?: ""
    set(value) { prefs.edit().putString("endpoint", normalizeEndpoint(value)).apply() }
  var fingerprint: String
    get() = prefs.getString("fingerprint", "") ?: ""
    set(value) {
      val clean = value.replace(":", "").trim().lowercase()
      require(clean.isEmpty() || clean.matches(Regex("[0-9a-f]{64}"))) { "Use a SHA-256 certificate fingerprint" }
      prefs.edit().putString("fingerprint", clean).apply()
    }
  var token: String
    get() = prefs.getString("token", "") ?: ""
    set(value) { prefs.edit().putString("token", value).apply() }

  fun client(): OkHttpClient {
    val builder = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS)
    val pin = fingerprint
    if (pin.isNotEmpty()) {
      // Explicit trust anchor for this configured endpoint. Date and hostname
      // checks remain enabled; no permissive HostnameVerifier is installed.
      val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Client certificates not accepted") }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
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
