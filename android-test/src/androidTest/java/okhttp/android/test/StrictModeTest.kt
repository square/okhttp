package okhttp.android.test

import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.strictmode.Violation
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.platform.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@Isolated
class StrictModeTest {
  private val violations = mutableListOf<Violation>()

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

    // Not currently safe
    // See https://github.com/square/okhttp/pull/8248
    OkHttpClient()

    assertThat(violations).hasSize(1)
    assertThat(violations[0].message).isEqualTo("newSSLContext")
  }

  @Test
  fun testNewCall() {
    Platform.resetForTests()

    val client = OkHttpClient()

    applyStrictMode()

    // Safe on main
    client.newCall(Request("https://google.com/robots.txt".toHttpUrl()))

    assertThat(violations).isEmpty()
  }

  private fun applyStrictMode() {
    StrictMode.setThreadPolicy(
      ThreadPolicy.Builder()
        .detectCustomSlowCalls()
        .penaltyListener({ it.run() }) {
          violations.add(it)
        }
        .build(),
    )
  }
}
