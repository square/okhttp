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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.RecordingOkAuthenticator;
import com.squareup.okhttp.internal.io.RealConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ConnectionPoolTest {
  private final Runnable emptyRunnable = new Runnable() {
    @Override public void run() {
    }
  };

  private final Address addressA = newAddress("a");
  private final Route routeA1 = newRoute(addressA);
  private final Address addressB = newAddress("b");
  private final Route routeB1 = newRoute(addressB);

  @Test public void connectionsEvictedWhenIdleLongEnough() throws Exception {
    ConnectionPool pool = new ConnectionPool(1, 100L, TimeUnit.NANOSECONDS);
    pool.setCleanupRunnableForTest(emptyRunnable);
    RealConnection c1 = new RealConnection(routeA1);
    c1.idleAtNanos = 50L;
    c1.socket = new Socket();
    assertFalse(c1.socket.isClosed());
    pool.put(c1);

    // Running at time 50, the pool returns that nothing can be evicted until time 150.
    assertEquals(100L, pool.cleanup(50L));
    assertEquals(1, pool.getConnectionCount());
    assertFalse(c1.socket.isClosed());

    // Running at time 60, the pool returns that nothing can be evicted until time 150.
    assertEquals(90L, pool.cleanup(60L));
    assertEquals(1, pool.getConnectionCount());
    assertFalse(c1.socket.isClosed());

    // Running at time 149, the pool returns that nothing can be evicted until time 150.
    assertEquals(1L, pool.cleanup(149L));
    assertEquals(1, pool.getConnectionCount());
    assertFalse(c1.socket.isClosed());

    // Running at time 150, the pool evicts.
    assertEquals(0, pool.cleanup(150L));
    assertEquals(0, pool.getConnectionCount());
    assertTrue(c1.socket.isClosed());

    // Running again, the pool reports that no further runs are necessary.
    assertEquals(-1, pool.cleanup(150L));
    assertEquals(0, pool.getConnectionCount());
    assertTrue(c1.socket.isClosed());
  }

  @Test public void inUseConnectionsNotEvicted() throws Exception {
    ConnectionPool pool = new ConnectionPool(1, 100L, TimeUnit.NANOSECONDS);
    pool.setCleanupRunnableForTest(emptyRunnable);
    RealConnection c1 = new RealConnection(routeA1);
    c1.allocationCount = 1;
    c1.idleAtNanos = 50L;
    c1.socket = new Socket();
    assertFalse(c1.socket.isClosed());
    pool.put(c1);

    // Running at time 50, the pool returns that nothing can be evicted until time 150.
    assertEquals(100L, pool.cleanup(50L));
    assertEquals(1, pool.getConnectionCount());
    assertFalse(c1.socket.isClosed());

    // Running at time 60, the pool returns that nothing can be evicted until time 160.
    assertEquals(100L, pool.cleanup(60L));
    assertEquals(1, pool.getConnectionCount());
    assertFalse(c1.socket.isClosed());

    // Running at time 160, the pool returns that nothing can be evicted until time 260.
    assertEquals(100L, pool.cleanup(160L));
    assertEquals(1, pool.getConnectionCount());
    assertFalse(c1.socket.isClosed());
  }

  @Test public void cleanupPrioritizesEarliestEviction() throws Exception {
    ConnectionPool pool = new ConnectionPool(1, 100L, TimeUnit.NANOSECONDS);
    pool.setCleanupRunnableForTest(emptyRunnable);
    RealConnection c1 = new RealConnection(routeA1);
    c1.idleAtNanos = 75L;
    c1.socket = new Socket();
    pool.put(c1);

    RealConnection c2 = new RealConnection(routeB1);
    c2.idleAtNanos = 50L;
    c2.socket = new Socket();
    pool.put(c2);

    // Running at time 75, the pool returns that nothing can be evicted until time 150.
    assertEquals(75L, pool.cleanup(75L));
    assertEquals(2, pool.getConnectionCount());

    // Running at time 149, the pool returns that nothing can be evicted until time 150.
    assertEquals(1L, pool.cleanup(149L));
    assertEquals(2, pool.getConnectionCount());

    // Running at time 150, the pool evicts c2.
    assertEquals(0L, pool.cleanup(150L));
    assertEquals(1, pool.getConnectionCount());
    assertFalse(c1.socket.isClosed());
    assertTrue(c2.socket.isClosed());

    // Running at time 150, the pool returns that nothing can be evicted until time 175.
    assertEquals(25L, pool.cleanup(150L));
    assertEquals(1, pool.getConnectionCount());

    // Running at time 175, the pool evicts c1.
    assertEquals(0L, pool.cleanup(175L));
    assertEquals(0, pool.getConnectionCount());
    assertTrue(c1.socket.isClosed());
    assertTrue(c2.socket.isClosed());
  }

  private Address newAddress(String name) {
    return new Address(name, 1, Dns.SYSTEM, SocketFactory.getDefault(), null, null, null,
        new RecordingOkAuthenticator("password"), null, Collections.<Protocol>emptyList(),
        Collections.<ConnectionSpec>emptyList(),
        ProxySelector.getDefault());
  }

  private Route newRoute(Address address) {
    return new Route(address, Proxy.NO_PROXY,
        InetSocketAddress.createUnresolved(address.url().host(), address.url().port()));
  }
}
