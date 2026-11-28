package ai.openclaw.app.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CallSettingsTest {
  @Test
  fun supportsMagicDnsAndExplicitPrivatePorts() {
    assertEquals("https://tablet.tailnet.ts.net:19190", CallSettings.normalizeEndpoint(" https://tablet.tailnet.ts.net:19190/ "))
    assertEquals("https://100.100.100.100:19190", CallSettings.normalizeEndpoint("https://100.100.100.100:19190"))
  }

  @Test
  fun rejectsCredentialsCleartextAndUnexpectedPaths() {
    listOf("http://tablet:19190", "https://user:secret@tablet", "https://tablet/rpc", "https://tablet/?token=secret", "https://tablet/#secret", "not a host").forEach { endpoint ->
      assertThrows(IllegalArgumentException::class.java) { CallSettings.normalizeEndpoint(endpoint) }
    }
  }
}
