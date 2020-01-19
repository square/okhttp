/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.internal.ws

import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.TimeUnit
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.concurrent.TaskRunner
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.Pipe
import okio.buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

class RealWebSocketTest {
  // NOTE: Fields are named 'client' and 'server' for cognitive simplicity. This differentiation has
  // zero effect on the behavior of the WebSocket API which is why tests are only written once
  // from the perspective of a single peer.

  private val random = Random(0)
  private val client2Server = Pipe(1024L)
  private val server2client = Pipe(1024L)

  private val client = TestStreams(true, server2client, client2Server)
  private val server = TestStreams(false, client2Server, server2client)

  @Before @Throws(IOException::class)
  fun setUp() {
    client.initWebSocket(random, 0)
    server.initWebSocket(random, 0)
  }

  @After @Throws(Exception::class)
  fun tearDown() {
    client.listener.assertExhausted()
    server.listener.assertExhausted()
    server.source.close()
    client.source.close()
    server.webSocket!!.tearDown()
    client.webSocket!!.tearDown()
  }

  @Test @Throws(IOException::class)
  fun close() {
    client.webSocket!!.close(1000, "Hello!")
    // This will trigger a close response.
    assertThat(server.processNextFrame()).isFalse()
    server.listener.assertClosing(1000, "Hello!")
    server.webSocket!!.close(1000, "Goodbye!")
    assertThat(client.processNextFrame()).isFalse()
    client.listener.assertClosing(1000, "Goodbye!")
    server.listener.assertClosed(1000, "Hello!")
    client.listener.assertClosed(1000, "Goodbye!")
  }

  @Test @Throws(IOException::class)
  fun clientCloseThenMethodsReturnFalse() {
    client.webSocket!!.close(1000, "Hello!")

    assertThat(client.webSocket!!.close(1000, "Hello!")).isFalse()
    assertThat(client.webSocket!!.send("Hello!")).isFalse()
  }

  @Test @Throws(IOException::class)
  fun clientCloseWith0Fails() {
    try {
      client.webSocket!!.close(0, null)
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat("Code must be in range [1000,5000): 0").isEqualTo(expected.message)
    }
  }

  @Test @Throws(IOException::class)
  fun afterSocketClosedPingFailsWebSocket() {
    client2Server.source.close()
    client.webSocket!!.pong("Ping!".encodeUtf8())
    client.listener.assertFailure(IOException::class.java, "source is closed")

    assertThat(client.webSocket!!.send("Hello!")).isFalse()
  }

  @Test @Throws(IOException::class)
  fun socketClosedDuringMessageKillsWebSocket() {
    client2Server.source.close()

    assertThat(client.webSocket!!.send("Hello!")).isTrue()
    client.listener.assertFailure(IOException::class.java, "source is closed")

    // A failed write prevents further use of the WebSocket instance.
    assertThat(client.webSocket!!.send("Hello!")).isFalse()
    assertThat(client.webSocket!!.pong("Ping!".encodeUtf8())).isFalse()
  }

  @Test @Throws(IOException::class)
  fun serverCloseThenWritingPingSucceeds() {
    server.webSocket!!.close(1000, "Hello!")
    client.processNextFrame()
    client.listener.assertClosing(1000, "Hello!")

    assertThat(client.webSocket!!.pong("Pong?".encodeUtf8())).isTrue()
  }

  @Test @Throws(IOException::class)
  fun clientCanWriteMessagesAfterServerClose() {
    server.webSocket!!.close(1000, "Hello!")
    client.processNextFrame()
    client.listener.assertClosing(1000, "Hello!")

    assertThat(client.webSocket!!.send("Hi!")).isTrue()
    server.processNextFrame()
    server.listener.assertTextMessage("Hi!")
  }

  @Test @Throws(IOException::class)
  fun serverCloseThenClientClose() {
    server.webSocket!!.close(1000, "Hello!")
    client.processNextFrame()
    client.listener.assertClosing(1000, "Hello!")
    assertThat(client.webSocket!!.close(1000, "Bye!")).isTrue()
  }

