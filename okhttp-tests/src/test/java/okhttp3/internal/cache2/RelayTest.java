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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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

    Relay relay = Relay.edit(file, upstream, metadata, 1024);
    Source source = relay.newSource();
    Buffer sourceBuffer = new Buffer();

    assertEquals(5, source.read(sourceBuffer, 5));
    assertEquals("abcde", sourceBuffer.readUtf8());

    assertEquals(8, source.read(sourceBuffer, 1024));
    assertEquals("fghijklm", sourceBuffer.readUtf8());

    assertEquals(-1, source.read(sourceBuffer, 1024));
    assertEquals(0, sourceBuffer.size());

    source.close();
    assertTrue(relay.isClosed());
    assertFile(Relay.PREFIX_CLEAN, 13L, metadata.size(), "abcdefghijklm", metadata);
  }

  @Test public void multipleSources() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghijklm");

    Relay relay = Relay.edit(file, upstream, metadata, 1024);
    BufferedSource source1 = Okio.buffer(relay.newSource());
    BufferedSource source2 = Okio.buffer(relay.newSource());

    assertEquals("abcdefghijklm", source1.readUtf8());
    assertEquals("abcdefghijklm", source2.readUtf8());
    source1.close();
    source2.close();
    assertTrue(relay.isClosed());

    assertFile(Relay.PREFIX_CLEAN, 13L, metadata.size(), "abcdefghijklm", metadata);
  }

  @Test public void readFromBuffer() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghij");

    Relay relay = Relay.edit(file, upstream, metadata, 5);
    BufferedSource source1 = Okio.buffer(relay.newSource());
    BufferedSource source2 = Okio.buffer(relay.newSource());

    assertEquals("abcde", source1.readUtf8(5));
    assertEquals("abcde", source2.readUtf8(5));
    assertEquals("fghij", source2.readUtf8(5));
    assertEquals("fghij", source1.readUtf8(5));
    assertTrue(source1.exhausted());
    assertTrue(source2.exhausted());
    source1.close();
    source2.close();
    assertTrue(relay.isClosed());

    assertFile(Relay.PREFIX_CLEAN, 10L, metadata.size(), "abcdefghij", metadata);
  }

  @Test public void readFromFile() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghijklmnopqrst");

    Relay relay = Relay.edit(file, upstream, metadata, 5);
    BufferedSource source1 = Okio.buffer(relay.newSource());
    BufferedSource source2 = Okio.buffer(relay.newSource());

    assertEquals("abcdefghij", source1.readUtf8(10));
    assertEquals("abcdefghij", source2.readUtf8(10));
    assertEquals("klmnopqrst", source2.readUtf8(10));
    assertEquals("klmnopqrst", source1.readUtf8(10));
    assertTrue(source1.exhausted());
    assertTrue(source2.exhausted());
    source1.close();
    source2.close();
    assertTrue(relay.isClosed());

    assertFile(Relay.PREFIX_CLEAN, 20L, metadata.size(), "abcdefghijklmnopqrst", metadata);
  }

  @Test public void readAfterEdit() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghij");

    Relay relay1 = Relay.edit(file, upstream, metadata, 5);
    BufferedSource source1 = Okio.buffer(relay1.newSource());
    assertEquals("abcdefghij", source1.readUtf8(10));
    assertTrue(source1.exhausted());
    source1.close();
    assertTrue(relay1.isClosed());

    // Since relay1 is closed, new sources cannot be created.
    assertNull(relay1.newSource());

    Relay relay2 = Relay.read(file);
    assertEquals(metadata, relay2.metadata());
    BufferedSource source2 = Okio.buffer(relay2.newSource());
    assertEquals("abcdefghij", source2.readUtf8(10));
    assertTrue(source2.exhausted());
    source2.close();
    assertTrue(relay2.isClosed());

    // Since relay2 is closed, new sources cannot be created.
    assertNull(relay2.newSource());

    assertFile(Relay.PREFIX_CLEAN, 10L, metadata.size(), "abcdefghij", metadata);
  }

  @Test public void closeBeforeExhaustLeavesDirtyFile() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcdefghij");

    Relay relay1 = Relay.edit(file, upstream, metadata, 5);
    BufferedSource source1 = Okio.buffer(relay1.newSource());
    assertEquals("abcdefghij", source1.readUtf8(10));
    source1.close(); // Not exhausted!
    assertTrue(relay1.isClosed());

    try {
      Relay.read(file);
      fail();
    } catch (IOException expected) {
      assertEquals("unreadable cache file", expected.getMessage());
    }

    assertFile(Relay.PREFIX_DIRTY, -1L, -1, null, null);
  }

  @Test public void redundantCallsToCloseAreIgnored() throws Exception {
    Buffer upstream = new Buffer();
    upstream.writeUtf8("abcde");

    Relay relay = Relay.edit(file, upstream, metadata, 1024);
    Source source1 = relay.newSource();
    Source source2 = relay.newSource();

    source1.close();
    source1.close(); // Unnecessary. Shouldn't decrement the reference count.
    assertFalse(relay.isClosed());

    source2.close();
    assertTrue(relay.isClosed());
    assertFile(Relay.PREFIX_DIRTY, -1L, -1, null, null);
  }

  @Test public void racingReaders() throws Exception {
    Pipe pipe = new Pipe(1024);
    BufferedSink sink = Okio.buffer(pipe.sink());

    Relay relay = Relay.edit(file, pipe.source(), metadata, 5);

    Future<ByteString> future1 = executor.submit(sourceReader(relay.newSource()));
    Future<ByteString> future2 = executor.submit(sourceReader(relay.newSource()));

    Thread.sleep(500);
    sink.writeUtf8("abcdefghij");

    Thread.sleep(500);
    sink.writeUtf8("klmnopqrst");
    sink.close();

    assertEquals(ByteString.encodeUtf8("abcdefghijklmnopqrst"), future1.get());
    assertEquals(ByteString.encodeUtf8("abcdefghijklmnopqrst"), future2.get());

    assertTrue(relay.isClosed());

    assertFile(Relay.PREFIX_CLEAN, 20L, metadata.size(), "abcdefghijklmnopqrst", metadata);
  }

  /** Returns a callable that reads all of source, closes it, and returns the bytes. */
  private Callable<ByteString> sourceReader(final Source source) {
    return new Callable<ByteString>() {
      @Override public ByteString call() throws Exception {
        Buffer buffer = new Buffer();
        while (source.read(buffer, 16384) != -1) {
        }
        source.close();
        return buffer.readByteString();
      }
    };
  }

  private void assertFile(ByteString prefix, long upstreamSize, int metadataSize, String upstream,
      ByteString metadata) throws IOException {
    BufferedSource source = Okio.buffer(Okio.source(file));
    assertEquals(prefix, source.readByteString(prefix.size()));
    assertEquals(upstreamSize, source.readLong());
    assertEquals(metadataSize, source.readLong());
    if (upstream != null) {
      assertEquals(upstream, source.readUtf8(upstreamSize));
    }
    if (metadata != null) {
      assertEquals(metadata, source.readByteString(metadataSize));
    }
    source.close();
  }
}
