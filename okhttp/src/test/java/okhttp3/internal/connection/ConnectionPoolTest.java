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
package okhttp3.internal.connection;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Route;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.concurrent.TaskFaker;
import okhttp3.internal.concurrent.TaskRunner;
import org.junit.Test;

import static okhttp3.TestUtil.awaitGarbageCollection;
import static org.assertj.core.api.Assertions.assertThat;

public final class ConnectionPoolTest {
  /** The fake task runner prevents the cleanup runnable from being started. */
  private final TaskRunner taskRunner = new TaskFaker().getTaskRunner();
  private final Address addressA = newAddress("a");
  private final Route routeA1 = newRoute(addressA);
  private final Address addressB = newAddress("b");
  private final Route routeB1 = newRoute(addressB);
  private final Address addressC = newAddress("c");
  private final Route routeC1 = newRoute(addressC);

  @Test public void connectionsEvictedWhenIdleLongEnough() throws Exception {
    RealConnectionPool pool = new RealConnectionPool(
        taskRunner, Integer.MAX_VALUE, 100L, TimeUnit.NANOSECONDS);

    RealConnection c1 = newConnection(pool, routeA1, 50L);

    // Running at time 50, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(50L)).isEqualTo(100L);
    assertThat(pool.connectionCount()).isEqualTo(1);
    assertThat(c1.socket().isClosed()).isFalse();

    // Running at time 60, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(60L)).isEqualTo(90L);
    assertThat(pool.connectionCount()).isEqualTo(1);
    assertThat(c1.socket().isClosed()).isFalse();

    // Running at time 149, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(149L)).isEqualTo(1L);
    assertThat(pool.connectionCount()).isEqualTo(1);
    assertThat(c1.socket().isClosed()).isFalse();

    // Running at time 150, the pool evicts.
    assertThat(pool.cleanup(150L)).isEqualTo(0);
    assertThat(pool.connectionCount()).isEqualTo(0);
    assertThat(c1.socket().isClosed()).isTrue();

    // Running again, the pool reports that no further runs are necessary.
    assertThat(pool.cleanup(150L)).isEqualTo(-1);
    assertThat(pool.connectionCount()).isEqualTo(0);
    assertThat(c1.socket().isClosed()).isTrue();
  }

  @Test public void inUseConnectionsNotEvicted() throws Exception {
    RealConnectionPool pool = new RealConnectionPool(
        taskRunner, Integer.MAX_VALUE, 100L, TimeUnit.NANOSECONDS);
    ConnectionPool poolApi = new ConnectionPool(pool);

    RealConnection c1 = newConnection(pool, routeA1, 50L);
    synchronized (pool) {
      OkHttpClient client = new OkHttpClient.Builder()
          .connectionPool(poolApi)
          .build();
      Call call = client.newCall(newRequest(addressA));
      Transmitter transmitter = new Transmitter(client, call);
      transmitter.prepareToConnect(call.request());
      transmitter.acquireConnectionNoEvents(c1);
    }

    // Running at time 50, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(50L)).isEqualTo(100L);
    assertThat(pool.connectionCount()).isEqualTo(1);
    assertThat(c1.socket().isClosed()).isFalse();

    // Running at time 60, the pool returns that nothing can be evicted until time 160.
    assertThat(pool.cleanup(60L)).isEqualTo(100L);
    assertThat(pool.connectionCount()).isEqualTo(1);
    assertThat(c1.socket().isClosed()).isFalse();

    // Running at time 160, the pool returns that nothing can be evicted until time 260.
    assertThat(pool.cleanup(160L)).isEqualTo(100L);
    assertThat(pool.connectionCount()).isEqualTo(1);
    assertThat(c1.socket().isClosed()).isFalse();
  }

  @Test public void cleanupPrioritizesEarliestEviction() throws Exception {
    RealConnectionPool pool = new RealConnectionPool(
        taskRunner, Integer.MAX_VALUE, 100L, TimeUnit.NANOSECONDS);

    RealConnection c1 = newConnection(pool, routeA1, 75L);
    RealConnection c2 = newConnection(pool, routeB1, 50L);

    // Running at time 75, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(75L)).isEqualTo(75L);
    assertThat(pool.connectionCount()).isEqualTo(2);

    // Running at time 149, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(149L)).isEqualTo(1L);
    assertThat(pool.connectionCount()).isEqualTo(2);

    // Running at time 150, the pool evicts c2.
    assertThat(pool.cleanup(150L)).isEqualTo(0L);
    assertThat(pool.connectionCount()).isEqualTo(1);
    assertThat(c1.socket().isClosed()).isFalse();
    assertThat(c2.socket().isClosed()).isTrue();

    // Running at time 150, the pool returns that nothing can be evicted until time 175.
    assertThat(pool.cleanup(150L)).isEqualTo(25L);
    assertThat(pool.connectionCount()).isEqualTo(1);

    // Running at time 175, the pool evicts c1.
    assertThat(pool.cleanup(175L)).isEqualTo(0L);
    assertThat(pool.connectionCount()).isEqualTo(0);
    assertThat(c1.socket().isClosed()).isTrue();
    assertThat(c2.socket().isClosed()).isTrue();
  }