  @Test @Throws(IOException::class)
  fun emptyCloseInitiatesShutdown() {
    server.sink.write("8800".decodeHex()).emit() // Close without code.
    client.processNextFrame()
    client.listener.assertClosing(1005, "")

    assertThat(client.webSocket!!.close(1000, "Bye!")).isTrue()
    server.processNextFrame()
    server.listener.assertClosing(1000, "Bye!")

    client.listener.assertClosed(1005, "")
  }

  @Test @Throws(IOException::class)
  fun clientCloseClosesConnection() {
    client.webSocket!!.close(1000, "Hello!")
    assertThat(client.closed).isFalse()
    server.processNextFrame() // Read client closing, send server close.
    server.listener.assertClosing(1000, "Hello!")

    server.webSocket!!.close(1000, "Goodbye!")
    client.processNextFrame() // Read server closing, close connection.
    assertThat(client.closed).isTrue()
    client.listener.assertClosing(1000, "Goodbye!")

    // Server and client both finished closing, connection is closed.
    server.listener.assertClosed(1000, "Hello!")
    client.listener.assertClosed(1000, "Goodbye!")
  }

  @Test @Throws(IOException::class)
  fun serverCloseClosesConnection() {
    server.webSocket!!.close(1000, "Hello!")

    client.processNextFrame() // Read server close, send client close, close connection.
    assertThat(client.closed).isFalse()
    client.listener.assertClosing(1000, "Hello!")

    client.webSocket!!.close(1000, "Hello!")
    server.processNextFrame()
    server.listener.assertClosing(1000, "Hello!")

    client.listener.assertClosed(1000, "Hello!")
    server.listener.assertClosed(1000, "Hello!")
  }

  @Test @Throws(Exception::class)
  fun clientAndServerCloseClosesConnection() {
    // Send close from both sides at the same time.
    server.webSocket!!.close(1000, "Hello!")
    client.processNextFrame() // Read close, close connection close.

    assertThat(client.closed).isFalse()
    client.webSocket!!.close(1000, "Hi!")
    server.processNextFrame()

    client.listener.assertClosing(1000, "Hello!")
    server.listener.assertClosing(1000, "Hi!")
    client.listener.assertClosed(1000, "Hello!")
    server.listener.assertClosed(1000, "Hi!")
    client.webSocket!!.awaitTermination(5, TimeUnit.SECONDS)
    assertThat(client.closed).isTrue()

    server.listener.assertExhausted() // Client should not have sent second close.
    client.listener.assertExhausted() // Server should not have sent second close.
  }

  @Test @Throws(IOException::class)
  fun serverCloseBreaksReadMessageLoop() {
    server.webSocket!!.send("Hello!")
    server.webSocket!!.close(1000, "Bye!")
    assertThat(client.processNextFrame()).isTrue()
    client.listener.assertTextMessage("Hello!")
    assertThat(client.processNextFrame()).isFalse()
    client.listener.assertClosing(1000, "Bye!")
  }

  @Test @Throws(IOException::class)
  fun protocolErrorBeforeCloseSendsFailure() {
    server.sink.write("0a00".decodeHex()).emit() // Invalid non-final ping frame.

    client.processNextFrame() // Detects error, send close, close connection.
    assertThat(client.closed).isTrue()
    client.listener.assertFailure(ProtocolException::class.java, "Control frames must be final.")

    server.processNextFrame()
    server.listener.assertFailure(EOFException::class.java)
  }

  @Test @Throws(IOException::class)
  fun protocolErrorInCloseResponseClosesConnection() {
    client.webSocket!!.close(1000, "Hello")
    server.processNextFrame()
    // Not closed until close reply is received.
    assertThat(client.closed).isFalse()

    // Manually write an invalid masked close frame.
    server.sink.write("888760b420bb635c68de0cd84f".decodeHex()).emit()

    client.processNextFrame() // Detects error, disconnects immediately since close already sent.
    assertThat(client.closed).isTrue()
    client.listener.assertFailure(
      ProtocolException::class.java, "Server-sent frames must not be masked.")

    server.listener.assertClosing(1000, "Hello")
    server.listener.assertExhausted() // Client should not have sent second close.
  }

