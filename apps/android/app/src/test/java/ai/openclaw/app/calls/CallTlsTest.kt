package ai.openclaw.app.calls

import ai.openclaw.app.gateway.gatewayTlsTestFixture
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallTlsTest {
  @Test
  fun matchingPinAllowsTheCorrectHostname() {
    val fixture = gatewayTlsTestFixture()
    MockWebServer().use { server ->
      server.useHttps(fixture.socketFactory, false)
      server.enqueue(MockResponse().setBody("ok"))
      createCallHttpClient(fixture.fingerprint).newCall(Request.Builder().url(server.url("/")).build()).execute().use { assertEquals(200, it.code) }
    }
  }

  @Test
  fun wrongPinAndUntrustedCertificateCannotSendRequests() {
    val fixture = gatewayTlsTestFixture()
    MockWebServer().use { server ->
      server.useHttps(fixture.socketFactory, false)
      server.start()
      for (pin in listOf(fixture.wrongFingerprint, "")) {
        val client = createCallHttpClient(pin).newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
        assertThrows(IOException::class.java) {
          client
            .newCall(
              Request
                .Builder()
                .url(server.url("/"))
                .header("Authorization", "Bearer fixture-only")
                .build(),
            ).execute()
            .close()
        }
      }
      assertEquals(0, server.requestCount)
    }
  }

  @Test
  fun aMatchingPinDoesNotDisableHostnameVerification() {
    val fixture = gatewayTlsTestFixture()
    MockWebServer().use { server ->
      server.useHttps(fixture.socketFactory, false)
      server.start()
      val wrongHost =
        server
          .url("/")
          .newBuilder()
          .host("127.0.0.1")
          .build()
      val client = createCallHttpClient(fixture.fingerprint).newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
      assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(wrongHost).build()).execute().close() }
      assertEquals(0, server.requestCount)
    }
  }

  @Test
  fun expiredPinnedCertificateCannotSendRequests() {
    val fixture = gatewayTlsTestFixture(validForMillis = -1_000)
    MockWebServer().use { server ->
      server.useHttps(fixture.socketFactory, false)
      server.start()
      val client = createCallHttpClient(fixture.fingerprint).newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
      assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(server.url("/")).build()).execute().close() }
      assertEquals(0, server.requestCount)
    }
  }
}