  @Test public void oldestConnectionsEvictedIfIdleLimitExceeded() throws Exception {
    RealConnectionPool pool = new RealConnectionPool(
        taskRunner, 2, 100L, TimeUnit.NANOSECONDS);

    RealConnection c1 = newConnection(pool, routeA1, 50L);
    RealConnection c2 = newConnection(pool, routeB1, 75L);

    // With 2 connections, there's no need to evict until the connections time out.
    assertThat(pool.cleanup(100L)).isEqualTo(50L);
    assertThat(pool.connectionCount()).isEqualTo(2);
    assertThat(c1.socket().isClosed()).isFalse();
    assertThat(c2.socket().isClosed()).isFalse();

    // Add a third connection
    RealConnection c3 = newConnection(pool, routeC1, 75L);

    // The third connection bounces the first.
    assertThat(pool.cleanup(100L)).isEqualTo(0L);
    assertThat(pool.connectionCount()).isEqualTo(2);
    assertThat(c1.socket().isClosed()).isTrue();
    assertThat(c2.socket().isClosed()).isFalse();
    assertThat(c3.socket().isClosed()).isFalse();
  }

  @Test public void leakedAllocation() throws Exception {
    RealConnectionPool pool = new RealConnectionPool(
        taskRunner, Integer.MAX_VALUE, 100L, TimeUnit.NANOSECONDS);
    ConnectionPool poolApi = new ConnectionPool(pool);

    RealConnection c1 = newConnection(pool, routeA1, 0L);
    allocateAndLeakAllocation(poolApi, c1);

    awaitGarbageCollection();
    assertThat(pool.cleanup(100L)).isEqualTo(0L);
    assertThat(c1.getTransmitters()).isEmpty();

    // Can't allocate once a leak has been detected.
    assertThat(c1.getNoNewExchanges()).isTrue();
  }

  @Test public void interruptStopsThread() throws Exception {
    TaskRunner realTaskRunner = TaskRunner.INSTANCE;
    RealConnectionPool pool = new RealConnectionPool(
        realTaskRunner, 2, 100L, TimeUnit.NANOSECONDS);
    newConnection(pool, routeA1, Long.MAX_VALUE);

    assertThat(realTaskRunner.activeQueues()).isNotEmpty();

    Thread.sleep(100);

    Thread[] threads = new Thread[Thread.activeCount() * 2];
    Thread.enumerate(threads);
    for (Thread t: threads) {
      if (t != null && t.getName().equals("OkHttp TaskRunner")) {
        t.interrupt();
      }
    }

    Thread.sleep(100);

    assertThat(realTaskRunner.activeQueues()).isEmpty();
  }

  /** Use a helper method so there's no hidden reference remaining on the stack. */
  private void allocateAndLeakAllocation(ConnectionPool pool, RealConnection connection) {
    synchronized (RealConnectionPool.Companion.get(pool)) {
      OkHttpClient client = new OkHttpClient.Builder()
          .connectionPool(pool)
          .build();
      Call call = client.newCall(newRequest(connection.route().address()));
      Transmitter transmitter = new Transmitter(client, call);
      transmitter.prepareToConnect(call.request());
      transmitter.acquireConnectionNoEvents(connection);
    }
  }

  private RealConnection newConnection(RealConnectionPool pool, Route route, long idleAtNanos) {
    RealConnection result = RealConnection.Companion.newTestConnection(
        pool, route, new Socket(), idleAtNanos);
    synchronized (pool) {
      pool.put(result);
    }
    return result;
  }

  private Address newAddress(String name) {
    return new Address(name, 1, Dns.SYSTEM, SocketFactory.getDefault(), null, null, null,
        new RecordingOkAuthenticator("password", null), null, Collections.emptyList(),
        Collections.emptyList(), ProxySelector.getDefault());
  }

  private Route newRoute(Address address) {
    return new Route(address, Proxy.NO_PROXY,
        InetSocketAddress.createUnresolved(address.url().host(), address.url().port()));
  }

  private Request newRequest(Address address) {
    return new Request.Builder()
        .url(address.url())
        .build();
  }
}
