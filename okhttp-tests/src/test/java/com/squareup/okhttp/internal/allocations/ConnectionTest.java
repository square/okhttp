/*
 * Copyright (C) 2015 Square, Inc.
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
package com.squareup.okhttp.internal.allocations;

import com.squareup.okhttp.ConnectionPool;
import org.junit.Test;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public final class ConnectionTest {
  ConnectionPool connectionPool = new ConnectionPool(1, 1000L);

  @Test public void reserveCreateCompleteRelease() throws Exception {
    Connection connection = new Connection(connectionPool);
    connection.setAllocationLimit(1);

    Connection.StreamAllocation a = connection.reserve("a");
    Connection.Stream a1 = a.newStream("a1");
    assertNotNull(a1);
    a.streamComplete(a1);
    assertEquals(1, connection.size()); // Still allocated.

    connection.release(a);
    assertEquals(0, connection.size());
  }

  @Test public void reserveCreateReleaseComplete() throws Exception {
    Connection connection = new Connection(connectionPool);
    connection.setAllocationLimit(1);

    Connection.StreamAllocation a = connection.reserve("a");
    Connection.Stream a1 = a.newStream("a1");
    assertNotNull(a1);
    connection.release(a);
    assertEquals(1, connection.size()); // Still allocated.

    a.streamComplete(a1);
    assertEquals(0, connection.size());
  }

  @Test public void reuseAllocation() throws Exception {
    Connection connection = new Connection(connectionPool);
    connection.setAllocationLimit(1);

    Connection.StreamAllocation a = connection.reserve("a");
    Connection.Stream a1 = a.newStream("a1");
    assertNotNull(a1);
    a.streamComplete(a1);
    assertEquals(1, connection.size());

    Connection.Stream a2 = a.newStream("a2");
    assertNotNull(a2);
    a.streamComplete(a2);
    assertEquals(1, connection.size());

    connection.release(a);
    assertEquals(0, connection.size());
  }

  @Test public void cannotReuseAllocationAfterRelease() throws Exception {
    Connection connection = new Connection(connectionPool);
    connection.setAllocationLimit(1);

    Connection.StreamAllocation a = connection.reserve("a");
    Connection.Stream a1 = a.newStream("a1");
    a.streamComplete(a1);
    connection.release(a);

    try {
      a.newStream("a2");
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void createReturnsNullAfterNoNewStreams() throws Exception {
    Connection connection = new Connection(connectionPool);
    connection.setAllocationLimit(1);

    Connection.StreamAllocation a = connection.reserve("a");
    Connection.Stream a1 = a.newStream("a1");
    assertNotNull(a1);
    a.streamComplete(a1);
    assertEquals(1, connection.size());

    connection.noNewStreams();
    assertNull(a.newStream("a2"));
    assertEquals(1, connection.size());

    connection.release(a);
    assertEquals(0, connection.size());
  }

  @Test public void reserveReturnsNullAfterNoNewStreams() throws Exception {
    Connection connection = new Connection(connectionPool);
    connection.setAllocationLimit(1);

    Connection.StreamAllocation a = connection.reserve("a");
    Connection.Stream a1 = a.newStream("a1");

    connection.noNewStreams();
    assertNull(connection.reserve("b"));

    // Even after streams are released, the limit still holds.
    a.streamComplete(a1);
    assertNull(connection.reserve("c"));
  }

  @Test public void closeScheduledAfterNoNewStreams() throws Exception {
    Connection connection = new Connection(connectionPool);
    connection.setAllocationLimit(1);

    Connection.StreamAllocation a = connection.reserve("c");
    Connection.Stream a1 = a.newStream("a1");
    connection.noNewStreams();
    assertEquals(Long.MAX_VALUE, connection.idleAt);

    a.streamComplete(a1);
    assertEquals(Long.MAX_VALUE, connection.idleAt);

    connection.release(a);
    assertNotEquals(Long.MAX_VALUE, connection.idleAt);
  }

  @Test public void multipleAllocations() throws Exception {
    Connection connection = new Connection(connectionPool);
    connection.setAllocationLimit(2);

    Connection.StreamAllocation a = connection.reserve("a");
    Connection.StreamAllocation b = connection.reserve("b");
    Connection.Stream a1 = a.newStream("a1");
    Connection.Stream b1 = b.newStream("b1");
    assertEquals(2, connection.size());

    connection.release(a);
    assertEquals(2, connection.size());
    a.streamComplete(a1);
    assertEquals(1, connection.size());

    b.streamComplete(b1);
    assertEquals(1, connection.size());
    connection.release(b);
    assertEquals(0, connection.size());
  }

  @Test public void lowerAndRaiseAllocationLimit() throws Exception {
    Connection connection = new Connection(connectionPool);
    connection.setAllocationLimit(2);

    assertNotNull(connection.reserve("a"));
    assertNotNull(connection.reserve("a"));
    assertNull(connection.reserve("c"));

    connection.setAllocationLimit(0);
    assertEquals(2, connection.size());
    assertNull(connection.reserve("d"));

    connection.setAllocationLimit(3);
    assertNotNull(connection.reserve("e"));
  }

  @Test public void leakedAllocation() throws Exception {
    Connection connection = new Connection(connectionPool);
    connection.setAllocationLimit(1);

    reserveAndLeakAllocation(connection);
    awaitGarbageCollection();
    connection.pruneLeakedAllocations();
    assertEquals(0, connection.size());

    assertNull(connection.reserve("b")); // Can't allocate once a leak has been detected.
  }

  /** Use a helper method so there's no hidden reference remaining on the stack. */
  private void reserveAndLeakAllocation(Connection connection) {
    connection.reserve("a");
  }

  /**
   * See FinalizationTester for discussion on how to best trigger GC in tests.
   * https://android.googlesource.com/platform/libcore/+/master/support/src/test/java/libcore/
   * java/lang/ref/FinalizationTester.java
   */
  private void awaitGarbageCollection() throws InterruptedException {
    Runtime.getRuntime().gc();
    Thread.sleep(100);
    System.runFinalization();
  }
}
