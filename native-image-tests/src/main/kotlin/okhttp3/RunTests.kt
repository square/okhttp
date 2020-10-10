package okhttp3

import okhttp3.sse.internal.EventSourceHttpTest
import org.junit.platform.console.ConsoleLauncher

fun main() {
  val x = EventSourceHttpTest()
  ConsoleLauncher.main("--select-class", "okhttp3.sse.internal.EventSourceHttpTest")
}