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
package okhttp3.internal.cache2;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import okio.Pipe;
import okio.Source;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class RelayTest {
  @Rule public final TemporaryFolder tempDir = new TemporaryFolder();

  private ExecutorService executor = Executors.newCachedThreadPool();
  private ByteString metadata = ByteString.encodeUtf8("great metadata!");
  private File file;

  @Before public void setUp() throws Exception {
    file = tempDir.newFile();
  }

  @After public void tearDown() throws Exception {
    executor.shutdown();
  }

  @Test public void singleSource() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghijklm");

    Relay relay = Relay.Companion.edit(file, upstream, metadata, 1024);
    Source source = relay.newSource();
    Buffer sourceBuffer = new Buffer();

    assertThat(source.read(sourceBuffer, 5)).isEqualTo(5);
    assertThat(sourceBuffer.readUtf8()).isEqualTo("abcde");

    assertThat(source.read(sourceBuffer, 1024)).isEqualTo(8);
    assertThat(sourceBuffer.readUtf8()).isEqualTo("fghijklm");

    assertThat(source.read(sourceBuffer, 1024)).isEqualTo(-1);
    assertThat(sourceBuffer.size()).isEqualTo(0);

    source.close();
    assertThat(relay.isClosed()).isTrue();
    assertFile(Relay.PREFIX_CLEAN, 13L, metadata.size(), "abcdefghijklm", metadata);
  }

  @Test public void multipleSources() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghijklm");

    Relay relay = Relay.Companion.edit(file, upstream, metadata, 1024);
    BufferedSource source1 = Okio.buffer(relay.newSource());
    BufferedSource source2 = Okio.buffer(relay.newSource());

    assertThat(source1.readUtf8()).isEqualTo("abcdefghijklm");
    assertThat(source2.readUtf8()).isEqualTo("abcdefghijklm");
    source1.close();
    source2.close();
    assertThat(relay.isClosed()).isTrue();

    assertFile(Relay.PREFIX_CLEAN, 13L, metadata.size(), "abcdefghijklm", metadata);
  }

  @Test public void readFromBuffer() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghij");

    Relay relay = Relay.Companion.edit(file, upstream, metadata, 5);
    BufferedSource source1 = Okio.buffer(relay.newSource());
    BufferedSource source2 = Okio.buffer(relay.newSource());

    assertThat(source1.readUtf8(5)).isEqualTo("abcde");
    assertThat(source2.readUtf8(5)).isEqualTo("abcde");
    assertThat(source2.readUtf8(5)).isEqualTo("fghij");
    assertThat(source1.readUtf8(5)).isEqualTo("fghij");
    assertThat(source1.exhausted()).isTrue();
    assertThat(source2.exhausted()).isTrue();
    source1.close();
    source2.close();
    assertThat(relay.isClosed()).isTrue();

    assertFile(Relay.PREFIX_CLEAN, 10L, metadata.size(), "abcdefghij", metadata);
  }

  @Test public void readFromFile() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghijklmnopqrst");

    Relay relay = Relay.Companion.edit(file, upstream, metadata, 5);
    BufferedSource source1 = Okio.buffer(relay.newSource());
    BufferedSource source2 = Okio.buffer(relay.newSource());

    assertThat(source1.readUtf8(10)).isEqualTo("abcdefghij");
    assertThat(source2.readUtf8(10)).isEqualTo("abcdefghij");
    assertThat(source2.readUtf8(10)).isEqualTo("klmnopqrst");
    assertThat(source1.readUtf8(10)).isEqualTo("klmnopqrst");
    assertThat(source1.exhausted()).isTrue();
    assertThat(source2.exhausted()).isTrue();
    source1.close();
    source2.close();
    assertThat(relay.isClosed()).isTrue();

    assertFile(Relay.PREFIX_CLEAN, 20L, metadata.size(), "abcdefghijklmnopqrst", metadata);
  }

  @Test public void readAfterEdit() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghij");

    Relay relay1 = Relay.Companion.edit(file, upstream, metadata, 5);
    BufferedSource source1 = Okio.buffer(relay1.newSource());
    assertThat(source1.readUtf8(10)).isEqualTo("abcdefghij");
    assertThat(source1.exhausted()).isTrue();
    source1.close();
    assertThat(relay1.isClosed()).isTrue();

    // Since relay1 is closed, new sources cannot be created.
    assertThat(relay1.newSource()).isNull();

    Relay relay2 = Relay.Companion.read(file);
    assertThat(relay2.metadata()).isEqualTo(metadata);
    BufferedSource source2 = Okio.buffer(relay2.newSource());
    assertThat(source2.readUtf8(10)).isEqualTo("abcdefghij");
    assertThat(source2.exhausted()).isTrue();
    source2.close();
    assertThat(relay2.isClosed()).isTrue();

    // Since relay2 is closed, new sources cannot be created.
    assertThat(relay2.newSource()).isNull();

    assertFile(Relay.PREFIX_CLEAN, 10L, metadata.size(), "abcdefghij", metadata);
  }

  @Test public void closeBeforeExhaustLeavesDirtyFile() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghij");

    Relay relay1 = Relay.Companion.edit(file, upstream, metadata, 5);
    BufferedSource source1 = Okio.buffer(relay1.newSource());
    assertThat(source1.readUtf8(10)).isEqualTo("abcdefghij");
    source1.close(); // Not exhausted!
    assertThat(relay1.isClosed()).isTrue();

    try {
      Relay.Companion.read(file);
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("unreadable cache file");
    }

    assertFile(Relay.PREFIX_DIRTY, -1L, -1, null, null);
  }

  @Test public void redundantCallsToCloseAreIgnored() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcde");

    Relay relay = Relay.Companion.edit(file, upstream, metadata, 1024);
    Source source1 = relay.newSource();
    Source source2 = relay.newSource();

    source1.close();
    source1.close(); // Unnecessary. Shouldn't decrement the reference count.
    assertThat(relay.isClosed()).isFalse();

    source2.close();
    assertThat(relay.isClosed()).isTrue();
    assertFile(Relay.PREFIX_DIRTY, -1L, -1, null, null);
  }

  @Test public void racingReaders() throws Exception {
    Pipe pipe = new Pipe(1024);
    BufferedSink sink = Okio.buffer(pipe.sink());

    Relay relay = Relay.Companion.edit(file, pipe.source(), metadata, 5);

    Future<ByteString> future1 = executor.submit(sourceReader(relay.newSource()));
    Future<ByteString> future2 = executor.submit(sourceReader(relay.newSource()));

    Thread.sleep(500);
    sink.writeUtf8("abcdefghij");

    Thread.sleep(500);
    sink.writeUtf8("klmnopqrst");
    sink.close();

    assertThat(future1.get()).isEqualTo(ByteString.encodeUtf8("abcdefghijklmnopqrst"));
    assertThat(future2.get()).isEqualTo(ByteString.encodeUtf8("abcdefghijklmnopqrst"));

    assertThat(relay.isClosed()).isTrue();

    assertFile(Relay.PREFIX_CLEAN, 20L, metadata.size(), "abcdefghijklmnopqrst", metadata);
  }

  /** Returns a callable that reads all of source, closes it, and returns the bytes. */
  private Callable<ByteString> sourceReader(final Source source) {
    return () -> {
      Buffer buffer = new Buffer();
      while (source.read(buffer, 16384) != -1) {
      }
      source.close();
      return buffer.readByteString();
    };
  }

  private void assertFile(ByteString prefix, long upstreamSize, int metadataSize, String upstream,
      ByteString metadata) throws IOException {
    BufferedSource source = Okio.buffer(Okio.source(file));
    assertThat(source.readByteString(prefix.size())).isEqualTo(prefix);
    assertThat(source.readLong()).isEqualTo(upstreamSize);
    assertThat(source.readLong()).isEqualTo(metadataSize);
    if (upstream != null) {
      assertThat(source.readUtf8(upstreamSize)).isEqualTo(upstream);
    }
    if (metadata != null) {
      assertThat(source.readByteString(metadataSize)).isEqualTo(metadata);
    }
    source.close();
  }
}