  @Test @Throws(IOException::class)
  fun protocolErrorAfterCloseDoesNotSendClose() {
    client.webSocket!!.close(1000, "Hello!")
    server.processNextFrame()

    // Not closed until close reply is received.
    assertThat(client.closed).isFalse()
    server.sink.write("0a00".decodeHex()).emit() // Invalid non-final ping frame.

    client.processNextFrame() // Detects error, disconnects immediately since close already sent.
    assertThat(client.closed).isTrue()
    client.listener.assertFailure(ProtocolException::class.java, "Control frames must be final.")

    server.listener.assertClosing(1000, "Hello!")

    server.listener.assertExhausted() // Client should not have sent second close.
  }

  @Test @Throws(IOException::class)
  fun networkErrorReportedAsFailure() {
    server.sink.close()
    client.processNextFrame()
    client.listener.assertFailure(EOFException::class.java)
  }

  @Test @Throws(IOException::class)
  fun closeThrowingFailsConnection() {
    client2Server.source.close()
    client.webSocket!!.close(1000, null)
    client.listener.assertFailure(IOException::class.java, "source is closed")
  }

  @Ignore // TODO(jwilson): come up with a way to test unchecked exceptions on the writer thread.
  @Test
  @Throws(IOException::class)
  fun closeMessageAndConnectionCloseThrowingDoesNotMaskOriginal() {
    client.sink.close()
    client.closeThrows = true

    client.webSocket!!.close(1000, "Bye!")
    client.listener.assertFailure(IOException::class.java, "failure")
    assertThat(client.closed).isTrue()
  }

  @Ignore // TODO(jwilson): come up with a way to test unchecked exceptions on the writer thread.
  @Test
  @Throws(IOException::class)
  fun peerConnectionCloseThrowingPropagates() {
    client.closeThrows = true

    server.webSocket!!.close(1000, "Bye from Server!")
    client.processNextFrame()
    client.listener.assertClosing(1000, "Bye from Server!")

    client.webSocket!!.close(1000, "Bye from Client!")
    server.processNextFrame()
    server.listener.assertClosing(1000, "Bye from Client!")
  }

