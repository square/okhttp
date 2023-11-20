/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import java.net.CookieManager
import java.net.ResponseCache
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.internal.buildCache
import okhttp3.java.net.cookiejar.JavaNetCookieJar
import okhttp3.okio.LoggingFilesystem
import okhttp3.testing.PlatformRule
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class CacheCorruptionTest {
  var fileSystem = FakeFileSystem()

  @JvmField
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @JvmField
  @RegisterExtension
  val platform = PlatformRule()

  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private lateinit var client: OkHttpClient
  private lateinit var cache: Cache
  private val NULL_HOSTNAME_VERIFIER = HostnameVerifier { _: String?, _: SSLSession? -> true }
  private val cookieManager = CookieManager()
  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server

    platform.assumeNotOpenJSSE()
    server.protocolNegotiationEnabled = false
    val loggingFileSystem = LoggingFilesystem(fileSystem)
    cache = buildCache("/cache/".toPath(), Int.MAX_VALUE.toLong(), loggingFileSystem)
    client = clientTestRule.newClientBuilder()
      .cache(cache)
      .cookieJar(JavaNetCookieJar(cookieManager))
      .build()
  }

  @AfterEach
  fun tearDown() {
    ResponseCache.setDefault(null)
    if (this::cache.isInitialized) {
      cache.delete()
    }
  }

  @Test
  fun corruptedCipher() {
    val response = testCorruptingCache {
      corruptMetadata {
        // mess with cipher suite
        it.replace("TLS_", "SLT_")
      }
    }

    assertThat(response.body.string()).isEqualTo("ABC.1") // cached
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(1)

    assertThat(response.handshake?.cipherSuite?.javaName).startsWith("SLT_")
  }

  @Test
  fun truncatedMetadataEntry() {
    val response = testCorruptingCache {
      corruptMetadata {
        // truncate metadata to 1/4 of length
        it.substring(0, it.length / 4)
      }
    }

    assertThat(response.body.string()).isEqualTo("ABC.2") // not cached
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.networkCount()).isEqualTo(2)
    assertThat(cache.hitCount()).isEqualTo(0)
  }

  @Test fun corruptedUrl() {
    val response = testCorruptingCache {
      corruptMetadata {
        // strip https scheme
        it.substring(5)
      }
    }

    assertThat(response.body.string()).isEqualTo("ABC.2") // not cached
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.networkCount()).isEqualTo(2)
    assertThat(cache.hitCount()).isEqualTo(0)
  }

  private fun corruptMetadata(corruptor: (String) -> String) {
    val metadataFile = fileSystem.allPaths.find {
      it.name.endsWith(".0")
    }

    if (metadataFile != null) {
      val contents = fileSystem.read(metadataFile) {
        readUtf8()
      }

      fileSystem.write(metadataFile) {
        writeUtf8(corruptor(contents))
      }
    }
  }

  private fun testCorruptingCache(corruptor: () -> Unit): Response {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse(
        headers = headersOf(
          "Last-Modified", formatDate(-1, TimeUnit.HOURS)!!,
          "Expires", formatDate(1, TimeUnit.HOURS)!!,
        ),
        body = "ABC.1",
      )
    )
    server.enqueue(
      MockResponse(
        headers = headersOf(
          "Last-Modified", formatDate(-1, TimeUnit.HOURS)!!,
          "Expires", formatDate(1, TimeUnit.HOURS)!!,
        ),
        body = "ABC.2",
      )
    )
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager
      )
      .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
      .build()
    val request = Request(server.url("/"))
    val response1: Response = client.newCall(request).execute()
    val bodySource = response1.body.source()
    assertThat(bodySource.readUtf8()).isEqualTo("ABC.1")

    corruptor()

    return client.newCall(request).execute()
  }

  /**
   * @param delta the offset from the current date to use. Negative values yield dates in the past;
   * positive values yield dates in the future.
   */
  private fun formatDate(delta: Long, timeUnit: TimeUnit): String? {
    return formatDate(Date(System.currentTimeMillis() + timeUnit.toMillis(delta)))
  }

  private fun formatDate(date: Date): String? {
    val rfc1123: DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    rfc1123.timeZone = TimeZone.getTimeZone("GMT")
    return rfc1123.format(date)
  }
}
