package okhttp3

import assertk.assertThat
import assertk.assertions.matches
import org.junit.jupiter.api.Test

class OkHttpTest {
  @Test
  fun testVersion() {
    assertThat(OkHttp.VERSION).matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(-.+)?"))
  }
}
