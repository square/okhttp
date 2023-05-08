@file:OptIn(ExperimentalCoroutinesApi::class)

package okhttp3

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.connection.RealCall
import org.junit.jupiter.api.Test

class TwoRequestTest {
  val file = "https://storage.googleapis.com/downloads.webmproject.org/av1/exoplayer/bbb-av1-480p.mp4".toHttpUrl()

  val client = OkHttpClient.Builder()
    .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
    .build()

  val extraRequests = false
  val stalledRequest = true

  @Test
  fun testTwoQueries() = runTest {
    if (extraRequests) {
      val callFull = client.newCall(
        Request(file, Headers.headersOf("Icy-MetaData", "1", "Accept-Encoding", "identity"))
      )
      callFull.executeAsync()

      callFull.cancel()

      val callEnd = client.newCall(
        Request(file, Headers.headersOf("Range", "bytes=37070547-", "Icy-MetaData", "1", "Accept-Encoding", "identity"))
      )
      callEnd.executeAsync()
      callEnd.cancel()
    }

    if (stalledRequest) {
      val callStart = client.newCall(
        Request(file, Headers.headersOf("Range", "bytes=44-", "Icy-MetaData", "1", "Accept-Encoding", "identity"))
      )
      val responseStart = callStart.executeAsync()
      val bodyStart = responseStart.body
      bodyStart.byteStream().readNBytes(100_000)
    }

    val callDownload = client.newCall(Request(file)) as RealCall
    val responseDownload = callDownload.executeAsync()
    val bodyDownload = responseDownload.body

    val connectionFlowControl = callDownload.connectionFlowControl!!
    val streamFlowControl = callDownload.streamFlowControl!!

    while (true) {
      val bytes = bodyDownload.byteStream().readNBytes(100_000)

      println("$connectionFlowControl $streamFlowControl")

      if (bytes.isEmpty()) {
        break
      }
    }
  }
}
