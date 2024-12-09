/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.cache2

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import okhttp3.TestUtil.threadFactory
import okhttp3.internal.cache2.Relay.Companion.edit
import okhttp3.internal.cache2.Relay.Companion.read
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.Pipe
import okio.Source
import okio.buffer
import okio.source
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("Slowish")
class RelayTest {
  @TempDir
  var tempDir: File? = null
  private val executor = Executors.newCachedThreadPool(threadFactory("RelayTest"))
  private val metadata: ByteString = "great metadata!".encodeUtf8()
  private lateinit var file: File

  @BeforeEach
  fun setUp() {
    file = File(tempDir, "test")
  }

  @AfterEach
  fun tearDown() {
    executor.shutdown()
  }

  @Test
  fun singleSource() {
    val upstream = Buffer()
    upstream.writeUtf8("abcdefghijklm")
    val relay = edit(file, upstream, metadata, 1024)
    val source = relay.newSource()
    val sourceBuffer = Buffer()
    assertThat(source!!.read(sourceBuffer, 5)).isEqualTo(5)
    assertThat(sourceBuffer.readUtf8()).isEqualTo("abcde")
    assertThat(source.read(sourceBuffer, 1024)).isEqualTo(8)
    assertThat(sourceBuffer.readUtf8()).isEqualTo("fghijklm")
    assertThat(source.read(sourceBuffer, 1024)).isEqualTo(-1)
    assertThat(sourceBuffer.size).isEqualTo(0)
    source.close()
    assertThat(relay.isClosed).isTrue()
    assertFile(Relay.PREFIX_CLEAN, 13L, metadata.size, "abcdefghijklm", metadata)
  }

  @Test
  fun multipleSources() {
    val upstream = Buffer()
    upstream.writeUtf8("abcdefghijklm")
    val relay = edit(file, upstream, metadata, 1024)
    val source1 = relay.newSource()!!.buffer()
    val source2 = relay.newSource()!!.buffer()
    assertThat(source1.readUtf8()).isEqualTo("abcdefghijklm")
    assertThat(source2.readUtf8()).isEqualTo("abcdefghijklm")
    source1.close()
    source2.close()
    assertThat(relay.isClosed).isTrue()
    assertFile(Relay.PREFIX_CLEAN, 13L, metadata.size, "abcdefghijklm", metadata)
  }

  @Test
  fun readFromBuffer() {
    val upstream = Buffer()
    upstream.writeUtf8("abcdefghij")
    val relay = edit(file, upstream, metadata, 5)
    val source1 = relay.newSource()!!.buffer()
    val source2 = relay.newSource()!!.buffer()
    assertThat(source1.readUtf8(5)).isEqualTo("abcde")
    assertThat(source2.readUtf8(5)).isEqualTo("abcde")
    assertThat(source2.readUtf8(5)).isEqualTo("fghij")
    assertThat(source1.readUtf8(5)).isEqualTo("fghij")
    assertThat(source1.exhausted()).isTrue()
    assertThat(source2.exhausted()).isTrue()
    source1.close()
    source2.close()
    assertThat(relay.isClosed).isTrue()
    assertFile(Relay.PREFIX_CLEAN, 10L, metadata.size, "abcdefghij", metadata)
  }

  @Test
  fun readFromFile() {
    val upstream = Buffer()
    upstream.writeUtf8("abcdefghijklmnopqrst")
    val relay = edit(file, upstream, metadata, 5)
    val source1 = relay.newSource()!!.buffer()
    val source2 = relay.newSource()!!.buffer()
    assertThat(source1.readUtf8(10)).isEqualTo("abcdefghij")
    assertThat(source2.readUtf8(10)).isEqualTo("abcdefghij")
    assertThat(source2.readUtf8(10)).isEqualTo("klmnopqrst")
    assertThat(source1.readUtf8(10)).isEqualTo("klmnopqrst")
    assertThat(source1.exhausted()).isTrue()
    assertThat(source2.exhausted()).isTrue()
    source1.close()
    source2.close()
    assertThat(relay.isClosed).isTrue()
    assertFile(Relay.PREFIX_CLEAN, 20L, metadata.size, "abcdefghijklmnopqrst", metadata)
  }

