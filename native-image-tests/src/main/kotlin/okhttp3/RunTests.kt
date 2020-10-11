package okhttp3

import okhttp3.sse.internal.EventSourceHttpTest
import okhttp3.test.graal.JunitTestRegisterer
import org.junit.platform.console.ConsoleLauncher

fun main(vararg args: String) {
  val test = EventSourceHttpTest()

  val args = if (args.isEmpty()) {
    listOf("--select-class", "okhttp3.sse.internal.EventSourceHttpTest")
  } else {
    args.toList()
  }

  JunitTestRegisterer().discoverTests()

  ConsoleLauncher.main(*args.toTypedArray())
}