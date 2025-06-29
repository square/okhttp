package artifacttests

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.exactly
import assertk.assertions.isEqualTo
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

class DependsOnLdAndLatestTest {
  @Test
  fun createAndUseOkHttpClient() {
    val client = OkHttpClient()
    val call = client.newCall(
      Request(
        url = "https://squareup.com/".toHttpUrl(),
      )
    )
    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.body.string()).contains("Square")
    }
  }
}
