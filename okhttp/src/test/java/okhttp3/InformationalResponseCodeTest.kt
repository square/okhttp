package okhttp3

import okhttp3.testing.PlatformRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

@Ignore("For manual testing only")
class InformationalResponseCodeTest {
  @JvmField @Rule
  val platform = PlatformRule()

  @JvmField @Rule val clientTestRule = OkHttpClientTestRule().apply {
    recordFrames = true
  }

  private var client = clientTestRule.newClient()

  @Test
  fun test103() {
    // Enable curl so cloudflare will send a 103
    val request = Request.Builder()
      .url("https://tradingstrategy.ai")
      .header("user-agent", "curl/7.85.0")
      .build()

    val response = client.newCall(request).execute()

    assertThat(response.code).isEqualTo(200)
    assertThat(response.protocol).isEqualTo(Protocol.HTTP_2)
    response.close()

    val outgoingHeaders = ">> 0x00000003\\s+\\d+\\s+HEADERS\\s+END_STREAM\\|END_HEADERS".toRegex()
    assertThat(clientTestRule.eventsList().filter { it.matches(outgoingHeaders) }).hasSize(1)

    // Confirm we get the informational response and final response headers.
    val incomingHeaders = "<< 0x00000003\\s+\\d+\\s+HEADERS\\s+END_HEADERS".toRegex()
    assertThat(clientTestRule.eventsList().filter { it.matches(incomingHeaders) }).hasSize(2)
  }
}