  @Test
  fun readAfterEdit() {
    val upstream = Buffer()
    upstream.writeUtf8("abcdefghij")
    val relay1 = edit(file, upstream, metadata, 5)
    val source1 = relay1.newSource()!!.buffer()
    assertThat(source1.readUtf8(10)).isEqualTo("abcdefghij")
    assertThat(source1.exhausted()).isTrue()
    source1.close()
    assertThat(relay1.isClosed).isTrue()

    // Since relay1 is closed, new sources cannot be created.
    assertThat(relay1.newSource()).isNull()
    val relay2 = read(file)
    assertThat(relay2.metadata()).isEqualTo(metadata)
    val source2 = relay2.newSource()!!.buffer()
    assertThat(source2.readUtf8(10)).isEqualTo("abcdefghij")
    assertThat(source2.exhausted()).isTrue()
    source2.close()
    assertThat(relay2.isClosed).isTrue()

    // Since relay2 is closed, new sources cannot be created.
    assertThat(relay2.newSource()).isNull()
    assertFile(Relay.PREFIX_CLEAN, 10L, metadata.size, "abcdefghij", metadata)
  }

  @Test
  fun closeBeforeExhaustLeavesDirtyFile() {
    val upstream = Buffer()
    upstream.writeUtf8("abcdefghij")
    val relay1 = edit(file, upstream, metadata, 5)
    val source1 = relay1.newSource()!!.buffer()
    assertThat(source1.readUtf8(10)).isEqualTo("abcdefghij")
    source1.close() // Not exhausted!
    assertThat(relay1.isClosed).isTrue()
    assertFailsWith<IOException> {
      read(file)
    }.also { expected ->
      assertThat(expected.message).isEqualTo("unreadable cache file")
    }
    assertFile(Relay.PREFIX_DIRTY, -1L, -1, null, null)
  }

  @Test
  fun redundantCallsToCloseAreIgnored() {
    val upstream = Buffer()
    upstream.writeUtf8("abcde")
    val relay = edit(file, upstream, metadata, 1024)
    val source1 = relay.newSource()
    val source2 = relay.newSource()
    source1!!.close()
    source1.close() // Unnecessary. Shouldn't decrement the reference count.
    assertThat(relay.isClosed).isFalse()
    source2!!.close()
    assertThat(relay.isClosed).isTrue()
    assertFile(Relay.PREFIX_DIRTY, -1L, -1, null, null)
  }

  @Test
  fun racingReaders() {
    val pipe = Pipe(1024)
    val sink = pipe.sink.buffer()
    val relay = edit(file, pipe.source, metadata, 5)
    val future1 = executor.submit(sourceReader(relay.newSource()))
    val future2 = executor.submit(sourceReader(relay.newSource()))
    Thread.sleep(500)
    sink.writeUtf8("abcdefghij")
    Thread.sleep(500)
    sink.writeUtf8("klmnopqrst")
    sink.close()
    assertThat<ByteString>(future1.get())
      .isEqualTo("abcdefghijklmnopqrst".encodeUtf8())
    assertThat<ByteString>(future2.get())
      .isEqualTo("abcdefghijklmnopqrst".encodeUtf8())
    assertThat(relay.isClosed).isTrue()
    assertFile(Relay.PREFIX_CLEAN, 20L, metadata.size, "abcdefghijklmnopqrst", metadata)
  }

  /** Returns a callable that reads all of source, closes it, and returns the bytes.  */
  private fun sourceReader(source: Source?): Callable<ByteString> {
    return Callable {
      val buffer = Buffer()
      while (source!!.read(buffer, 16384) != -1L) {
      }
      source.close()
      buffer.readByteString()
    }
  }

  private fun assertFile(
    prefix: ByteString,
    upstreamSize: Long,
    metadataSize: Int,
    upstream: String?,
    metadata: ByteString?,
  ) {
    val source = file.source().buffer()
    assertThat(source.readByteString(prefix.size.toLong())).isEqualTo(prefix)
    assertThat(source.readLong()).isEqualTo(upstreamSize)
    assertThat(source.readLong()).isEqualTo(metadataSize.toLong())
    if (upstream != null) {
      assertThat(source.readUtf8(upstreamSize)).isEqualTo(upstream)
    }
    if (metadata != null) {
      assertThat(source.readByteString(metadataSize.toLong())).isEqualTo(metadata)
    }
    source.close()
  }
}
