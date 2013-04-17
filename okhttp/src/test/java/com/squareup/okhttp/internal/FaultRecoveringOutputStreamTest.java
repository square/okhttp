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
package com.squareup.okhttp.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class FaultRecoveringOutputStreamTest {
  @Test public void noRecoveryWithoutReplacement() throws Exception {
    FaultingOutputStream faulting = new FaultingOutputStream();
    TestFaultRecoveringOutputStream recovering = new TestFaultRecoveringOutputStream(10, faulting);

    recovering.write('a');
    faulting.nextFault = "system on fire";
    try {
      recovering.write('b');
      fail();
    } catch (IOException e) {
      assertEquals(Arrays.asList("system on fire"), recovering.exceptionMessages);
      assertEquals("ab", faulting.receivedUtf8);
      assertFalse(faulting.closed);
    }
  }

  @Test public void successfulRecoveryOnWriteFault() throws Exception {
    FaultingOutputStream faulting1 = new FaultingOutputStream();
    FaultingOutputStream faulting2 = new FaultingOutputStream();
    TestFaultRecoveringOutputStream recovering = new TestFaultRecoveringOutputStream(10, faulting1);
    recovering.replacements.addLast(faulting2);

    recovering.write('a');
    assertEquals("a", faulting1.receivedUtf8);
    assertEquals("", faulting2.receivedUtf8);
    faulting1.nextFault = "system under water";
    recovering.write('b');
    assertEquals(Arrays.asList("system under water"), recovering.exceptionMessages);
    assertEquals("ab", faulting1.receivedUtf8);
    assertEquals("ab", faulting2.receivedUtf8);
    assertTrue(faulting1.closed);
    assertFalse(faulting2.closed);

    // Confirm that new data goes to the new stream.
    recovering.write('c');
    assertEquals("ab", faulting1.receivedUtf8);
    assertEquals("abc", faulting2.receivedUtf8);
  }

  @Test public void successfulRecoveryOnFlushFault() throws Exception {
    FaultingOutputStream faulting1 = new FaultingOutputStream();
    FaultingOutputStream faulting2 = new FaultingOutputStream();
    TestFaultRecoveringOutputStream recovering = new TestFaultRecoveringOutputStream(10, faulting1);
    recovering.replacements.addLast(faulting2);

    recovering.write('a');
    faulting1.nextFault = "bad weather";
    recovering.flush();
    assertEquals(Arrays.asList("bad weather"), recovering.exceptionMessages);
    assertEquals("a", faulting1.receivedUtf8);
    assertEquals("a", faulting2.receivedUtf8);
    assertTrue(faulting1.closed);
    assertFalse(faulting2.closed);
    assertEquals("a", faulting2.flushedUtf8);

    // Confirm that new data goes to the new stream.
    recovering.write('b');
    assertEquals("a", faulting1.receivedUtf8);
    assertEquals("ab", faulting2.receivedUtf8);
    assertEquals("a", faulting2.flushedUtf8);
  }

  @Test public void successfulRecoveryOnCloseFault() throws Exception {
    FaultingOutputStream faulting1 = new FaultingOutputStream();
    FaultingOutputStream faulting2 = new FaultingOutputStream();
    TestFaultRecoveringOutputStream recovering = new TestFaultRecoveringOutputStream(10, faulting1);
    recovering.replacements.addLast(faulting2);

    recovering.write('a');
    faulting1.nextFault = "termites";
    recovering.close();
    assertEquals(Arrays.asList("termites"), recovering.exceptionMessages);
    assertEquals("a", faulting1.receivedUtf8);
    assertEquals("a", faulting2.receivedUtf8);
    assertTrue(faulting1.closed);
    assertTrue(faulting2.closed);
  }

  @Test public void replacementStreamFaultsImmediately() throws Exception {
    FaultingOutputStream faulting1 = new FaultingOutputStream();
    FaultingOutputStream faulting2 = new FaultingOutputStream();
    FaultingOutputStream faulting3 = new FaultingOutputStream();
    TestFaultRecoveringOutputStream recovering = new TestFaultRecoveringOutputStream(10, faulting1);
    recovering.replacements.addLast(faulting2);
    recovering.replacements.addLast(faulting3);

    recovering.write('a');
    assertEquals("a", faulting1.receivedUtf8);
    assertEquals("", faulting2.receivedUtf8);
    assertEquals("", faulting3.receivedUtf8);
    faulting1.nextFault = "offline";
    faulting2.nextFault = "slow";
    recovering.write('b');
    assertEquals(Arrays.asList("offline", "slow"), recovering.exceptionMessages);
    assertEquals("ab", faulting1.receivedUtf8);
    assertEquals("a", faulting2.receivedUtf8);
    assertEquals("ab", faulting3.receivedUtf8);
    assertTrue(faulting1.closed);
    assertTrue(faulting2.closed);
    assertFalse(faulting3.closed);

    // Confirm that new data goes to the new stream.
    recovering.write('c');
    assertEquals("ab", faulting1.receivedUtf8);
    assertEquals("a", faulting2.receivedUtf8);
    assertEquals("abc", faulting3.receivedUtf8);
  }

  @Test public void recoverWithFullBuffer() throws Exception {
    FaultingOutputStream faulting1 = new FaultingOutputStream();
    FaultingOutputStream faulting2 = new FaultingOutputStream();
    TestFaultRecoveringOutputStream recovering = new TestFaultRecoveringOutputStream(10, faulting1);
    recovering.replacements.addLast(faulting2);

    recovering.write("abcdefghij".getBytes(UTF_8)); // 10 bytes.
    faulting1.nextFault = "unlucky";
    recovering.write('k');
    assertEquals("abcdefghijk", faulting1.receivedUtf8);
    assertEquals("abcdefghijk", faulting2.receivedUtf8);
    assertEquals(Arrays.asList("unlucky"), recovering.exceptionMessages);
    assertTrue(faulting1.closed);
    assertFalse(faulting2.closed);

    // Confirm that new data goes to the new stream.
    recovering.write('l');
    assertEquals("abcdefghijk", faulting1.receivedUtf8);
    assertEquals("abcdefghijkl", faulting2.receivedUtf8);
  }

  @Test public void noRecoveryWithOverfullBuffer() throws Exception {
    FaultingOutputStream faulting1 = new FaultingOutputStream();
    FaultingOutputStream faulting2 = new FaultingOutputStream();
    TestFaultRecoveringOutputStream recovering = new TestFaultRecoveringOutputStream(10, faulting1);
    recovering.replacements.addLast(faulting2);

    recovering.write("abcdefghijk".getBytes(UTF_8)); // 11 bytes.
    faulting1.nextFault = "out to lunch";
    try {
      recovering.write('l');
      fail();
    } catch (IOException expected) {
      assertEquals("out to lunch", expected.getMessage());
    }

    assertEquals(Arrays.<String>asList(), recovering.exceptionMessages);
    assertEquals("abcdefghijkl", faulting1.receivedUtf8);
    assertEquals("", faulting2.receivedUtf8);
    assertFalse(faulting1.closed);
    assertFalse(faulting2.closed);
  }

  static class FaultingOutputStream extends OutputStream {
    String receivedUtf8 = "";
    String flushedUtf8 = null;
    String nextFault;
    boolean closed;

    @Override public final void write(int data) throws IOException {
      write(new byte[] { (byte) data });
    }

    @Override public void write(byte[] buffer, int offset, int count) throws IOException {
      receivedUtf8 += new String(buffer, offset, count, UTF_8);
      if (nextFault != null) throw new IOException(nextFault);
    }

    @Override public void flush() throws IOException {
      flushedUtf8 = receivedUtf8;
      if (nextFault != null) throw new IOException(nextFault);
    }

    @Override public void close() throws IOException {
      closed = true;
      if (nextFault != null) throw new IOException(nextFault);
    }
  }

  static class TestFaultRecoveringOutputStream extends FaultRecoveringOutputStream {
    final List<String> exceptionMessages = new ArrayList<String>();
    final Deque<OutputStream> replacements = new ArrayDeque<OutputStream>();

    TestFaultRecoveringOutputStream(int maxReplayBufferLength, OutputStream first) {
      super(maxReplayBufferLength, first);
    }

    @Override protected OutputStream replacementStream(IOException e) {
      exceptionMessages.add(e.getMessage());
      return replacements.poll();
    }
  }
}