  @Test @Throws(IOException::class)
  fun pingOnInterval() {
    val startNanos = System.nanoTime()
    client.initWebSocket(random, 500)

    server.processNextFrame() // Ping.
    client.processNextFrame() // Pong.
    val elapsedUntilPing1 = System.nanoTime() - startNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilPing1).toDouble())
      .isCloseTo(500.toDouble(), offset(250.0))

    server.processNextFrame() // Ping.
    client.processNextFrame() // Pong.
    val elapsedUntilPing2 = System.nanoTime() - startNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilPing2).toDouble())
      .isCloseTo(1000.toDouble(), offset(250.0))

    server.processNextFrame() // Ping.
    client.processNextFrame() // Pong.
    val elapsedUntilPing3 = System.nanoTime() - startNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilPing3).toDouble())
      .isCloseTo(1500.toDouble(), offset(250.0))
  }

  @Test @Throws(IOException::class)
  fun unacknowledgedPingFailsConnection() {
    val startNanos = System.nanoTime()
    client.initWebSocket(random, 500)

    // Don't process the ping and pong frames!
    client.listener.assertFailure(SocketTimeoutException::class.java,
      "sent ping but didn't receive pong within 500ms (after 0 successful ping/pongs)")
    val elapsedUntilFailure = System.nanoTime() - startNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure).toDouble())
      .isCloseTo(1000.toDouble(), offset(250.0))
  }

  @Test @Throws(IOException::class)
  fun unexpectedPongsDoNotInterfereWithFailureDetection() {
    val startNanos = System.nanoTime()
    client.initWebSocket(random, 500)

    // At 0ms the server sends 3 unexpected pongs. The client accepts 'em and ignores em.
    server.webSocket!!.pong("pong 1".encodeUtf8())
    client.processNextFrame()
    server.webSocket!!.pong("pong 2".encodeUtf8())
    client.processNextFrame()
    server.webSocket!!.pong("pong 3".encodeUtf8())
    client.processNextFrame()

    // After 500ms the client automatically pings and the server pongs back.
    server.processNextFrame() // Ping.
    client.processNextFrame() // Pong.
    val elapsedUntilPing = System.nanoTime() - startNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilPing).toDouble())
      .isCloseTo(500.toDouble(), offset(250.0))

    // After 1000ms the client will attempt a ping 2, but we don't process it. That'll cause the
    // client to fail at 1500ms when it's time to send ping 3 because pong 2 hasn't been received.
    client.listener.assertFailure(SocketTimeoutException::class.java,
      "sent ping but didn't receive pong within 500ms (after 1 successful ping/pongs)")
    val elapsedUntilFailure = System.nanoTime() - startNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure).toDouble())
      .isCloseTo(1500.toDouble(), offset(250.0))
  }

  @Test @Throws(IOException::class)
  fun writesUncompressedMessageIfCompressionDisabled() {
    server.initWebSocket(random, 0, compressionEnabled = false, contextTakeover = false)

    server.webSocket!!.send("Hello".encodeUtf8())
    server.webSocket!!.writeOneFrame()

    val buffer = Buffer()
    server2client.source.read(buffer, Integer.MAX_VALUE.toLong())

    assertThat(buffer.readByteString())
      .isEqualTo("820548656c6c6f".decodeHex()) // Uncompressed Hello
  }

  @Test @Throws(IOException::class)
  fun writesUncompressedMessageIfNoContextTakeoverAndCompressionDoesNotReduceSize() {
    server.initWebSocket(random, 0, compressionEnabled = true, contextTakeover = false)

    // Message is 5 bytes long, compressed would be 7
    server.webSocket!!.send("Hello".encodeUtf8())
    server.webSocket!!.writeOneFrame()

    val buffer = Buffer()
    server2client.source.read(buffer, Integer.MAX_VALUE.toLong())

    assertThat(buffer.readByteString())
      .isEqualTo("820548656c6c6f".decodeHex()) // Uncompressed
  }

  @Test @Throws(IOException::class)
  fun writesCompressedMessageIfNoContextTakeoverAndCompressionReducesSize() {
    server.initWebSocket(random, 0, compressionEnabled = true, contextTakeover = false)

    // Message is 17 bytes long, compressed is 11
    server.webSocket!!.send("Hello Hello Hello".encodeUtf8())
    server.webSocket!!.writeOneFrame()

    val buffer = Buffer()
    server2client.source.read(buffer, Integer.MAX_VALUE.toLong())

    assertThat(buffer.readByteString())
      .isEqualTo("c20bf248cdc9c957f040900000".decodeHex()) // Compressed
  }

  @Test @Throws(IOException::class)
  fun writesCompressedMessageIfContextTakeover() {
    server.initWebSocket(random, 0, compressionEnabled = true, contextTakeover = true)

    // Message is 5 bytes long, compressed is 7
    server.webSocket!!.send("Hello".encodeUtf8())
    server.webSocket!!.writeOneFrame()

    val buffer = Buffer()
    server2client.source.read(buffer, Integer.MAX_VALUE.toLong())

    assertThat(buffer.readByteString())
      .isEqualTo("c207f248cdc9c90700".decodeHex()) // Compressed
  }

  /** One peer's streams, listener, and web socket in the test.  */
  private class TestStreams(client: Boolean, source: Pipe, sink: Pipe) :
    RealWebSocket.Streams(client, source.source.buffer(), sink.sink.buffer()) {
    internal val name: String = if (client) "client" else "server"
    internal val listener: WebSocketRecorder = WebSocketRecorder(name)
    internal var webSocket: RealWebSocket? = null
    internal var closeThrows: Boolean = false
    internal var closed: Boolean = false

    @Throws(IOException::class)
    @JvmOverloads fun initWebSocket(
      random: Random,
      pingIntervalMillis: Int,
      compressionEnabled: Boolean = false,
      contextTakeover: Boolean = false
    ) {
      val url = "http://example.com/websocket"
      val response = Response.Builder()
        .code(101)
        .message("OK")
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .build()
      webSocket = RealWebSocket(
        TaskRunner.INSTANCE, response.request, listener, random,
        pingIntervalMillis.toLong(), compressionEnabled, contextTakeover)
      webSocket!!.initReaderAndWriter(name, this)
    }

    @Throws(IOException::class)
    fun processNextFrame(): Boolean {
      return webSocket!!.processNextFrame()
    }

    @Throws(IOException::class)
    override fun close() {
      source.close()
      sink.close()
      if (closed) {
        throw AssertionError("Already closed")
      }
      closed = true

      if (closeThrows) {
        throw RuntimeException("Oops!")
      }
    }
  }
}
