package okhttp.android.test

import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import assertk.assertThat
import assertk.assertions.hasMessage
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.platform.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.parallel.Isolated
import org.opentest4j.AssertionFailedError

@Isolated
class StrictModeTest {
  @AfterEach
  fun cleanup() {
    StrictMode.setThreadPolicy(
      ThreadPolicy.Builder()
        .permitAll()
        .build(),
    )
  }

  @Test
  fun testInit() {
    Platform.resetForTests()

    applyStrictMode()

    val e =
      assertThrows<AssertionFailedError> {
        // Not currently safe
        // See https://github.com/square/okhttp/pull/8248
        OkHttpClient()
      }
    assertThat(e).hasMessage("Slow call on main")
  }

  @Test
  fun testNewCall() {
    Platform.resetForTests()

    val client = OkHttpClient()

    applyStrictMode()

    // Safe on main
    client.newCall(Request("https://google.com/robots.txt".toHttpUrl()))
  }

  private fun applyStrictMode() {
    StrictMode.setThreadPolicy(
      ThreadPolicy.Builder()
        .detectCustomSlowCalls()
        .penaltyListener({ it.run() }) {
          fail("Slow call on main")
        }
        .build(),
    )
  }
}
