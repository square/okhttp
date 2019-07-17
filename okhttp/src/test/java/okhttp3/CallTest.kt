/*
 * Copyright (C) 2013 Square, Inc.
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

import okhttp3.CipherSuite.Companion.TLS_DH_anon_WITH_AES_128_GCM_SHA256
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RecordingEventListener.CallEnd
import okhttp3.RecordingEventListener.ConnectionAcquired
import okhttp3.RecordingEventListener.ConnectionReleased
import okhttp3.RecordingEventListener.ResponseFailed
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.TestUtil.awaitGarbageCollection
import okhttp3.internal.DoubleInetAddressDns
import okhttp3.internal.RecordingOkAuthenticator
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.http.RecordingProxySelector
import okhttp3.internal.io.InMemoryFileSystem
import okhttp3.internal.userAgent
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.internal.TlsUtil.localhost
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSource
import okio.GzipSink
import okio.buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.CookieManager
import java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.ArrayList
import java.util.Arrays
import java.util.LinkedHashSet
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

@Suppress("SameParameterValue")
class CallTest {
  @Rule val platform = PlatformRule()
  @Rule val timeout: TestRule = Timeout(30000, TimeUnit.MILLISECONDS)
  @Rule val server = MockWebServer()
  @Rule val server2 = MockWebServer()
  @Rule val fileSystem = InMemoryFileSystem()
  @Rule val clientTestRule = OkHttpClientTestRule()

  private var listener = RecordingEventListener()
  private val handshakeCertificates = localhost()
  private lateinit var client: OkHttpClient
  private val callback = RecordingCallback()
  private val logHandler = TestLogHandler()
  private val cache = Cache(File("/cache/"), Integer.MAX_VALUE.toLong(), fileSystem)
  private val logger = Logger.getLogger(OkHttpClient::class.java.name)

  @Before fun setUp() {
    logger.addHandler(logHandler)
    client = clientTestRule.newClientBuilder()
        .eventListener(listener)
        .build()
  }

  @After @Throws(Exception::class)
  fun tearDown() {
    cache.delete()
    logger.removeHandler(logHandler)
  }

  @Test
  fun get() {
    server.enqueue(MockResponse()
        .setBody("abc")
        .clearHeaders()
        .addHeader("content-type: text/plain")
        .addHeader("content-length", "3"))

    val sentAt = System.currentTimeMillis()
    val recordedResponse = executeSynchronously("/", "User-Agent", "SyncApiTest")
    val receivedAt = System.currentTimeMillis()

    recordedResponse.assertCode(200)
        .assertSuccessful()
        .assertHeaders(Headers.Builder()
            .add("content-type", "text/plain")
            .add("content-length", "3")
            .build())
        .assertBody("abc")
        .assertSentRequestAtMillis(sentAt, receivedAt)
        .assertReceivedResponseAtMillis(sentAt, receivedAt)

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("GET")
    assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("SyncApiTest")
    assertThat(recordedRequest.body.size).isEqualTo(0)
    assertThat(recordedRequest.getHeader("Content-Length")).isNull()
  }

  @Test
  fun buildRequestUsingHttpUrl() {
    server.enqueue(MockResponse())
    executeSynchronously("/").assertSuccessful()
  }

  @Test
  fun invalidScheme() {
    val requestBuilder = Request.Builder()
    try {
      requestBuilder.url("ftp://hostname/path")
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo(
          "Expected URL scheme 'http' or 'https' but was 'ftp'")
    }
  }

  @Test
  fun invalidPort() {
    val requestBuilder = Request.Builder()
    try {
      requestBuilder.url("http://localhost:65536/")
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo("Invalid URL port: \"65536\"")
    }
  }

  @Test
  fun getReturns500() {
    server.enqueue(MockResponse().setResponseCode(500))
    executeSynchronously("/")
        .assertCode(500)
        .assertNotSuccessful()
  }

  @Test
  fun get_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    get()
  }

  @Test
  fun get_HTTPS() {
    enableTls()
    get()
  }

  @Test
  fun repeatedHeaderNames() {
    server.enqueue(MockResponse()
        .addHeader("B", "123")
        .addHeader("B", "234"))

    executeSynchronously("/", "A", "345", "A", "456")
        .assertCode(200)
        .assertHeader("B", "123", "234")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.headers.values("A")).containsExactly("345", "456")
  }

  @Test
  fun repeatedHeaderNames_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    repeatedHeaderNames()
  }

  @Test
  fun getWithRequestBody() {
    server.enqueue(MockResponse())

    try {
      Request.Builder().method("GET", "abc".toRequestBody("text/plain".toMediaType()))
      fail()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test
  fun head() {
    server.enqueue(MockResponse().addHeader("Content-Type: text/plain"))

    val request = Request.Builder()
        .url(server.url("/"))
        .head()
        .header("User-Agent", "SyncApiTest")
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("HEAD")
    assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("SyncApiTest")
    assertThat(recordedRequest.body.size).isEqualTo(0)
    assertThat(recordedRequest.getHeader("Content-Length")).isNull()
  }

  @Test
  fun headResponseContentLengthIsIgnored() {
    server.enqueue(MockResponse()
        .clearHeaders()
        .addHeader("Content-Length", "100"))
    server.enqueue(MockResponse()
        .setBody("abc"))

    val headRequest = Request.Builder()
        .url(server.url("/"))
        .head()
        .build()
    val response = client.newCall(headRequest).execute()
    assertThat(response.code).isEqualTo(200)
    assertArrayEquals(ByteArray(0), response.body!!.bytes())

    val getRequest = Request.Builder()
        .url(server.url("/"))
        .build()
    executeSynchronously(getRequest)
        .assertCode(200)
        .assertBody("abc")

    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @Test
  fun headResponseContentEncodingIsIgnored() {
    server.enqueue(MockResponse()
        .clearHeaders()
        .addHeader("Content-Encoding", "chunked"))
    server.enqueue(MockResponse()
        .setBody("abc"))

    val headRequest = Request.Builder()
        .url(server.url("/"))
        .head()
        .build()
    executeSynchronously(headRequest)
        .assertCode(200)
        .assertHeader("Content-Encoding", "chunked")
        .assertBody("")

    val getRequest = Request.Builder()
        .url(server.url("/"))
        .build()
    executeSynchronously(getRequest)
        .assertCode(200)
        .assertBody("abc")

    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @Test
  fun head_HTTPS() {
    enableTls()
    head()
  }

  @Test
  fun head_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    head()
  }

  @Test
  fun post() {
    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
        .url(server.url("/"))
        .post("def".toRequestBody("text/plain".toMediaType()))
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("POST")
    assertThat(recordedRequest.body.readUtf8()).isEqualTo("def")
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3")
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo(
        "text/plain; charset=utf-8")
  }

  @Test
  fun post_HTTPS() {
    enableTls()
    post()
  }

  @Test
  fun post_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    post()
  }

  @Test
  fun postZeroLength() {
    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
        .url(server.url("/"))
        .method("POST", ByteArray(0).toRequestBody(null, 0, 0))
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("POST")
    assertThat(recordedRequest.body.size).isEqualTo(0)
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("0")
    assertThat(recordedRequest.getHeader("Content-Type")).isNull()
  }

  @Test
  fun postZerolength_HTTPS() {
    enableTls()
    postZeroLength()
  }

  @Test
  fun postZerolength_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    postZeroLength()
  }

  @Test
  fun postBodyRetransmittedAfterAuthorizationFail() {
    postBodyRetransmittedAfterAuthorizationFail("abc")
  }

  @Test
  fun postBodyRetransmittedAfterAuthorizationFail_HTTPS() {
    enableTls()
    postBodyRetransmittedAfterAuthorizationFail("abc")
  }

  @Test
  fun postBodyRetransmittedAfterAuthorizationFail_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    postBodyRetransmittedAfterAuthorizationFail("abc")
  }

  /** Don't explode when resending an empty post. https://github.com/square/okhttp/issues/1131  */
  @Test
  fun postEmptyBodyRetransmittedAfterAuthorizationFail() {
    postBodyRetransmittedAfterAuthorizationFail("")
  }

  @Test
  fun postEmptyBodyRetransmittedAfterAuthorizationFail_HTTPS() {
    enableTls()
    postBodyRetransmittedAfterAuthorizationFail("")
  }

  @Test
  fun postEmptyBodyRetransmittedAfterAuthorizationFail_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    postBodyRetransmittedAfterAuthorizationFail("")
  }

  @Throws(Exception::class)
  private fun postBodyRetransmittedAfterAuthorizationFail(body: String) {
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse())

    val request = Request.Builder()
        .url(server.url("/"))
        .method("POST", body.toRequestBody(null))
        .build()

    val credential = Credentials.basic("jesse", "secret")
    client = client.newBuilder()
        .authenticator(RecordingOkAuthenticator(credential, null))
        .build()

    val response = client.newCall(request).execute()
    assertThat(response.code).isEqualTo(200)
    response.body!!.close()

    val recordedRequest1 = server.takeRequest()
    assertThat(recordedRequest1.method).isEqualTo("POST")
    assertThat(recordedRequest1.body.readUtf8()).isEqualTo(body)
    assertThat(recordedRequest1.getHeader("Authorization")).isNull()

    val recordedRequest2 = server.takeRequest()
    assertThat(recordedRequest2.method).isEqualTo("POST")
    assertThat(recordedRequest2.body.readUtf8()).isEqualTo(body)
    assertThat(recordedRequest2.getHeader("Authorization")).isEqualTo(credential)
  }

  @Test
  fun attemptAuthorization20Times() {
    for (i in 0..19) {
      server.enqueue(MockResponse().setResponseCode(401))
    }
    server.enqueue(MockResponse().setBody("Success!"))

    val credential = Credentials.basic("jesse", "secret")
    client = client.newBuilder()
        .authenticator(RecordingOkAuthenticator(credential, null))
        .build()

    executeSynchronously("/")
        .assertCode(200)
        .assertBody("Success!")
  }

  @Test
  fun doesNotAttemptAuthorization21Times() {
    for (i in 0..20) {
      server.enqueue(MockResponse().setResponseCode(401))
    }

    val credential = Credentials.basic("jesse", "secret")
    client = client.newBuilder()
        .authenticator(RecordingOkAuthenticator(credential, null))
        .build()

    try {
      client.newCall(Request.Builder().url(server.url("/0")).build()).execute()
      fail()
    } catch (expected: IOException) {
      assertThat(expected.message).isEqualTo("Too many follow-up requests: 21")
    }
  }

  /**
   * We had a bug where we were passing a null route to the authenticator.
   * https://github.com/square/okhttp/issues/3809
   */
  @Test
  fun authenticateWithNoConnection() {
    val responseCode = MockResponse()
        .addHeader("Connection: close")
        .setResponseCode(401)
    server.enqueue(
        responseCode.apply { responseCode.socketPolicy = SocketPolicy.DISCONNECT_AT_END })

    val authenticator = RecordingOkAuthenticator(null, null)

    client = client.newBuilder()
        .authenticator(authenticator)
        .build()

    executeSynchronously("/")
        .assertCode(401)

    assertThat(authenticator.onlyRoute()).isNotNull
  }

  @Test
  fun delete() {
    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
        .url(server.url("/"))
        .delete()
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("DELETE")
    assertThat(recordedRequest.body.size).isEqualTo(0)
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("0")
    assertThat(recordedRequest.getHeader("Content-Type")).isNull()
  }

  @Test
  fun delete_HTTPS() {
    enableTls()
    delete()
  }

  @Test
  fun delete_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    delete()
  }

  @Test
  fun deleteWithRequestBody() {
    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
        .url(server.url("/"))
        .method("DELETE", "def".toRequestBody("text/plain".toMediaType()))
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("DELETE")
    assertThat(recordedRequest.body.readUtf8()).isEqualTo("def")
  }

  @Test
  fun put() {
    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
        .url(server.url("/"))
        .put("def".toRequestBody("text/plain".toMediaType()))
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("PUT")
    assertThat(recordedRequest.body.readUtf8()).isEqualTo("def")
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3")
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo(
        "text/plain; charset=utf-8")
  }

  @Test
  fun put_HTTPS() {
    enableTls()
    put()
  }

  @Test
  fun put_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    put()
  }

  @Test
  fun patch() {
    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
        .url(server.url("/"))
        .patch("def".toRequestBody("text/plain".toMediaType()))
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("PATCH")
    assertThat(recordedRequest.body.readUtf8()).isEqualTo("def")
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3")
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo(
        "text/plain; charset=utf-8")
  }

  @Test
  fun patch_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    patch()
  }

  @Test
  fun patch_HTTPS() {
    enableTls()
    patch()
  }

  @Test
  fun customMethodWithBody() {
    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
        .url(server.url("/"))
        .method("CUSTOM", "def".toRequestBody("text/plain".toMediaType()))
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("CUSTOM")
    assertThat(recordedRequest.body.readUtf8()).isEqualTo("def")
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3")
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo(
        "text/plain; charset=utf-8")
  }

  @Test
  fun unspecifiedRequestBodyContentTypeDoesNotGetDefault() {
    server.enqueue(MockResponse())

    val request = Request.Builder()
        .url(server.url("/"))
        .method("POST", "abc".toRequestBody(null))
        .build()

    executeSynchronously(request).assertCode(200)

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.getHeader("Content-Type")).isNull()
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3")
    assertThat(recordedRequest.body.readUtf8()).isEqualTo("abc")
  }

  @Test
  fun illegalToExecuteTwice() {
    server.enqueue(MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"))

    val request = Request.Builder()
        .url(server.url("/"))
        .header("User-Agent", "SyncApiTest")
        .build()

    val call = client.newCall(request)
    val response = call.execute()
    response.body!!.close()

    try {
      call.execute()
      fail()
    } catch (e: IllegalStateException) {
      assertThat(e.message).isEqualTo("Already Executed")
    }

    try {
      call.enqueue(callback)
      fail()
    } catch (e: IllegalStateException) {
      assertThat(e.message).isEqualTo("Already Executed")
    }

    assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("SyncApiTest")
  }

  @Test
  fun illegalToExecuteTwice_Async() {
    server.enqueue(MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"))

    val request = Request.Builder()
        .url(server.url("/"))
        .header("User-Agent", "SyncApiTest")
        .build()

    val call = client.newCall(request)
    call.enqueue(callback)

    try {
      call.execute()
      fail()
    } catch (e: IllegalStateException) {
      assertThat(e.message).isEqualTo("Already Executed")
    }

    try {
      call.enqueue(callback)
      fail()
    } catch (e: IllegalStateException) {
      assertThat(e.message).isEqualTo("Already Executed")
    }

    assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("SyncApiTest")

    callback.await(request.url).assertSuccessful()
  }

  @Test
  fun legalToExecuteTwiceCloning() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    val call = client.newCall(request)
    val response1 = call.execute()

    val cloned = call.clone()
    val response2 = cloned.execute()

    assertThat("abc").isEqualTo(response1.body!!.string())
    assertThat("def").isEqualTo(response2.body!!.string())
  }

  @Test
  fun legalToExecuteTwiceCloning_Async() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    val call = client.newCall(request)
    call.enqueue(callback)

    val cloned = call.clone()
    cloned.enqueue(callback)

    val firstResponse = callback.await(request.url).assertSuccessful()
    val secondResponse = callback.await(request.url).assertSuccessful()

    val bodies = LinkedHashSet<String>()
    bodies.add(firstResponse.getBody())
    bodies.add(secondResponse.getBody())

    assertThat(bodies).contains("abc")
    assertThat(bodies).contains("def")
  }

  @Test
  fun get_Async() {
    server.enqueue(MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"))

    val request = Request.Builder()
        .url(server.url("/"))
        .header("User-Agent", "AsyncApiTest")
        .build()
    client.newCall(request).enqueue(callback)

    callback.await(request.url)
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain")
        .assertBody("abc")

    assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("AsyncApiTest")
  }

  @Test
  fun exceptionThrownByOnResponseIsRedactedAndLogged() {
    server.enqueue(MockResponse())

    val request = Request.Builder()
        .url(server.url("/secret"))
        .build()

    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        fail()
      }

      @Throws(IOException::class)
      override fun onResponse(call: Call, response: Response) {
        throw IOException("a")
      }
    })

    assertThat(logHandler.take()).isEqualTo(
        "INFO: Callback failure for call to " + server.url("/") + "...")
  }

  @Test
  fun connectionPooling() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def"))
    server.enqueue(MockResponse().setBody("ghi"))

    executeSynchronously("/a").assertBody("abc")
    executeSynchronously("/b").assertBody("def")
    executeSynchronously("/c").assertBody("ghi")

    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(2)
  }

  @Test
  fun connectionPooling_Async() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def"))
    server.enqueue(MockResponse().setBody("ghi"))

    client.newCall(Request.Builder().url(server.url("/a")).build()).enqueue(callback)
    callback.await(server.url("/a")).assertBody("abc")

    client.newCall(Request.Builder().url(server.url("/b")).build()).enqueue(callback)
    callback.await(server.url("/b")).assertBody("def")

    client.newCall(Request.Builder().url(server.url("/c")).build()).enqueue(callback)
    callback.await(server.url("/c")).assertBody("ghi")

    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(2)
  }

  @Test
  fun connectionReuseWhenResponseBodyConsumed_Async() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def"))

    val request = Request.Builder().url(server.url("/a")).build()
    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        throw AssertionError()
      }

      @Throws(IOException::class)
      override fun onResponse(call: Call, response: Response) {
        val bytes = response.body!!.byteStream()
        assertThat(bytes.read()).isEqualTo('a')
        assertThat(bytes.read()).isEqualTo('b')
        assertThat(bytes.read()).isEqualTo('c')

        // This request will share a connection with 'A' cause it's all done.
        client.newCall(Request.Builder().url(server.url("/b")).build()).enqueue(callback)
      }
    })

    callback.await(server.url("/b")).assertCode(200).assertBody("def")
    // New connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    // Connection reuse!
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @Test
  fun timeoutsUpdatedOnReusedConnections() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS))

    // First request: time out after 1000ms.
    client = client.newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build()
    executeSynchronously("/a").assertBody("abc")

    // Second request: time out after 250ms.
    client = client.newBuilder()
        .readTimeout(250, TimeUnit.MILLISECONDS)
        .build()
    val request = Request.Builder().url(server.url("/b")).build()
    val response = client.newCall(request).execute()
    val bodySource = response.body!!.source()
    assertThat(bodySource.readByte()).isEqualTo('d'.toByte())

    // The second byte of this request will be delayed by 750ms so we should time out after 250ms.
    val startNanos = System.nanoTime()
    try {
      bodySource.readByte()
      fail()
    } catch (expected: IOException) {
      // Timed out as expected.
      val elapsedNanos = System.nanoTime() - startNanos
      val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
      assertThat(elapsedMillis).isLessThan(500)
    } finally {
      bodySource.close()
    }
  }

  /** https://github.com/square/okhttp/issues/442  */
  @Test
  fun tlsTimeoutsNotRetried() {
    enableTls()
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.NO_RESPONSE })
    server.enqueue(MockResponse()
        .setBody("unreachable!"))

    client = client.newBuilder()
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .build()

    val request = Request.Builder().url(server.url("/")).build()
    try {
      // If this succeeds, too many requests were made.
      client.newCall(request).execute()
      fail()
    } catch (expected: InterruptedIOException) {
    }
  }

  /**
   * Make a request with two routes. The first route will time out because it's connecting to a
   * special address that never connects. The automatic retry will succeed.
   */
  @Test
  fun connectTimeoutsAttemptsAlternateRoute() {
    val proxySelector = RecordingProxySelector()
    proxySelector.proxies.add(Proxy(Proxy.Type.HTTP, TestUtil.UNREACHABLE_ADDRESS))
    proxySelector.proxies.add(server.toProxyAddress())

    server.enqueue(MockResponse()
        .setBody("success!"))

    client = client.newBuilder()
        .proxySelector(proxySelector)
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .connectTimeout(100, TimeUnit.MILLISECONDS)
        .build()

    val request = Request.Builder().url("http://android.com/").build()
    executeSynchronously(request)
        .assertCode(200)
        .assertBody("success!")
  }

  /** https://github.com/square/okhttp/issues/4875  */
  @Test
  fun interceptorRecoversWhenRoutesExhausted() {
    val mockResponse = MockResponse()
    server.enqueue(
        mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AT_START })
    server.enqueue(MockResponse())

    client = client.newBuilder()
        .addInterceptor(object : Interceptor {
          @Throws(IOException::class)
          override fun intercept(chain: Interceptor.Chain): Response {
            try {
              chain.proceed(chain.request())
              throw AssertionError()
            } catch (expected: IOException) {
              return chain.proceed(chain.request())
            }
          }
        })
        .build()

    val request = Request.Builder()
        .url(server.url("/"))
        .build()
    executeSynchronously(request)
        .assertCode(200)
  }

  /** https://github.com/square/okhttp/issues/4761  */
  @Test
  fun interceptorCallsProceedWithoutClosingPriorResponse() {
    server.enqueue(MockResponse()
        .setBody("abc"))
    server.enqueue(MockResponse())

    client = client.newBuilder()
        .addInterceptor(object : Interceptor {
          @Throws(IOException::class)
          override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            try {
              chain.proceed(chain.request())
              fail()
            } catch (expected: IllegalStateException) {
              assertThat(expected).hasMessageContaining("please call response.close()")
            }

            return response
          }
        })
        .build()

    val request = Request.Builder()
        .url(server.url("/"))
        .build()
    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc")
  }

  /**
   * Make a request with two routes. The first route will fail because the null server connects but
   * never responds. The manual retry will succeed.
   */
  @Test
  fun readTimeoutFails() {
    val mockResponse = MockResponse()
    server.enqueue(
        mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.STALL_SOCKET_AT_START })
    server2.enqueue(MockResponse()
        .setBody("success!"))

    val proxySelector = RecordingProxySelector()
    proxySelector.proxies.add(server.toProxyAddress())
    proxySelector.proxies.add(server2.toProxyAddress())

    client = client.newBuilder()
        .proxySelector(proxySelector)
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .build()

    val request = Request.Builder().url("http://android.com/").build()
    executeSynchronously(request)
        .assertFailure(SocketTimeoutException::class.java)
    executeSynchronously(request)
        .assertCode(200)
        .assertBody("success!")
  }

  /** https://github.com/square/okhttp/issues/1801  */
  @Test
  fun asyncCallEngineInitialized() {
    val c = client.newBuilder()
        .addInterceptor { throw IOException() }
        .build()
    val request = Request.Builder().url(server.url("/")).build()
    c.newCall(request).enqueue(callback)
    val response = callback.await(request.url)
    assertThat(response.request).isEqualTo(request)
  }

  @Test
  fun reusedSinksGetIndependentTimeoutInstances() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())

    // Call 1: set a deadline on the request body.
    val requestBody1 = object : RequestBody() {
      override fun contentType(): MediaType {
        return "text/plain".toMediaType()
      }

      @Throws(IOException::class)
      override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("abc")
        sink.timeout().deadline(5, TimeUnit.SECONDS)
      }
    }
    val request1 = Request.Builder()
        .url(server.url("/"))
        .method("POST", requestBody1)
        .build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.code).isEqualTo(200)

    // Call 2: check for the absence of a deadline on the request body.
    val requestBody2 = object : RequestBody() {
      override fun contentType(): MediaType {
        return "text/plain".toMediaType()
      }

      @Throws(IOException::class)
      override fun writeTo(sink: BufferedSink) {
        assertThat(sink.timeout().hasDeadline()).isFalse
        sink.writeUtf8("def")
      }
    }
    val request2 = Request.Builder()
        .url(server.url("/"))
        .method("POST", requestBody2)
        .build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.code).isEqualTo(200)

    // Use sequence numbers to confirm the connection was pooled.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @Test
  fun reusedSourcesGetIndependentTimeoutInstances() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def"))

    // Call 1: set a deadline on the response body.
    val request1 = Request.Builder().url(server.url("/")).build()
    val response1 = client.newCall(request1).execute()
    val body1 = response1.body!!.source()
    assertThat(body1.readUtf8()).isEqualTo("abc")
    body1.timeout().deadline(5, TimeUnit.SECONDS)

    // Call 2: check for the absence of a deadline on the request body.
    val request2 = Request.Builder().url(server.url("/")).build()
    val response2 = client.newCall(request2).execute()
    val body2 = response2.body!!.source()
    assertThat(body2.readUtf8()).isEqualTo("def")
    assertThat(body2.timeout().hasDeadline()).isFalse

    // Use sequence numbers to confirm the connection was pooled.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @Test
  fun tls() {
    enableTls()
    server.enqueue(MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"))

    executeSynchronously("/").assertHandshake()
  }

  @Test
  fun tls_Async() {
    enableTls()
    server.enqueue(MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()
    client.newCall(request).enqueue(callback)

    callback.await(request.url).assertHandshake()
  }

  @Test
  fun recoverWhenRetryOnConnectionFailureIsTrue() {
    // Set to 2 because the seeding request will count down before the retried request does.
    val requestFinished = CountDownLatch(2)

    val dispatcher = object : QueueDispatcher() {
      @Throws(InterruptedException::class)
      override fun dispatch(request: RecordedRequest): MockResponse {
        if (peek().socketPolicy === SocketPolicy.DISCONNECT_AFTER_REQUEST) {
          requestFinished.await()
        }
        return super.dispatch(request)
      }
    }
    dispatcher.enqueueResponse(MockResponse().setBody("seed connection pool"))
    val mockResponse = MockResponse()
    dispatcher.enqueueResponse(
        mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AFTER_REQUEST })
    dispatcher.enqueueResponse(MockResponse().setBody("retry success"))
    server.dispatcher = dispatcher

    listener = object : RecordingEventListener() {
      override fun responseHeadersStart(call: Call) {
        requestFinished.countDown()
        super.responseHeadersStart(call)
      }
    }

    client = client.newBuilder()
        .dns(DoubleInetAddressDns())
        .eventListener(listener)
        .build()
    assertThat(client.retryOnConnectionFailure).isTrue

    executeSynchronously("/").assertBody("seed connection pool")
    executeSynchronously("/").assertBody("retry success")

    // The call that seeds the connection pool.
    listener.removeUpToEvent(CallEnd::class.java)

    // The ResponseFailed event is not necessarily fatal!
    listener.removeUpToEvent(ConnectionAcquired::class.java)
    listener.removeUpToEvent(ResponseFailed::class.java)
    listener.removeUpToEvent(ConnectionReleased::class.java)
    listener.removeUpToEvent(ConnectionAcquired::class.java)
    listener.removeUpToEvent(ConnectionReleased::class.java)
    listener.removeUpToEvent(CallEnd::class.java)
  }

  @Test
  fun recoverWhenRetryOnConnectionFailureIsTrue_HTTP2() {
    enableProtocol(Protocol.HTTP_2)
    recoverWhenRetryOnConnectionFailureIsTrue()
  }

  @Test
  fun noRecoverWhenRetryOnConnectionFailureIsFalse() {
    server.enqueue(MockResponse().setBody("seed connection pool"))
    val mockResponse = MockResponse()
    server.enqueue(
        mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AFTER_REQUEST })
    server.enqueue(MockResponse().setBody("unreachable!"))

    client = client.newBuilder()
        .dns(DoubleInetAddressDns())
        .retryOnConnectionFailure(false)
        .build()

    executeSynchronously("/").assertBody("seed connection pool")

    // If this succeeds, too many requests were made.
    executeSynchronously("/")
        .assertFailure(IOException::class.java)
        .assertFailureMatches("stream was reset: CANCEL",
            "unexpected end of stream on " + server.url("/").redact())
  }

  @Test
  fun recoverWhenRetryOnConnectionFailureIsFalse_HTTP2() {
    enableProtocol(Protocol.HTTP_2)
    noRecoverWhenRetryOnConnectionFailureIsFalse()
  }

  @Test
  fun tlsHandshakeFailure_noFallbackByDefault() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.FAIL_HANDSHAKE })
    server.enqueue(MockResponse().setBody("response that will never be received"))
    val response = executeSynchronously("/")
    response.assertFailure(
        SSLException::class.java, // JDK 11 response to the FAIL_HANDSHAKE
        SSLProtocolException::class.java, // RI response to the FAIL_HANDSHAKE
        SSLHandshakeException::class.java // Android's response to the FAIL_HANDSHAKE
    )
    assertThat(client.connectionSpecs).doesNotContain(ConnectionSpec.COMPATIBLE_TLS)
  }

  @Test
  fun recoverFromTlsHandshakeFailure() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.FAIL_HANDSHAKE })
    server.enqueue(MockResponse().setBody("abc"))

    client = client.newBuilder()
        .hostnameVerifier(RecordingHostnameVerifier())
        // Attempt RESTRICTED_TLS then fall back to MODERN_TLS.
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager)
        .build()

    executeSynchronously("/").assertBody("abc")
  }

  @Test
  fun recoverFromTlsHandshakeFailure_tlsFallbackScsvEnabled() {
    platform.assumeNotConscrypt()

    val tlsFallbackScsv = "TLS_FALLBACK_SCSV"
    val supportedCiphers = listOf(*handshakeCertificates.sslSocketFactory().supportedCipherSuites)
    if (!supportedCiphers.contains(tlsFallbackScsv)) {
      // This only works if the client socket supports TLS_FALLBACK_SCSV.
      return
    }

    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.FAIL_HANDSHAKE })

    val clientSocketFactory = RecordingSSLSocketFactory(handshakeCertificates.sslSocketFactory())
    client = client.newBuilder()
        .sslSocketFactory(clientSocketFactory, handshakeCertificates.trustManager)
        // Attempt RESTRICTED_TLS then fall back to MODERN_TLS.
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .hostnameVerifier(RecordingHostnameVerifier())
        .build()

    val request = Request.Builder().url(server.url("/")).build()
    try {
      client.newCall(request).execute()
      fail()
    } catch (expected: SSLHandshakeException) {
    }

    val clientSockets = clientSocketFactory.getSocketsCreated()
    val firstSocket = clientSockets[0]
    assertThat(listOf(*firstSocket.enabledCipherSuites)).doesNotContain(tlsFallbackScsv)
    val secondSocket = clientSockets[1]
    assertThat(listOf(*secondSocket.enabledCipherSuites)).contains(tlsFallbackScsv)
  }

  @Test
  fun recoverFromTlsHandshakeFailure_Async() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.FAIL_HANDSHAKE })
    server.enqueue(MockResponse().setBody("abc"))

    client = client.newBuilder()
        .hostnameVerifier(RecordingHostnameVerifier())
        // Attempt RESTRICTED_TLS then fall back to MODERN_TLS.
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager)
        .build()

    val request = Request.Builder()
        .url(server.url("/"))
        .build()
    client.newCall(request).enqueue(callback)

    callback.await(request.url).assertBody("abc")
  }

  @Test
  fun noRecoveryFromTlsHandshakeFailureWhenTlsFallbackIsDisabled() {
    client = client.newBuilder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        .hostnameVerifier(RecordingHostnameVerifier())
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager)
        .build()

    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.FAIL_HANDSHAKE })

    val request = Request.Builder().url(server.url("/")).build()
    try {
      client.newCall(request).execute()
      fail()
    } catch (expected: SSLProtocolException) {
      // RI response to the FAIL_HANDSHAKE
    } catch (expected: SSLHandshakeException) {
      // Android's response to the FAIL_HANDSHAKE
    } catch (expected: SSLException) {
      // JDK 11 response to the FAIL_HANDSHAKE
      val jvmVersion = System.getProperty("java.specification.version")
      assertThat(jvmVersion).isEqualTo("11")
    }
  }

  @Test
  fun tlsHostnameVerificationFailure() {
    server.enqueue(MockResponse())

    val serverCertificate = HeldCertificate.Builder()
        .commonName("localhost") // Unusued for hostname verification.
        .addSubjectAlternativeName("wronghostname")
        .build()

    val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(serverCertificate)
        .build()

    val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(serverCertificate.certificate)
        .build()

    client = client.newBuilder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()
    server.useHttps(serverCertificates.sslSocketFactory(), false)

    executeSynchronously("/")
        .assertFailureMatches("(?s)Hostname localhost not verified.*")
  }

  /**
   * Anonymous cipher suites were disabled in OpenJDK because they're rarely used and permit
   * man-in-the-middle attacks. https://bugs.openjdk.java.net/browse/JDK-8212823
   */
  @Test
  fun anonCipherSuiteUnsupported() {
    platform.assumeNotConscrypt()

    // The _anon_ suites became unsupported in "1.8.0_201" and "11.0.2".
    assumeFalse(
        System.getProperty("java.version", "unknown").matches("1\\.8\\.0_1\\d\\d".toRegex()))

    server.enqueue(MockResponse())

    val cipherSuite = TLS_DH_anon_WITH_AES_128_GCM_SHA256

    val clientCertificates = HandshakeCertificates.Builder()
        .build()
    client = client.newBuilder()
        .sslSocketFactory(
            socketFactoryWithCipherSuite(clientCertificates.sslSocketFactory(), cipherSuite),
            clientCertificates.trustManager)
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .cipherSuites(cipherSuite)
            .build()))
        .build()

    val serverCertificates = HandshakeCertificates.Builder()
        .build()
    server.useHttps(socketFactoryWithCipherSuite(
        serverCertificates.sslSocketFactory(), cipherSuite), false)

    executeSynchronously("/")
        .assertFailure(SSLHandshakeException::class.java)
  }

  @Test
  fun cleartextCallsFailWhenCleartextIsDisabled() {
    // Configure the client with only TLS configurations. No cleartext!
    client = client.newBuilder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .build()

    server.enqueue(MockResponse())

    val request = Request.Builder().url(server.url("/")).build()
    try {
      client.newCall(request).execute()
      fail()
    } catch (expected: UnknownServiceException) {
      assertThat(expected.message).isEqualTo(
          "CLEARTEXT communication not enabled for client")
    }
  }

  @Test
  fun httpsCallsFailWhenProtocolIsH2PriorKnowledge() {
    client = client.newBuilder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .build()

    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    server.enqueue(MockResponse())

    val call = client.newCall(Request.Builder()
        .url(server.url("/"))
        .build())
    try {
      call.execute()
      fail()
    } catch (expected: UnknownServiceException) {
      assertThat(expected.message).isEqualTo(
          "H2_PRIOR_KNOWLEDGE cannot be used with HTTPS")
    }
  }

  @Test
  fun setFollowSslRedirectsFalse() {
    enableTls()
    server.enqueue(MockResponse()
        .setResponseCode(301)
        .addHeader("Location: http://square.com"))

    client = client.newBuilder()
        .followSslRedirects(false)
        .build()

    val request = Request.Builder().url(server.url("/")).build()
    val response = client.newCall(request).execute()
    assertThat(response.code).isEqualTo(301)
    response.body!!.close()
  }

  @Test
  fun matchingPinnedCertificate() {
    // Fails on 11.0.1 https://github.com/square/okhttp/issues/4703

    enableTls()
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())

    // Make a first request without certificate pinning. Use it to collect certificates to pin.
    val request1 = Request.Builder().url(server.url("/")).build()
    val response1 = client.newCall(request1).execute()
    val certificatePinnerBuilder = CertificatePinner.Builder()
    for (certificate in response1.handshake!!.peerCertificates) {
      certificatePinnerBuilder.add(server.hostName, CertificatePinner.pin(certificate))
    }
    response1.body!!.close()

    // Make another request with certificate pinning. It should complete normally.
    client = client.newBuilder()
        .certificatePinner(certificatePinnerBuilder.build())
        .build()
    val request2 = Request.Builder().url(server.url("/")).build()
    val response2 = client.newCall(request2).execute()
    assertThat(response1.handshake).isNotSameAs(response2.handshake)
    response2.body!!.close()
  }

  @Test
  fun unmatchingPinnedCertificate() {
    enableTls()
    server.enqueue(MockResponse())

    // Pin publicobject.com's cert.
    client = client.newBuilder()
        .certificatePinner(CertificatePinner.Builder()
            .add(server.hostName, "sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw=")
            .build())
        .build()

    // When we pin the wrong certificate, connectivity fails.
    val request = Request.Builder().url(server.url("/")).build()
    try {
      client.newCall(request).execute()
      fail()
    } catch (expected: SSLPeerUnverifiedException) {
      assertThat(expected.message).startsWith("Certificate pinning failure!")
    }
  }

  @Test
  fun post_Async() {
    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
        .url(server.url("/"))
        .post("def".toRequestBody("text/plain".toMediaType()))
        .build()
    client.newCall(request).enqueue(callback)

    callback.await(request.url)
        .assertCode(200)
        .assertBody("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.body.readUtf8()).isEqualTo("def")
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3")
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo(
        "text/plain; charset=utf-8")
  }

  @Test
  fun postBodyRetransmittedOnFailureRecovery() {
    server.enqueue(MockResponse().setBody("abc"))
    val mockResponse = MockResponse()
    server.enqueue(
        mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AFTER_REQUEST })
    server.enqueue(MockResponse().setBody("def"))

    // Seed the connection pool so we have something that can fail.
    val request1 = Request.Builder().url(server.url("/")).build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.body!!.string()).isEqualTo("abc")

    val request2 = Request.Builder()
        .url(server.url("/"))
        .post("body!".toRequestBody("text/plain".toMediaType()))
        .build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.body!!.string()).isEqualTo("def")

    val get = server.takeRequest()
    assertThat(get.sequenceNumber).isEqualTo(0)

    val post1 = server.takeRequest()
    assertThat(post1.body.readUtf8()).isEqualTo("body!")
    assertThat(post1.sequenceNumber).isEqualTo(1)

    val post2 = server.takeRequest()
    assertThat(post2.body.readUtf8()).isEqualTo("body!")
    assertThat(post2.sequenceNumber).isEqualTo(0)
  }

  @Test
  fun postBodyRetransmittedOnFailureRecovery_HTTP2() {
    enableProtocol(Protocol.HTTP_2)
    postBodyRetransmittedOnFailureRecovery()
  }

  @Test
  fun cacheHit() {
    server.enqueue(MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Charset")
        .setBody("A"))

    client = client.newBuilder()
        .cache(cache)
        .build()

    // Store a response in the cache.
    val url = server.url("/")
    val request1SentAt = System.currentTimeMillis()
    executeSynchronously("/", "Accept-Language", "fr-CA", "Accept-Charset", "UTF-8")
        .assertCode(200)
        .assertBody("A")
    val request1ReceivedAt = System.currentTimeMillis()
    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull()

    // Hit that stored response. It's different, but Vary says it doesn't matter.
    Thread.sleep(10) // Make sure the timestamps are unique.
    val cacheHit = executeSynchronously(
        "/", "Accept-Language", "en-US", "Accept-Charset", "UTF-8")

    // Check the merged response. The request is the application's original request.
    cacheHit.assertCode(200)
        .assertBody("A")
        .assertHeaders(Headers.Builder()
            .add("ETag", "v1")
            .add("Cache-Control", "max-age=60")
            .add("Vary", "Accept-Charset")
            .add("Content-Length", "1")
            .build())
        .assertRequestUrl(url)
        .assertRequestHeader("Accept-Language", "en-US")
        .assertRequestHeader("Accept-Charset", "UTF-8")
        .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
        .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt)

    // Check the cached response. Its request contains only the saved Vary headers.
    cacheHit.cacheResponse()
        .assertCode(200)
        .assertHeaders(Headers.Builder()
            .add("ETag", "v1")
            .add("Cache-Control", "max-age=60")
            .add("Vary", "Accept-Charset")
            .add("Content-Length", "1")
            .build())
        .assertRequestMethod("GET")
        .assertRequestUrl(url)
        .assertRequestHeader("Accept-Language")
        .assertRequestHeader("Accept-Charset", "UTF-8")
        .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
        .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt)

    cacheHit.assertNoNetworkResponse()
  }

  @Test
  fun conditionalCacheHit() {
    server.enqueue(MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Vary: Accept-Charset")
        .addHeader("Donut: a")
        .setBody("A"))
    server.enqueue(MockResponse().clearHeaders()
        .addHeader("Donut: b")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED))

    client = client.newBuilder()
        .cache(cache)
        .build()

    // Store a response in the cache.
    val request1SentAt = System.currentTimeMillis()
    executeSynchronously("/", "Accept-Language", "fr-CA", "Accept-Charset", "UTF-8")
        .assertCode(200)
        .assertHeader("Donut", "a")
        .assertBody("A")
    val request1ReceivedAt = System.currentTimeMillis()
    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull()

    // Hit that stored response. It's different, but Vary says it doesn't matter.
    Thread.sleep(10) // Make sure the timestamps are unique.
    val request2SentAt = System.currentTimeMillis()
    val cacheHit = executeSynchronously(
        "/", "Accept-Language", "en-US", "Accept-Charset", "UTF-8")
    val request2ReceivedAt = System.currentTimeMillis()
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v1")

    // Check the merged response. The request is the application's original request.
    cacheHit.assertCode(200)
        .assertBody("A")
        .assertHeader("Donut", "b")
        .assertRequestUrl(server.url("/"))
        .assertRequestHeader("Accept-Language", "en-US")
        .assertRequestHeader("Accept-Charset", "UTF-8")
        .assertRequestHeader("If-None-Match") // No If-None-Match on the user's request.
        .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
        .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt)

    // Check the cached response. Its request contains only the saved Vary headers.
    cacheHit.cacheResponse()
        .assertCode(200)
        .assertHeader("Donut", "a")
        .assertHeader("ETag", "v1")
        .assertRequestUrl(server.url("/"))
        .assertRequestHeader("Accept-Language") // No Vary on Accept-Language.
        .assertRequestHeader("Accept-Charset", "UTF-8") // Because of Vary on Accept-Charset.
        .assertRequestHeader("If-None-Match") // This wasn't present in the original request.
        .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
        .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt)

    // Check the network response. It has the caller's request, plus some caching headers.
    cacheHit.networkResponse()
        .assertCode(304)
        .assertHeader("Donut", "b")
        .assertRequestHeader("Accept-Language", "en-US")
        .assertRequestHeader("Accept-Charset", "UTF-8")
        .assertRequestHeader("If-None-Match", "v1") // If-None-Match in the validation request.
        .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
        .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt)
  }

  @Test
  fun conditionalCacheHit_Async() {
    server.enqueue(MockResponse().setBody("A").addHeader("ETag: v1"))
    server.enqueue(MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED))

    client = client.newBuilder()
        .cache(cache)
        .build()

    val request1 = Request.Builder()
        .url(server.url("/"))
        .build()
    client.newCall(request1).enqueue(callback)
    callback.await(request1.url).assertCode(200).assertBody("A")
    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull()

    val request2 = Request.Builder()
        .url(server.url("/"))
        .build()
    client.newCall(request2).enqueue(callback)
    callback.await(request2.url).assertCode(200).assertBody("A")
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v1")
  }

  @Test
  fun conditionalCacheMiss() {
    server.enqueue(MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Vary: Accept-Charset")
        .addHeader("Donut: a")
        .setBody("A"))
    server.enqueue(MockResponse()
        .addHeader("Donut: b")
        .setBody("B"))

    client = client.newBuilder()
        .cache(cache)
        .build()

    val request1SentAt = System.currentTimeMillis()
    executeSynchronously("/", "Accept-Language", "fr-CA", "Accept-Charset", "UTF-8")
        .assertCode(200)
        .assertBody("A")
    val request1ReceivedAt = System.currentTimeMillis()
    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull()

    // Different request, but Vary says it doesn't matter.
    Thread.sleep(10) // Make sure the timestamps are unique.
    val request2SentAt = System.currentTimeMillis()
    val cacheMiss = executeSynchronously(
        "/", "Accept-Language", "en-US", "Accept-Charset", "UTF-8")
    val request2ReceivedAt = System.currentTimeMillis()
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v1")

    // Check the user response. It has the application's original request.
    cacheMiss.assertCode(200)
        .assertBody("B")
        .assertHeader("Donut", "b")
        .assertRequestUrl(server.url("/"))
        .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
        .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt)

    // Check the cache response. Even though it's a miss, we used the cache.
    cacheMiss.cacheResponse()
        .assertCode(200)
        .assertHeader("Donut", "a")
        .assertHeader("ETag", "v1")
        .assertRequestUrl(server.url("/"))
        .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
        .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt)

    // Check the network response. It has the network request, plus caching headers.
    cacheMiss.networkResponse()
        .assertCode(200)
        .assertHeader("Donut", "b")
        .assertRequestHeader("If-None-Match", "v1") // If-None-Match in the validation request.
        .assertRequestUrl(server.url("/"))
        .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
        .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt)
  }

  @Test
  fun conditionalCacheMiss_Async() {
    server.enqueue(MockResponse().setBody("A").addHeader("ETag: v1"))
    server.enqueue(MockResponse().setBody("B"))

    client = client.newBuilder()
        .cache(cache)
        .build()

    val request1 = Request.Builder()
        .url(server.url("/"))
        .build()
    client.newCall(request1).enqueue(callback)
    callback.await(request1.url).assertCode(200).assertBody("A")
    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull()

    val request2 = Request.Builder()
        .url(server.url("/"))
        .build()
    client.newCall(request2).enqueue(callback)
    callback.await(request2.url).assertCode(200).assertBody("B")
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v1")
  }

  @Test
  fun onlyIfCachedReturns504WhenNotCached() {
    executeSynchronously("/", "Cache-Control", "only-if-cached")
        .assertCode(504)
        .assertBody("")
        .assertNoNetworkResponse()
        .assertNoCacheResponse()
  }

  @Test
  fun networkDropsOnConditionalGet() {
    client = client.newBuilder()
        .cache(cache)
        .build()

    // Seed the cache.
    server.enqueue(MockResponse()
        .addHeader("ETag: v1")
        .setBody("A"))
    executeSynchronously("/")
        .assertCode(200)
        .assertBody("A")

    // Attempt conditional cache validation and a DNS miss.
    client = client.newBuilder()
        .dns(FakeDns())
        .build()
    executeSynchronously("/").assertFailure(UnknownHostException::class.java)
  }

  @Test
  fun redirect() {
    server.enqueue(MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .addHeader("Test", "Redirect from /a to /b")
        .setBody("/a has moved!"))
    server.enqueue(MockResponse()
        .setResponseCode(302)
        .addHeader("Location: /c")
        .addHeader("Test", "Redirect from /b to /c")
        .setBody("/b has moved!"))
    server.enqueue(MockResponse().setBody("C"))

    executeSynchronously("/a")
        .assertCode(200)
        .assertBody("C")
        .priorResponse()
        .assertCode(302)
        .assertHeader("Test", "Redirect from /b to /c")
        .priorResponse()
        .assertCode(301)
        .assertHeader("Test", "Redirect from /a to /b")

    // New connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    // Connection reused.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    // Connection reused again!
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(2)
  }

  @Test
  fun postRedirectsToGet() {
    server.enqueue(MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /page2")
        .setBody("This page has moved!"))
    server.enqueue(MockResponse().setBody("Page 2"))

    val response = client.newCall(Request.Builder()
        .url(server.url("/page1"))
        .post("Request Body".toRequestBody("text/plain".toMediaType()))
        .build()).execute()
    assertThat(response.body!!.string()).isEqualTo("Page 2")

    val page1 = server.takeRequest()
    assertThat(page1.requestLine).isEqualTo("POST /page1 HTTP/1.1")
    assertThat(page1.body.readUtf8()).isEqualTo("Request Body")

    val page2 = server.takeRequest()
    assertThat(page2.requestLine).isEqualTo("GET /page2 HTTP/1.1")
  }

  @Test
  fun getClientRequestTimeout() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AT_END }
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"))
    server.enqueue(MockResponse().setBody("Body"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()

    assertThat(response.body!!.string()).isEqualTo("Body")
  }

  @Test
  fun getClientRequestTimeoutWithBackPressure() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AT_END }
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setHeader("Retry-After", "1")
        .setBody("You took too long!"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()

    assertThat(response.body!!.string()).isEqualTo("You took too long!")
  }

  @Test
  fun requestBodyRetransmittedOnClientRequestTimeout() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AT_END }
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"))
    server.enqueue(MockResponse().setBody("Body"))

    val request = Request.Builder()
        .url(server.url("/"))
        .post("Hello".toRequestBody("text/plain".toMediaType()))
        .build()
    val response = client.newCall(request).execute()

    assertThat(response.body!!.string()).isEqualTo("Body")

    val request1 = server.takeRequest()
    assertThat(request1.body.readUtf8()).isEqualTo("Hello")

    val request2 = server.takeRequest()
    assertThat(request2.body.readUtf8()).isEqualTo("Hello")
  }

  @Test
  fun disableClientRequestTimeoutRetry() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AT_END }
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"))

    client = client.newBuilder()
        .retryOnConnectionFailure(false)
        .build()

    val request = Request.Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()

    assertThat(response.code).isEqualTo(408)
    assertThat(response.body!!.string()).isEqualTo("You took too long!")
  }

  @Test
  fun maxClientRequestTimeoutRetries() {
    var mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AT_END }
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"))
    mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AT_END }
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()

    assertThat(response.code).isEqualTo(408)
    assertThat(response.body!!.string()).isEqualTo("You took too long!")

    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun maxUnavailableTimeoutRetries() {
    var mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AT_END }
        .setResponseCode(503)
        .setHeader("Connection", "Close")
        .setHeader("Retry-After", "0")
        .setBody("You took too long!"))
    mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AT_END }
        .setResponseCode(503)
        .setHeader("Connection", "Close")
        .setHeader("Retry-After", "0")
        .setBody("You took too long!"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()

    assertThat(response.code).isEqualTo(503)
    assertThat(response.body!!.string()).isEqualTo("You took too long!")

    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun retryOnUnavailableWith0RetryAfter() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AT_END }
        .setResponseCode(503)
        .setHeader("Connection", "Close")
        .setHeader("Retry-After", "0")
        .setBody("You took too long!"))
    server.enqueue(MockResponse().setBody("Body"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()

    assertThat(response.body!!.string()).isEqualTo("Body")
  }

  @Test
  fun canRetryNormalRequestBody() {
    server.enqueue(MockResponse()
        .setResponseCode(503)
        .setHeader("Retry-After", "0")
        .setBody("please retry"))
    server.enqueue(MockResponse()
        .setBody("thank you for retrying"))

    val request = Request.Builder()
        .url(server.url("/"))
        .post(object : RequestBody() {
          var attempt = 0

          override fun contentType(): MediaType? {
            return null
          }

          @Throws(IOException::class)
          override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8("attempt " + attempt++)
          }
        })
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body!!.string()).isEqualTo("thank you for retrying")

    assertThat(server.takeRequest().body.readUtf8()).isEqualTo("attempt 0")
    assertThat(server.takeRequest().body.readUtf8()).isEqualTo("attempt 1")
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun cannotRetryOneShotRequestBody() {
    server.enqueue(MockResponse()
        .setResponseCode(503)
        .setHeader("Retry-After", "0")
        .setBody("please retry"))
    server.enqueue(MockResponse()
        .setBody("thank you for retrying"))

    val request = Request.Builder()
        .url(server.url("/"))
        .post(object : RequestBody() {
          var attempt = 0

          override fun contentType(): MediaType? {
            return null
          }

          @Throws(IOException::class)
          override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8("attempt " + attempt++)
          }

          override fun isOneShot(): Boolean {
            return true
          }
        })
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.code).isEqualTo(503)
    assertThat(response.body!!.string()).isEqualTo("please retry")

    assertThat(server.takeRequest().body.readUtf8()).isEqualTo("attempt 0")
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun propfindRedirectsToPropfindAndMaintainsRequestBody() {
    // given
    server.enqueue(MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /page2")
        .setBody("This page has moved!"))
    server.enqueue(MockResponse().setBody("Page 2"))

    // when
    val response = client.newCall(Request.Builder()
        .url(server.url("/page1"))
        .method("PROPFIND", "Request Body".toRequestBody("text/plain".toMediaType()))
        .build()).execute()

    // then
    assertThat(response.body!!.string()).isEqualTo("Page 2")

    val page1 = server.takeRequest()
    assertThat(page1.requestLine).isEqualTo("PROPFIND /page1 HTTP/1.1")
    assertThat(page1.body.readUtf8()).isEqualTo("Request Body")

    val page2 = server.takeRequest()
    assertThat(page2.requestLine).isEqualTo("PROPFIND /page2 HTTP/1.1")
    assertThat(page2.body.readUtf8()).isEqualTo("Request Body")
  }

  @Test
  fun responseCookies() {
    server.enqueue(MockResponse()
        .addHeader("Set-Cookie", "a=b; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        .addHeader("Set-Cookie", "c=d; Expires=Fri, 02 Jan 1970 23:59:59 GMT; path=/bar; secure"))

    val cookieJar = RecordingCookieJar()
    client = client.newBuilder()
        .cookieJar(cookieJar)
        .build()

    executeSynchronously("/").assertCode(200)

    val responseCookies = cookieJar.takeResponseCookies()
    assertThat(responseCookies.size).isEqualTo(2)
    assertThat(responseCookies[0].toString()).isEqualTo(
        "a=b; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
    assertThat(responseCookies[1].toString()).isEqualTo(
        "c=d; expires=Fri, 02 Jan 1970 23:59:59 GMT; path=/bar; secure")
  }

  @Test
  fun requestCookies() {
    server.enqueue(MockResponse())

    val cookieJar = RecordingCookieJar()

    cookieJar.enqueueRequestCookies(
        Cookie.Builder().name("a").value("b").domain(server.hostName).build(),
        Cookie.Builder().name("c").value("d").domain(server.hostName).build())
    client = client.newBuilder()
        .cookieJar(cookieJar)
        .build()

    executeSynchronously("/").assertCode(200)

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.getHeader("Cookie")).isEqualTo("a=b; c=d")
  }

  @Test
  fun redirectsDoNotIncludeTooManyCookies() {
    server2.enqueue(MockResponse().setBody("Page 2"))
    server.enqueue(MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.url("/")))

    val cookieManager = CookieManager(null, ACCEPT_ORIGINAL_SERVER)
    val cookie = HttpCookie("c", "cookie")
    cookie.domain = server.hostName
    cookie.path = "/"
    val portList = server.port.toString()
    cookie.portlist = portList
    cookieManager.cookieStore.add(server.url("/").toUri(), cookie)
    client = client.newBuilder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()

    val response = client.newCall(Request.Builder()
        .url(server.url("/page1"))
        .build()).execute()
    assertThat(response.body!!.string()).isEqualTo("Page 2")

    val request1 = server.takeRequest()
    assertThat(request1.getHeader("Cookie")).isEqualTo("c=cookie")

    val request2 = server2.takeRequest()
    assertThat(request2.getHeader("Cookie")).isNull()
  }

  @Test
  fun redirectsDoNotIncludeTooManyAuthHeaders() {
    server2.enqueue(MockResponse().setBody("Page 2"))
    server.enqueue(MockResponse()
        .setResponseCode(401))
    server.enqueue(MockResponse()
        .setResponseCode(302)
        .addHeader("Location: " + server2.url("/b")))

    client = client.newBuilder()
        .authenticator(RecordingOkAuthenticator(Credentials.basic("jesse", "secret"), null))
        .build()

    val request = Request.Builder().url(server.url("/a")).build()
    val response = client.newCall(request).execute()
    assertThat(response.body!!.string()).isEqualTo("Page 2")

    val redirectRequest = server2.takeRequest()
    assertThat(redirectRequest.getHeader("Authorization")).isNull()
    assertThat(redirectRequest.path).isEqualTo("/b")
  }

  @Test
  fun redirect_Async() {
    server.enqueue(MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .addHeader("Test", "Redirect from /a to /b")
        .setBody("/a has moved!"))
    server.enqueue(MockResponse()
        .setResponseCode(302)
        .addHeader("Location: /c")
        .addHeader("Test", "Redirect from /b to /c")
        .setBody("/b has moved!"))
    server.enqueue(MockResponse().setBody("C"))

    val request = Request.Builder().url(server.url("/a")).build()
    client.newCall(request).enqueue(callback)

    callback.await(server.url("/a"))
        .assertCode(200)
        .assertBody("C")
        .priorResponse()
        .assertCode(302)
        .assertHeader("Test", "Redirect from /b to /c")
        .priorResponse()
        .assertCode(301)
        .assertHeader("Test", "Redirect from /a to /b")

    // New connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    // Connection reused.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    // Connection reused again!
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(2)
  }

  @Test
  fun follow20Redirects() {
    for (i in 0..19) {
      server.enqueue(MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)))
    }
    server.enqueue(MockResponse().setBody("Success!"))

    executeSynchronously("/0")
        .assertCode(200)
        .assertBody("Success!")
  }

  @Test
  fun follow20Redirects_Async() {
    for (i in 0..19) {
      server.enqueue(MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)))
    }
    server.enqueue(MockResponse().setBody("Success!"))

    val request = Request.Builder().url(server.url("/0")).build()
    client.newCall(request).enqueue(callback)
    callback.await(server.url("/0"))
        .assertCode(200)
        .assertBody("Success!")
  }

  @Test
  fun doesNotFollow21Redirects() {
    for (i in 0..20) {
      server.enqueue(MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)))
    }

    try {
      client.newCall(Request.Builder().url(server.url("/0")).build()).execute()
      fail()
    } catch (expected: IOException) {
      assertThat(expected.message).isEqualTo("Too many follow-up requests: 21")
    }
  }

  @Test
  fun doesNotFollow21Redirects_Async() {
    for (i in 0..20) {
      server.enqueue(MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)))
    }

    val request = Request.Builder().url(server.url("/0")).build()
    client.newCall(request).enqueue(callback)
    callback.await(server.url("/0")).assertFailure("Too many follow-up requests: 21")
  }

  @Test
  fun http204WithBodyDisallowed() {
    server.enqueue(MockResponse()
        .setResponseCode(204)
        .setBody("I'm not even supposed to be here today."))

    executeSynchronously("/")
        .assertFailure("HTTP 204 had non-zero Content-Length: 39")
  }

  @Test
  fun http205WithBodyDisallowed() {
    server.enqueue(MockResponse()
        .setResponseCode(205)
        .setBody("I'm not even supposed to be here today."))

    executeSynchronously("/")
        .assertFailure("HTTP 205 had non-zero Content-Length: 39")
  }

  @Test
  fun httpWithExcessiveHeaders() {
    val longLine = "HTTP/1.1 200 " + stringFill('O', 256 * 1024) + "K"

    server.protocols = listOf(Protocol.HTTP_1_1)

    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.status = longLine }
        .setBody("I'm not even supposed to be here today."))

    executeSynchronously("/")
        .assertFailureMatches(".*unexpected end of stream on " + server.url("/").redact())
  }

  private fun stringFill(fillChar: Char, length: Int): String {
    val value = CharArray(length)
    Arrays.fill(value, fillChar)
    return String(value)
  }

  @Test
  fun canceledBeforeExecute() {
    val call = client.newCall(Request.Builder().url(server.url("/a")).build())
    call.cancel()

    try {
      call.execute()
      fail()
    } catch (expected: IOException) {
    }

    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun cancelDuringHttpConnect() {
    cancelDuringConnect("http")
  }

  @Test
  fun cancelDuringHttpsConnect() {
    cancelDuringConnect("https")
  }

  /** Cancel a call that's waiting for connect to complete.  */
  @Throws(Exception::class)
  private fun cancelDuringConnect(scheme: String) {
    val mockResponse = MockResponse()
    server.enqueue(
        mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.STALL_SOCKET_AT_START })

    val cancelDelayMillis = 300L
    val call = client.newCall(Request.Builder()
        .url(server.url("/").newBuilder().scheme(scheme).build())
        .build())
    cancelLater(call, cancelDelayMillis)

    val startNanos = System.nanoTime()
    try {
      call.execute()
      fail()
    } catch (expected: IOException) {
    }

    val elapsedNanos = System.nanoTime() - startNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedNanos).toFloat()).isCloseTo(
        cancelDelayMillis.toFloat(), offset(100f))
  }

  @Test
  fun cancelImmediatelyAfterEnqueue() {
    server.enqueue(MockResponse())
    val latch = CountDownLatch(1)
    client = client.newBuilder()
        .addNetworkInterceptor { chain ->
          try {
            latch.await()
          } catch (e: InterruptedException) {
            throw AssertionError(e)
          }

          chain.proceed(chain.request())
        }
        .build()

    val call = client.newCall(Request.Builder()
        .url(server.url("/a"))
        .build())
    call.enqueue(callback)
    call.cancel()
    latch.countDown()

    callback.await(server.url("/a")).assertFailure("Canceled", "Socket closed")
  }

  @Test
  fun cancelAll() {
    val call = client.newCall(Request.Builder()
        .url(server.url("/"))
        .build())
    call.enqueue(callback)
    client.dispatcher.cancelAll()
    callback.await(server.url("/")).assertFailure("Canceled", "Socket closed")
  }

  @Test
  @Throws(Exception::class)
  fun cancelWhileRequestHeadersAreSent() {
    server.enqueue(MockResponse().setBody("A"))

    val listener = object : EventListener() {
      override fun requestHeadersStart(call: Call) {
        try {
          // Cancel call from another thread to avoid reentrance.
          cancelLater(call, 0).join()
        } catch (e: InterruptedException) {
          throw AssertionError()
        }
      }
    }
    client = client.newBuilder().eventListener(listener).build()

    val call = client.newCall(Request.Builder().url(server.url("/a")).build())
    try {
      call.execute()
      fail()
    } catch (expected: IOException) {
    }
  }

  @Test
  @Throws(Exception::class)
  fun cancelWhileRequestHeadersAreSent_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    cancelWhileRequestHeadersAreSent()
  }

  @Test
  fun cancelBeforeBodyIsRead() {
    server.enqueue(MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS))

    val call = client.newCall(Request.Builder().url(server.url("/a")).build())
    val executor = Executors.newSingleThreadExecutor()
    val result = executor.submit(Callable { call.execute() })

    Thread.sleep(100) // wait for it to go in flight.

    call.cancel()
    try {
      result.get().body!!.bytes()
      fail()
    } catch (expected: IOException) {
    }

    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun cancelInFlightBeforeResponseReadThrowsIOE() {
    val request = Request.Builder().url(server.url("/a")).build()
    val call = client.newCall(request)

    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        call.cancel()
        return MockResponse().setBody("A")
      }
    }

    try {
      call.execute()
      fail()
    } catch (expected: IOException) {
    }
  }

  @Test
  fun cancelInFlightBeforeResponseReadThrowsIOE_HTTPS() {
    enableTls()
    cancelInFlightBeforeResponseReadThrowsIOE()
  }

  @Test
  fun cancelInFlightBeforeResponseReadThrowsIOE_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    cancelInFlightBeforeResponseReadThrowsIOE()
  }

  /**
   * This test puts a request in front of one that is to be canceled, so that it is canceled before
   * I/O takes place.
   */
  @Test
  fun canceledBeforeIOSignalsOnFailure() {
    // Force requests to be executed serially.
    val dispatcher = Dispatcher(client.dispatcher.executorService)
    dispatcher.maxRequests = 1
    client = client.newBuilder()
        .dispatcher(dispatcher)
        .build()

    val requestA = Request.Builder().url(server.url("/a")).build()
    val requestB = Request.Builder().url(server.url("/b")).build()
    val callA = client.newCall(requestA)
    val callB = client.newCall(requestB)

    server.dispatcher = object : Dispatcher() {
      var nextResponse = 'A'

      override fun dispatch(request: RecordedRequest): MockResponse {
        callB.cancel()
        return MockResponse().setBody((nextResponse++).toString())
      }
    }

    callA.enqueue(callback)
    callB.enqueue(callback)
    assertThat(server.takeRequest().path).isEqualTo("/a")

    callback.await(requestA.url).assertBody("A")
    // At this point we know the callback is ready, and that it will receive a cancel failure.
    callback.await(requestB.url).assertFailure("Canceled", "Socket closed")
  }

  @Test
  fun canceledBeforeIOSignalsOnFailure_HTTPS() {
    enableTls()
    canceledBeforeIOSignalsOnFailure()
  }

  @Test
  fun canceledBeforeIOSignalsOnFailure_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    canceledBeforeIOSignalsOnFailure()
  }

  @Test
  fun canceledBeforeResponseReadSignalsOnFailure() {
    val requestA = Request.Builder().url(server.url("/a")).build()
    val call = client.newCall(requestA)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        call.cancel()
        return MockResponse().setBody("A")
      }
    }

    call.enqueue(callback)
    assertThat(server.takeRequest().path).isEqualTo("/a")

    callback.await(requestA.url).assertFailure("Canceled", "stream was reset: CANCEL",
        "Socket closed")
  }

  @Test
  fun canceledBeforeResponseReadSignalsOnFailure_HTTPS() {
    enableTls()
    canceledBeforeResponseReadSignalsOnFailure()
  }

  @Test
  fun canceledBeforeResponseReadSignalsOnFailure_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    canceledBeforeResponseReadSignalsOnFailure()
  }

  /**
   * There's a race condition where the cancel may apply after the stream has already been
   * processed.
   */
  @Test
  fun canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce() {
    server.enqueue(MockResponse().setBody("A"))

    val latch = CountDownLatch(1)
    val bodyRef = AtomicReference<String>()
    val failureRef = AtomicBoolean()

    val request = Request.Builder().url(server.url("/a")).build()
    val call = client.newCall(request)
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        failureRef.set(true)
        latch.countDown()
      }

      @Throws(IOException::class)
      override fun onResponse(call: Call, response: Response) {
        call.cancel()
        try {
          bodyRef.set(response.body!!.string())
        } catch (e: IOException) { // It is ok if this broke the stream.
          bodyRef.set("A")
          throw e // We expect to not loop into onFailure in this case.
        } finally {
          latch.countDown()
        }
      }
    })

    latch.await()
    assertThat(bodyRef.get()).isEqualTo("A")
    assertThat(failureRef.get()).isFalse
  }

  @Test
  fun canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_HTTPS() {
    enableTls()
    canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce()
  }

  @Test
  fun canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce()
  }

  @Test
  fun cancelWithInterceptor() {
    client = client.newBuilder()
        .addInterceptor { chain ->
          chain.proceed(chain.request())
          throw AssertionError() // We expect an exception.
        }
        .build()

    val call = client.newCall(Request.Builder().url(server.url("/a")).build())
    call.cancel()

    try {
      call.execute()
      fail()
    } catch (expected: IOException) {
    }

    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun gzip() {
    val gzippedBody = gzip("abcabcabc")
    val bodySize = gzippedBody.size.toString()

    server.enqueue(MockResponse()
        .setBody(gzippedBody)
        .addHeader("Content-Encoding: gzip"))

    // Confirm that the user request doesn't have Accept-Encoding, and the user
    // response doesn't have a Content-Encoding or Content-Length.
    val userResponse = executeSynchronously("/")
    userResponse.assertCode(200)
        .assertRequestHeader("Accept-Encoding")
        .assertHeader("Content-Encoding")
        .assertHeader("Content-Length")
        .assertBody("abcabcabc")

    // But the network request doesn't lie. OkHttp used gzip for this call.
    userResponse.networkResponse()
        .assertHeader("Content-Encoding", "gzip")
        .assertHeader("Content-Length", bodySize)
        .assertRequestHeader("Accept-Encoding", "gzip")
  }

  /** https://github.com/square/okhttp/issues/1927  */
  @Test
  fun gzipResponseAfterAuthenticationChallenge() {
    server.enqueue(MockResponse()
        .setResponseCode(401))
    server.enqueue(MockResponse()
        .setBody(gzip("abcabcabc"))
        .addHeader("Content-Encoding: gzip"))
    client = client.newBuilder()
        .authenticator(RecordingOkAuthenticator("password", null))
        .build()

    executeSynchronously("/").assertBody("abcabcabc")
  }

  @Test
  fun rangeHeaderPreventsAutomaticGzip() {
    val gzippedBody = gzip("abcabcabc")

    // Enqueue a gzipped response. Our request isn't expecting it, but that's okay.
    server.enqueue(MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
        .setBody(gzippedBody)
        .addHeader("Content-Encoding: gzip")
        .addHeader("Content-Range: bytes 0-" + (gzippedBody.size - 1)))

    // Make a range request.
    val request = Request.Builder()
        .url(server.url("/"))
        .header("Range", "bytes=0-")
        .build()
    val call = client.newCall(request)

    // The response is not decompressed.
    val response = call.execute()
    assertThat(response.header("Content-Encoding")).isEqualTo("gzip")
    assertThat(response.body!!.source().readByteString()).isEqualTo(
        gzippedBody.snapshot())

    // The request did not offer gzip support.
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.getHeader("Accept-Encoding")).isNull()
  }

  @Test
  fun asyncResponseCanBeConsumedLater() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def"))

    val request = Request.Builder()
        .url(server.url("/"))
        .header("User-Agent", "SyncApiTest")
        .build()

    val responseRef = SynchronousQueue<Response>()
    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        throw AssertionError()
      }

      @Throws(IOException::class)
      override fun onResponse(call: Call, response: Response) {
        try {
          responseRef.put(response)
        } catch (e: InterruptedException) {
          throw AssertionError()
        }
      }
    })

    val response = responseRef.take()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body!!.string()).isEqualTo("abc")

    // Make another request just to confirm that that connection can be reused...
    executeSynchronously("/").assertBody("def")
    // New connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    // Connection reused.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)

    // ... even before we close the response body!
    response.body!!.close()
  }

  @Test
  fun userAgentIsIncludedByDefault() {
    server.enqueue(MockResponse())

    executeSynchronously("/")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.getHeader("User-Agent")).matches(userAgent)
  }

  @Test
  fun setFollowRedirectsFalse() {
    server.enqueue(MockResponse()
        .setResponseCode(302)
        .addHeader("Location: /b")
        .setBody("A"))
    server.enqueue(MockResponse().setBody("B"))

    client = client.newBuilder()
        .followRedirects(false)
        .build()
    executeSynchronously("/a")
        .assertBody("A")
        .assertCode(302)
  }

  @Test
  fun expect100ContinueNonEmptyRequestBody() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.EXPECT_CONTINUE })

    val request = Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertSuccessful()

    assertThat(server.takeRequest().body.readUtf8()).isEqualTo("abc")
  }

  @Test
  fun expect100ContinueEmptyRequestBody() {
    server.enqueue(MockResponse())

    val request = Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post("".toRequestBody("text/plain".toMediaType()))
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertSuccessful()
  }

  @Test
  fun expect100ContinueEmptyRequestBody_HTTP2() {
    enableProtocol(Protocol.HTTP_2)
    expect100ContinueEmptyRequestBody()
  }

  @Test
  fun expect100ContinueTimesOutWithoutContinue() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.NO_RESPONSE })

    client = client.newBuilder()
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .build()

    val request = Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build()

    val call = client.newCall(request)
    try {
      call.execute()
      fail()
    } catch (expected: SocketTimeoutException) {
    }

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.body.readUtf8()).isEqualTo("")
  }

  @Test
  fun expect100ContinueTimesOutWithoutContinue_HTTP2() {
    enableProtocol(Protocol.HTTP_2)
    expect100ContinueTimesOutWithoutContinue()
  }

  @Test
  fun serverRespondsWithUnsolicited100Continue() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.CONTINUE_ALWAYS })

    val request = Request.Builder()
        .url(server.url("/"))
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build()

    executeSynchronously(request)
        .assertCode(200)
        .assertSuccessful()

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.body.readUtf8()).isEqualTo("abc")
  }

  @Test
  fun serverRespondsWithUnsolicited100Continue_HTTP2() {
    enableProtocol(Protocol.HTTP_2)
    serverRespondsWithUnsolicited100Continue()
  }

  @Test
  fun serverRespondsWith100ContinueOnly() {
    client = client.newBuilder()
        .readTimeout(1, TimeUnit.SECONDS)
        .build()

    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.status = "HTTP/1.1 100 Continue" })

    val request = Request.Builder()
        .url(server.url("/"))
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build()

    val call = client.newCall(request)
    try {
      call.execute()
      fail()
    } catch (expected: SocketTimeoutException) {
    }

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.body.readUtf8()).isEqualTo("abc")
  }

  @Test
  fun serverRespondsWith100ContinueOnly_HTTP2() {
    enableProtocol(Protocol.HTTP_2)
    serverRespondsWith100ContinueOnly()
  }

  @Test
  fun successfulExpectContinuePermitsConnectionReuse() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.EXPECT_CONTINUE })
    server.enqueue(MockResponse())

    executeSynchronously(Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build())
    executeSynchronously(Request.Builder()
        .url(server.url("/"))
        .build())

    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @Test
  fun successfulExpectContinuePermitsConnectionReuseWithHttp2() {
    enableProtocol(Protocol.HTTP_2)
    successfulExpectContinuePermitsConnectionReuse()
  }

  @Test
  fun unsuccessfulExpectContinuePreventsConnectionReuse() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())

    executeSynchronously(Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build())
    executeSynchronously(Request.Builder()
        .url(server.url("/"))
        .build())

    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @Test
  fun unsuccessfulExpectContinuePermitsConnectionReuseWithHttp2() {
    platform.assumeHttp2Support()

    enableProtocol(Protocol.HTTP_2)

    server.enqueue(MockResponse())
    server.enqueue(MockResponse())

    executeSynchronously(Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build())
    executeSynchronously(Request.Builder()
        .url(server.url("/"))
        .build())

    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  /** We forbid non-ASCII characters in outgoing request headers, but accept UTF-8.  */
  @Test
  fun responseHeaderParsingIsLenient() {
    val headersBuilder = Headers.Builder()
    headersBuilder.add("Content-Length", "0")
    addHeaderLenient(headersBuilder, "a\tb: c\u007fd")
    addHeaderLenient(headersBuilder, ": ef")
    addHeaderLenient(headersBuilder, "\ud83c\udf69: \u2615\ufe0f")
    val headers = headersBuilder.build()
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.headers = headers })

    executeSynchronously("/")
        .assertHeader("a\tb", "c\u007fd")
        .assertHeader("\ud83c\udf69", "\u2615\ufe0f")
        .assertHeader("", "ef")
  }

  @Test
  fun customDns() {
    // Configure a DNS that returns our local MockWebServer for android.com.
    val dns = FakeDns()
    dns.set("android.com", Dns.SYSTEM.lookup(server.url("/").host))
    client = client.newBuilder()
        .dns(dns)
        .build()

    server.enqueue(MockResponse())
    val request = Request.Builder()
        .url(server.url("/").newBuilder().host("android.com").build())
        .build()
    executeSynchronously(request).assertCode(200)

    dns.assertRequests("android.com")
  }

  @Test
  fun dnsReturnsZeroIpAddresses() {
    // Configure a DNS that returns our local MockWebServer for android.com.
    val dns = FakeDns()
    val ipAddresses = ArrayList<InetAddress>()
    dns.set("android.com", ipAddresses)
    client = client.newBuilder()
        .dns(dns)
        .build()

    server.enqueue(MockResponse())
    val request = Request.Builder()
        .url(server.url("/").newBuilder().host("android.com").build())
        .build()
    executeSynchronously(request).assertFailure("$dns returned no addresses for android.com")

    dns.assertRequests("android.com")
  }

  /** We had a bug where failed HTTP/2 calls could break the entire connection.  */
  @Test
  fun failingCallsDoNotInterfereWithConnection() {
    enableProtocol(Protocol.HTTP_2)

    server.enqueue(MockResponse().setBody("Response 1"))
    server.enqueue(MockResponse().setBody("Response 2"))

    val requestBody = object : RequestBody() {
      override fun contentType(): MediaType? {
        return null
      }

      @Throws(IOException::class)
      override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("abc")
        sink.flush()

        makeFailingCall()

        sink.writeUtf8("def")
        sink.flush()
      }
    }
    val call = client.newCall(Request.Builder()
        .url(server.url("/"))
        .post(requestBody)
        .build())
    assertThat(call.execute().body!!.string()).isEqualTo("Response 1")
  }

  /** Test which headers are sent unencrypted to the HTTP proxy.  */
  @Test
  fun proxyConnectOmitsApplicationHeaders() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true)
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply {
      mockResponse.socketPolicy = SocketPolicy.UPGRADE_TO_SSL_AT_END
    }
        .clearHeaders())
    server.enqueue(MockResponse()
        .setBody("encrypted response from the origin server"))

    val hostnameVerifier = RecordingHostnameVerifier()
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .proxy(server.toProxyAddress())
        .hostnameVerifier(hostnameVerifier)
        .build()

    val request = Request.Builder()
        .url("https://android.com/foo")
        .header("Private", "Secret")
        .header("User-Agent", "App 1.0")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body!!.string()).isEqualTo(
        "encrypted response from the origin server")

    val connect = server.takeRequest()
    assertThat(connect.getHeader("Private")).isNull()
    assertThat(connect.getHeader("User-Agent")).isEqualTo(userAgent)
    assertThat(connect.getHeader("Proxy-Connection")).isEqualTo("Keep-Alive")
    assertThat(connect.getHeader("Host")).isEqualTo("android.com:443")

    val get = server.takeRequest()
    assertThat(get.getHeader("Private")).isEqualTo("Secret")
    assertThat(get.getHeader("User-Agent")).isEqualTo("App 1.0")

    assertThat(hostnameVerifier.calls).containsExactly("verify android.com")
  }

  /** Respond to a proxy authorization challenge.  */
  @Test
  fun proxyAuthenticateOnConnect() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true)
    server.enqueue(MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\""))
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply {
      mockResponse.socketPolicy = SocketPolicy.UPGRADE_TO_SSL_AT_END
    }
        .clearHeaders())
    server.enqueue(MockResponse()
        .setBody("response body"))

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(RecordingOkAuthenticator("password", "Basic"))
        .hostnameVerifier(RecordingHostnameVerifier())
        .build()

    val request = Request.Builder()
        .url("https://android.com/foo")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body!!.string()).isEqualTo("response body")

    val connect1 = server.takeRequest()
    assertThat(connect1.requestLine).isEqualTo("CONNECT android.com:443 HTTP/1.1")
    assertThat(connect1.getHeader("Proxy-Authorization")).isNull()

    val connect2 = server.takeRequest()
    assertThat(connect2.requestLine).isEqualTo("CONNECT android.com:443 HTTP/1.1")
    assertThat(connect2.getHeader("Proxy-Authorization")).isEqualTo("password")

    val get = server.takeRequest()
    assertThat(get.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(get.getHeader("Proxy-Authorization")).isNull()
  }

  /** Confirm that the proxy authenticator works for unencrypted HTTP proxies.  */
  @Test
  fun httpProxyAuthenticate() {
    server.enqueue(MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\""))
    server.enqueue(MockResponse()
        .setBody("response body"))

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(RecordingOkAuthenticator("password", "Basic"))
        .build()

    val request = Request.Builder()
        .url("http://android.com/foo")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body!!.string()).isEqualTo("response body")

    val get1 = server.takeRequest()
    assertThat(get1.requestLine).isEqualTo("GET http://android.com/foo HTTP/1.1")
    assertThat(get1.getHeader("Proxy-Authorization")).isNull()

    val get2 = server.takeRequest()
    assertThat(get2.requestLine).isEqualTo("GET http://android.com/foo HTTP/1.1")
    assertThat(get2.getHeader("Proxy-Authorization")).isEqualTo("password")
  }

  /**
   * OkHttp has a bug where a `Connection: close` response header is not honored when establishing a
   * TLS tunnel. https://github.com/square/okhttp/issues/2426
   */
  @Test
  fun proxyAuthenticateOnConnectWithConnectionClose() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true)
    server.protocols = listOf(Protocol.HTTP_1_1)
    server.enqueue(MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
        .addHeader("Connection: close"))
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply {
      mockResponse.socketPolicy = SocketPolicy.UPGRADE_TO_SSL_AT_END
    }
        .clearHeaders())
    server.enqueue(MockResponse()
        .setBody("response body"))

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(RecordingOkAuthenticator("password", "Basic"))
        .hostnameVerifier(RecordingHostnameVerifier())
        .build()

    val request = Request.Builder()
        .url("https://android.com/foo")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body!!.string()).isEqualTo("response body")

    // First CONNECT call needs a new connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)

    // Second CONNECT call needs a new connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)

    // GET reuses the connection from the second connect.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @Test
  fun tooManyProxyAuthFailuresWithConnectionClose() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true)
    server.protocols = listOf(Protocol.HTTP_1_1)
    for (i in 0..20) {
      server.enqueue(MockResponse()
          .setResponseCode(407)
          .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
          .addHeader("Connection: close"))
    }

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(RecordingOkAuthenticator("password", "Basic"))
        .hostnameVerifier(RecordingHostnameVerifier())
        .build()

    val request = Request.Builder()
        .url("https://android.com/foo")
        .build()
    try {
      client.newCall(request).execute()
      fail()
    } catch (expected: ProtocolException) {
    }
  }

  /**
   * Confirm that we don't send the Proxy-Authorization header from the request to the proxy server.
   * We used to have that behavior but it is problematic because unrelated requests end up sharing
   * credentials. Worse, that approach leaks proxy credentials to the origin server.
   */
  @Test
  fun noPreemptiveProxyAuthorization() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true)
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply {
      mockResponse.socketPolicy = SocketPolicy.UPGRADE_TO_SSL_AT_END
    }
        .clearHeaders())
    server.enqueue(MockResponse()
        .setBody("response body"))

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .proxy(server.toProxyAddress())
        .hostnameVerifier(RecordingHostnameVerifier())
        .build()

    val request = Request.Builder()
        .url("https://android.com/foo")
        .header("Proxy-Authorization", "password")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body!!.string()).isEqualTo("response body")

    val connect1 = server.takeRequest()
    assertThat(connect1.getHeader("Proxy-Authorization")).isNull()

    val connect2 = server.takeRequest()
    assertThat(connect2.getHeader("Proxy-Authorization")).isEqualTo("password")
  }

  /** Confirm that we can send authentication information without being prompted first.  */
  @Test
  fun preemptiveProxyAuthentication() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true)
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply {
      mockResponse.socketPolicy = SocketPolicy.UPGRADE_TO_SSL_AT_END
    }
        .clearHeaders())
    server.enqueue(MockResponse()
        .setBody("encrypted response from the origin server"))

    val credential = Credentials.basic("jesse", "password1")

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .proxy(server.toProxyAddress())
        .hostnameVerifier(RecordingHostnameVerifier())
        .proxyAuthenticator(Authenticator { _, response ->
          assertThat(response.request.method).isEqualTo("CONNECT")
          assertThat(response.code).isEqualTo(HttpURLConnection.HTTP_PROXY_AUTH)
          assertThat(response.request.url.host).isEqualTo("android.com")

          val challenges = response.challenges()
          assertThat(challenges[0].scheme).isEqualTo("OkHttp-Preemptive")

          response.request.newBuilder()
              .header("Proxy-Authorization", credential)
              .build()
        })
        .build()

    val request = Request.Builder()
        .url("https://android.com/foo")
        .build()

    executeSynchronously(request).assertSuccessful()

    val connect = server.takeRequest()
    assertThat(connect.method).isEqualTo("CONNECT")
    assertThat(connect.getHeader("Proxy-Authorization")).isEqualTo(credential)
    assertThat(connect.path).isEqualTo("/")

    val get = server.takeRequest()
    assertThat(get.method).isEqualTo("GET")
    assertThat(get.getHeader("Proxy-Authorization")).isNull()
    assertThat(get.path).isEqualTo("/foo")
  }

  @Test
  fun preemptiveThenReactiveProxyAuthentication() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true)
    server.enqueue(MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_PROXY_AUTH)
        .addHeader("Proxy-Authenticate", "Basic realm=\"localhost\"")
        .setBody("proxy auth required"))
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply {
      mockResponse.socketPolicy = SocketPolicy.UPGRADE_TO_SSL_AT_END
    }
        .clearHeaders())
    server.enqueue(MockResponse())

    val challengeSchemes = ArrayList<String>()
    val credential = Credentials.basic("jesse", "password1")

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .proxy(server.toProxyAddress())
        .hostnameVerifier(RecordingHostnameVerifier())
        .proxyAuthenticator(Authenticator { _, response ->
          val challenges = response.challenges()
          challengeSchemes.add(challenges[0].scheme)
          response.request.newBuilder()
              .header("Proxy-Authorization", credential)
              .build()
        })
        .build()

    val request = Request.Builder()
        .url("https://android.com/foo")
        .build()

    executeSynchronously(request).assertSuccessful()

    val connect1 = server.takeRequest()
    assertThat(connect1.method).isEqualTo("CONNECT")
    assertThat(connect1.getHeader("Proxy-Authorization")).isEqualTo(credential)

    val connect2 = server.takeRequest()
    assertThat(connect2.method).isEqualTo("CONNECT")
    assertThat(connect2.getHeader("Proxy-Authorization")).isEqualTo(credential)

    assertThat(challengeSchemes).containsExactly("OkHttp-Preemptive", "Basic")
  }

  /** https://github.com/square/okhttp/issues/4915  */
  @Test @Ignore @Throws(Exception::class)
  fun proxyDisconnectsAfterRequest() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true)
    val mockResponse = MockResponse()
    server.enqueue(
        mockResponse.apply { mockResponse.socketPolicy = SocketPolicy.DISCONNECT_AFTER_REQUEST })

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .proxy(server.toProxyAddress())
        .build()

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    try {
      client.newCall(request).execute()
      fail()
    } catch (expected: IOException) {
    }
  }

  @Test
  fun interceptorGetsHttp2() {
    platform.assumeHttp2Support()

    enableProtocol(Protocol.HTTP_2)

    // Capture the protocol as it is observed by the interceptor.
    val protocolRef = AtomicReference<Protocol>()
    val interceptor = Interceptor { chain ->
      protocolRef.set(chain.connection()!!.protocol())
      chain.proceed(chain.request())
    }
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build()

    // Make an HTTP/2 request and confirm that the protocol matches.
    server.enqueue(MockResponse())
    executeSynchronously("/")
    assertThat(protocolRef.get()).isEqualTo(Protocol.HTTP_2)
  }

  @Test
  fun serverSendsInvalidResponseHeaders() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.status = "HTP/1.1 200 OK" })

    executeSynchronously("/")
        .assertFailure("Unexpected status line: HTP/1.1 200 OK")
  }

  @Test
  fun serverSendsInvalidCodeTooLarge() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.status = "HTTP/1.1 2147483648 OK" })

    executeSynchronously("/")
        .assertFailure("Unexpected status line: HTTP/1.1 2147483648 OK")
  }

  @Test
  fun serverSendsInvalidCodeNotANumber() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.status = "HTTP/1.1 00a OK" })

    executeSynchronously("/")
        .assertFailure("Unexpected status line: HTTP/1.1 00a OK")
  }

  @Test
  fun serverSendsUnnecessaryWhitespace() {
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply { mockResponse.status = " HTTP/1.1 200 OK" })

    executeSynchronously("/")
        .assertFailure("Unexpected status line:  HTTP/1.1 200 OK")
  }

  @Test
  fun requestHeaderNameWithSpaceForbidden() {
    try {
      Request.Builder().addHeader("a b", "c")
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo(
          "Unexpected char 0x20 at 1 in header name: a b")
    }
  }

  @Test
  fun requestHeaderNameWithTabForbidden() {
    try {
      Request.Builder().addHeader("a\tb", "c")
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo(
          "Unexpected char 0x09 at 1 in header name: a\tb")
    }
  }

  @Test
  fun responseHeaderNameWithSpacePermitted() {
    server.enqueue(MockResponse()
        .clearHeaders()
        .addHeader("content-length: 0")
        .addHeaderLenient("a b", "c"))

    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    assertThat(response.header("a b")).isEqualTo("c")
  }

  @Test
  fun responseHeaderNameWithTabPermitted() {
    server.enqueue(MockResponse()
        .clearHeaders()
        .addHeader("content-length: 0")
        .addHeaderLenient("a\tb", "c"))

    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    assertThat(response.header("a\tb")).isEqualTo("c")
  }

  @Test
  fun connectFails() {
    server.shutdown()

    executeSynchronously("/")
        .assertFailure(IOException::class.java)
  }

  @Test
  fun requestBodySurvivesRetries() {
    server.enqueue(MockResponse())

    // Enable a misconfigured proxy selector to guarantee that the request is retried.
    client = client.newBuilder()
        .proxySelector(FakeProxySelector()
            .addProxy(server2.toProxyAddress())
            .addProxy(Proxy.NO_PROXY))
        .build()
    server2.shutdown()

    val request = Request.Builder()
        .url(server.url("/"))
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build()

    executeSynchronously(request)
    assertThat(server.takeRequest().body.readUtf8()).isEqualTo("abc")
  }

  @Ignore // This may fail in DNS lookup, which we don't have timeouts for.
  @Test
  @Throws(Exception::class)
  fun invalidHost() {
    val request = Request.Builder()
        .url("http://1234.1.1.1/".toHttpUrl())
        .build()

    executeSynchronously(request)
        .assertFailure(UnknownHostException::class.java)
  }

  @Test
  fun uploadBodySmallChunkedEncoding() {
    upload(true, 1048576, 256)
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.bodySize).isEqualTo(1048576)
    assertThat(recordedRequest.chunkSizes).isNotEmpty
  }

  @Test
  fun uploadBodyLargeChunkedEncoding() {
    upload(true, 1048576, 65536)
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.bodySize).isEqualTo(1048576)
    assertThat(recordedRequest.chunkSizes).isNotEmpty
  }

  @Test
  fun uploadBodySmallFixedLength() {
    upload(false, 1048576, 256)
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.bodySize).isEqualTo(1048576)
    assertThat(recordedRequest.chunkSizes).isEmpty()
  }

  @Test
  fun uploadBodyLargeFixedLength() {
    upload(false, 1048576, 65536)
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.bodySize).isEqualTo(1048576)
    assertThat(recordedRequest.chunkSizes).isEmpty()
  }

  @Throws(Exception::class)
  private fun upload(
    chunked: Boolean,
    size: Int,
    writeSize: Int
  ) {
    server.enqueue(MockResponse())
    executeSynchronously(Request.Builder()
        .url(server.url("/"))
        .post(requestBody(chunked, size.toLong(), writeSize))
        .build())
  }

  /** https://github.com/square/okhttp/issues/2344  */
  @Test
  fun ipv6HostHasSquareBraces() {
    // Use a proxy to fake IPv6 connectivity, even if localhost doesn't have IPv6.
    server.useHttps(handshakeCertificates.sslSocketFactory(), true)
    server.protocols = listOf(Protocol.HTTP_1_1)
    val mockResponse = MockResponse()
    server.enqueue(mockResponse.apply {
      mockResponse.socketPolicy = SocketPolicy.UPGRADE_TO_SSL_AT_END
    }
        .clearHeaders())
    server.enqueue(MockResponse()
        .setBody("response body"))

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .hostnameVerifier(RecordingHostnameVerifier())
        .proxy(server.toProxyAddress())
        .build()

    val request = Request.Builder()
        .url("https://[::1]/")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body!!.string()).isEqualTo("response body")

    val connect = server.takeRequest()
    assertThat(connect.requestLine).isEqualTo("CONNECT [::1]:443 HTTP/1.1")
    assertThat(connect.getHeader("Host")).isEqualTo("[::1]:443")

    val get = server.takeRequest()
    assertThat(get.requestLine).isEqualTo("GET / HTTP/1.1")
    assertThat(get.getHeader("Host")).isEqualTo("[::1]")
  }

  private fun requestBody(chunked: Boolean, size: Long, writeSize: Int): RequestBody {
    val buffer = ByteArray(writeSize)
    Arrays.fill(buffer, 'x'.toByte())

    return object : RequestBody() {
      override fun contentType(): MediaType? {
        return "text/plain; charset=utf-8".toMediaType()
      }

      @Throws(IOException::class)
      override fun contentLength(): Long {
        return if (chunked) -1L else size
      }

      @Throws(IOException::class)
      override fun writeTo(sink: BufferedSink) {
        var count = 0
        while (count < size) {
          sink.write(buffer, 0, min(size - count, writeSize.toLong()).toInt())
          count += writeSize
        }
      }
    }
  }

  @Test
  fun emptyResponseBody() {
    server.enqueue(MockResponse()
        .addHeader("abc", "def"))
    executeSynchronously("/")
        .assertCode(200)
        .assertHeader("abc", "def")
        .assertBody("")
  }

  @Test
  fun leakedResponseBodyLogsStackTrace() {
    server.enqueue(MockResponse()
        .setBody("This gets leaked."))

    client = clientTestRule.newClientBuilder()
        .connectionPool(ConnectionPool(0, 10, TimeUnit.MILLISECONDS))
        .build()

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    val original = logger.level
    logger.level = Level.FINE
    logHandler.formatter = SimpleFormatter()
    try {
      client.newCall(request).execute() // Ignore the response so it gets leaked then GC'd.
      awaitGarbageCollection()

      val message = logHandler.take()
      assertThat(message).contains("A connection to " + server.url("/") + " was leaked." +
          " Did you forget to close a response body?")
      assertThat(message).contains("okhttp3.RealCall.execute(")
      assertThat(message).contains("okhttp3.CallTest.leakedResponseBodyLogsStackTrace(")
    } finally {
      logger.level = original
    }
  }

  @Test
  fun asyncLeakedResponseBodyLogsStackTrace() {
    server.enqueue(MockResponse()
        .setBody("This gets leaked."))

    client = clientTestRule.newClientBuilder()
        .connectionPool(ConnectionPool(0, 10, TimeUnit.MILLISECONDS))
        .build()

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    val original = logger.level
    logger.level = Level.FINE
    logHandler.formatter = SimpleFormatter()
    try {
      val latch = CountDownLatch(1)
      client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          fail()
        }

        @Throws(IOException::class)
        override fun onResponse(call: Call, response: Response) {
          // Ignore the response so it gets leaked then GC'd.
          latch.countDown()
        }
      })
      latch.await()
      // There's some flakiness when triggering a GC for objects in a separate thread. Adding a
      // small delay appears to ensure the objects will get GC'd.
      Thread.sleep(200)
      awaitGarbageCollection()

      val message = logHandler.take()
      assertThat(message).contains("A connection to " + server.url("/") + " was leaked." +
          " Did you forget to close a response body?")
      assertThat(message).contains("okhttp3.RealCall.enqueue(")
      assertThat(message).contains("okhttp3.CallTest.asyncLeakedResponseBodyLogsStackTrace(")
    } finally {
      logger.level = original
    }
  }

  @Test
  fun failedAuthenticatorReleasesConnection() {
    server.enqueue(MockResponse()
        .setResponseCode(401))

    client = client.newBuilder()
        .authenticator(Authenticator { _, _ -> throw IOException("IOException!") })
        .build()

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    executeSynchronously(request)
        .assertFailure(IOException::class.java)

    assertThat(client.connectionPool.idleConnectionCount()).isEqualTo(1)
  }

  @Test
  fun failedProxyAuthenticatorReleasesConnection() {
    server.enqueue(MockResponse()
        .setResponseCode(407))

    client = client.newBuilder()
        .proxyAuthenticator(Authenticator { _, _ -> throw IOException("IOException!") })
        .build()

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    executeSynchronously(request)
        .assertFailure(IOException::class.java)

    assertThat(client.connectionPool.idleConnectionCount()).isEqualTo(1)
  }

  @Test
  fun httpsWithIpAddress() {
    val localIpAddress = InetAddress.getLoopbackAddress().hostAddress

    // Create a certificate with an IP address in the subject alt name.
    val heldCertificate = HeldCertificate.Builder()
        .commonName("example.com")
        .addSubjectAlternativeName(localIpAddress)
        .build()
    val handshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .addTrustedCertificate(heldCertificate.certificate)
        .build()

    // Use that certificate on the server and trust it on the client.
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .hostnameVerifier(RecordingHostnameVerifier())
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    // Make a request.
    server.enqueue(MockResponse())
    val url = server.url("/").newBuilder()
        .host(localIpAddress)
        .build()
    val request = Request.Builder()
        .url(url)
        .build()
    executeSynchronously(request)
        .assertCode(200)

    // Confirm that the IP address was used in the host header.
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.getHeader("Host")).isEqualTo(
        localIpAddress + ":" + server.port)
  }

  @Test
  fun postWithFileNotFound() {
    val called = AtomicInteger(0)

    val body = object : RequestBody() {
      override fun contentType(): MediaType? {
        return "application/octet-stream".toMediaType()
      }

      @Throws(IOException::class)
      override fun writeTo(sink: BufferedSink) {
        called.incrementAndGet()
        throw FileNotFoundException()
      }
    }

    val request = Request.Builder()
        .url(server.url("/"))
        .post(body)
        .build()

    client = client.newBuilder()
        .dns(DoubleInetAddressDns())
        .build()

    executeSynchronously(request)
        .assertFailure(FileNotFoundException::class.java)

    assertThat(called.get()).isEqualTo(1L)
  }

  @Test
  fun clientReadsHeadersDataTrailersHttp1ChunkedTransferEncoding() {
    val chunkedBody = MockResponse()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .setChunkedBody("HelloBonjour", 1024)
    val mockResponse = chunkedBody.apply { chunkedBody.trailers = headersOf("trailers", "boom") }
    server.enqueue(mockResponse)

    val call = client.newCall(Request.Builder()
        .url(server.url("/"))
        .build())

    val response = call.execute()
    val source = response.body!!.source()

    assertThat(response.header("h1")).isEqualTo("v1")
    assertThat(response.header("h2")).isEqualTo("v2")

    assertThat(source.readUtf8(5)).isEqualTo("Hello")
    assertThat(source.readUtf8(7)).isEqualTo("Bonjour")

    assertThat(source.exhausted()).isTrue
    assertThat(response.trailers()).isEqualTo(headersOf("trailers", "boom"))
  }

  @Test
  fun clientReadsHeadersDataTrailersHttp2() {
    platform.assumeHttp2Support()

    val body = MockResponse()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .setBody("HelloBonjour")
    val mockResponse = body.apply { body.trailers = headersOf("trailers", "boom") }
    server.enqueue(mockResponse)
    enableProtocol(Protocol.HTTP_2)

    val call = client.newCall(Request.Builder()
        .url(server.url("/"))
        .build())

    call.execute().use { response ->
      val source = response.body!!.source()

      assertThat(response.header("h1")).isEqualTo("v1")
      assertThat(response.header("h2")).isEqualTo("v2")

      assertThat(source.readUtf8(5)).isEqualTo("Hello")
      assertThat(source.readUtf8(7)).isEqualTo("Bonjour")

      assertThat(source.exhausted()).isTrue
      assertThat(response.trailers()).isEqualTo(headersOf("trailers", "boom"))
    }
  }

  @Test
  fun requestBodyThrowsUnrelatedToNetwork() {
    server.enqueue(MockResponse())

    val request = Request.Builder()
        .url(server.url("/"))
        .post(object : RequestBody() {
          override fun contentType(): MediaType? {
            return null
          }

          @Throws(IOException::class)
          override fun writeTo(sink: BufferedSink) {
            throw IOException("boom")
          }
        })
        .build()

    executeSynchronously(request).assertFailure("boom")
  }

  @Test
  fun requestBodyThrowsUnrelatedToNetwork_HTTP2() {
    enableProtocol(Protocol.HTTP_2)
    requestBodyThrowsUnrelatedToNetwork()
  }

  /** https://github.com/square/okhttp/issues/4583  */
  @Test
  fun lateCancelCallsOnFailure() {
    server.enqueue(MockResponse()
        .setBody("abc"))

    val closed = AtomicBoolean()

    client = client.newBuilder()
        .addInterceptor(object : Interceptor {
          @Throws(IOException::class)
          override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            chain.call().cancel() // Cancel after we have the response.
            val closeTrackingSource = object : ForwardingSource(response.body!!.source()) {
              @Throws(IOException::class)
              override fun close() {
                closed.set(true)
                super.close()
              }
            }
            return response.newBuilder()
                .body(closeTrackingSource.buffer().asResponseBody(null, -1L))
                .build()
          }
        })
        .build()

    executeSynchronously("/").assertFailure("Canceled")
    assertThat(closed.get()).isTrue
  }

  private fun makeFailingCall() {
    val requestBody = object : RequestBody() {
      override fun contentType(): MediaType? {
        return null
      }

      @Throws(IOException::class)
      override fun contentLength(): Long {
        return 1
      }

      @Throws(IOException::class)
      override fun writeTo(sink: BufferedSink) {
        throw IOException("write body fail!")
      }
    }
    val nonRetryingClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .build()
    val call = nonRetryingClient.newCall(Request.Builder()
        .url(server.url("/"))
        .post(requestBody)
        .build())
    try {
      call.execute()
      fail()
    } catch (expected: IOException) {
      assertThat(expected.message).isEqualTo("write body fail!")
    }
  }

  @Throws(IOException::class)
  private fun executeSynchronously(path: String, vararg headers: String): RecordedResponse {
    val builder = Request.Builder()
    builder.url(server.url(path))
    var i = 0
    val size = headers.size
    while (i < size) {
      builder.addHeader(headers[i], headers[i + 1])
      i += 2
    }
    return executeSynchronously(builder.build())
  }

  @Throws(IOException::class)
  private fun executeSynchronously(request: Request): RecordedResponse {
    val call = client.newCall(request)
    return try {
      val response = call.execute()
      val bodyString = response.body!!.string()
      RecordedResponse(request, response, null, bodyString, null)
    } catch (e: IOException) {
      RecordedResponse(request, null, null, null, e)
    }
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. `-Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317`
   */
  private fun enableProtocol(protocol: Protocol) {
    enableTls()
    client = client.newBuilder()
        .protocols(listOf(protocol, Protocol.HTTP_1_1))
        .build()
    server.protocols = client.protocols
  }

  private fun enableTls() {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .hostnameVerifier(RecordingHostnameVerifier())
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }

  @Throws(IOException::class)
  private fun gzip(data: String): Buffer {
    val result = Buffer()
    val sink = GzipSink(result).buffer()
    sink.writeUtf8(data)
    sink.close()
    return result
  }

  private fun cancelLater(call: Call, delay: Long): Thread {
    val thread = object : Thread("canceler") {
      override fun run() {
        try {
          sleep(delay)
        } catch (e: InterruptedException) {
          throw AssertionError()
        }

        call.cancel()
      }
    }
    thread.start()
    return thread
  }

  private fun socketFactoryWithCipherSuite(
    sslSocketFactory: SSLSocketFactory,
    cipherSuite: CipherSuite
  ): SSLSocketFactory {
    return object : DelegatingSSLSocketFactory(sslSocketFactory) {
      @Throws(IOException::class)
      override fun configureSocket(sslSocket: SSLSocket): SSLSocket {
        sslSocket.enabledCipherSuites = arrayOf(cipherSuite.javaName)
        return super.configureSocket(sslSocket)
      }
    }
  }

  private class RecordingSSLSocketFactory(delegate: SSLSocketFactory) : DelegatingSSLSocketFactory(
      delegate) {

    private val socketsCreated = ArrayList<SSLSocket>()

    @Throws(IOException::class)
    override fun configureSocket(sslSocket: SSLSocket): SSLSocket {
      socketsCreated.add(sslSocket)
      return sslSocket
    }

    fun getSocketsCreated(): List<SSLSocket> {
      return socketsCreated
    }
  }

  /**
   * Used during tests that involve TLS connection fallback attempts. OkHttp includes the
   * TLS_FALLBACK_SCSV cipher on fallback connections. See [FallbackTestClientSocketFactory]
   * for details.
   */
  private fun suppressTlsFallbackClientSocketFactory(): FallbackTestClientSocketFactory {
    return FallbackTestClientSocketFactory(handshakeCertificates.sslSocketFactory())
  }
}
