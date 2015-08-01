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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.AuthenticatorAdapter;
import com.squareup.okhttp.internal.http.RecordingProxySelector;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.testing.RecordingHostnameVerifier;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ConnectionPoolTest {
  static {
    Internal.initializeInstanceForTests();
  }

  private static final List<ConnectionSpec> CONNECTION_SPECS = Util.immutableList(
      ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT);

  private static final int KEEP_ALIVE_DURATION_MS = 5000;

  private SSLContext sslContext = SslContextBuilder.localhost();
  private MockWebServer spdyServer;
  private InetSocketAddress spdySocketAddress;
  private Address spdyAddress;

  private MockWebServer httpServer;
  private Address httpAddress;
  private InetSocketAddress httpSocketAddress;

  private ConnectionPool pool;
  private FakeExecutor cleanupExecutor;
  private Connection httpA;
  private Connection httpB;
  private Connection httpC;
  private Connection httpD;
  private Connection httpE;
  private Connection spdyA;

  private Object owner;

  @Before public void setUp() throws Exception {
    setUp(2);
  }

  private void setUp(int poolSize) throws Exception {
    SocketFactory socketFactory = SocketFactory.getDefault();
    RecordingProxySelector proxySelector = new RecordingProxySelector();

    spdyServer = new MockWebServer();
    httpServer = new MockWebServer();
    spdyServer.useHttps(sslContext.getSocketFactory(), false);

    httpServer.start();
    httpAddress = new Address(httpServer.getHostName(), httpServer.getPort(), socketFactory, null,
        null, null, AuthenticatorAdapter.INSTANCE, null,
        Util.immutableList(Protocol.SPDY_3, Protocol.HTTP_1_1), CONNECTION_SPECS, proxySelector);
    httpSocketAddress = new InetSocketAddress(InetAddress.getByName(httpServer.getHostName()),
        httpServer.getPort());

    spdyServer.start();
    spdyAddress = new Address(spdyServer.getHostName(), spdyServer.getPort(), socketFactory,
        sslContext.getSocketFactory(), new RecordingHostnameVerifier(), CertificatePinner.DEFAULT,
        AuthenticatorAdapter.INSTANCE, null, Util.immutableList(Protocol.SPDY_3, Protocol.HTTP_1_1),
        CONNECTION_SPECS, proxySelector);
    spdySocketAddress = new InetSocketAddress(InetAddress.getByName(spdyServer.getHostName()),
        spdyServer.getPort());

    Route httpRoute = new Route(httpAddress, Proxy.NO_PROXY, httpSocketAddress);
    Route spdyRoute = new Route(spdyAddress, Proxy.NO_PROXY, spdySocketAddress);
    pool = new ConnectionPool(poolSize, KEEP_ALIVE_DURATION_MS);
    // Disable the automatic execution of the cleanup.
    cleanupExecutor = new FakeExecutor();
    pool.replaceCleanupExecutorForTests(cleanupExecutor);
    httpA = new Connection(pool, httpRoute);
    httpA.connect(200, 200, 200, null, CONNECTION_SPECS, false /* connectionRetryEnabled */);
    httpB = new Connection(pool, httpRoute);
    httpB.connect(200, 200, 200, null, CONNECTION_SPECS, false /* connectionRetryEnabled */);
    httpC = new Connection(pool, httpRoute);
    httpC.connect(200, 200, 200, null, CONNECTION_SPECS, false /* connectionRetryEnabled */);
    httpD = new Connection(pool, httpRoute);
    httpD.connect(200, 200, 200, null, CONNECTION_SPECS, false /* connectionRetryEnabled */);
    httpE = new Connection(pool, httpRoute);
    httpE.connect(200, 200, 200, null, CONNECTION_SPECS, false /* connectionRetryEnabled */);
    spdyA = new Connection(pool, spdyRoute);
    spdyA.connect(20000, 20000, 2000, null, CONNECTION_SPECS, false /* connectionRetryEnabled */);

    owner = new Object();
    httpA.setOwner(owner);
    httpB.setOwner(owner);
    httpC.setOwner(owner);
    httpD.setOwner(owner);
    httpE.setOwner(owner);
  }

  @After public void tearDown() throws Exception {
    httpServer.shutdown();
    spdyServer.shutdown();

    Util.closeQuietly(httpA.getSocket());
    Util.closeQuietly(httpB.getSocket());
    Util.closeQuietly(httpC.getSocket());
    Util.closeQuietly(httpD.getSocket());
    Util.closeQuietly(httpE.getSocket());
    Util.closeQuietly(spdyA.getSocket());
  }

  private void resetWithPoolSize(int poolSize) throws Exception {
    tearDown();
    setUp(poolSize);
  }

  @Test public void poolSingleHttpConnection() throws Exception {
    resetWithPoolSize(1);
    Connection connection = pool.get(httpAddress);
    assertNull(connection);

    connection = new Connection(pool, new Route(httpAddress, Proxy.NO_PROXY, httpSocketAddress));
    connection.connect(200, 200, 200, null, CONNECTION_SPECS, false /* connectionRetryEnabled */);
    connection.setOwner(owner);
    assertEquals(0, pool.getConnectionCount());

    pool.recycle(connection);
    assertNull(connection.getOwner());
    assertEquals(1, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(0, pool.getMultiplexedConnectionCount());

    Connection recycledConnection = pool.get(httpAddress);
    assertNull(connection.getOwner());
    assertEquals(connection, recycledConnection);
    assertTrue(recycledConnection.isAlive());

    recycledConnection = pool.get(httpAddress);
    assertNull(recycledConnection);
  }

  @Test public void getDoesNotScheduleCleanup() {
    Connection connection = pool.get(httpAddress);
    assertNull(connection);
    cleanupExecutor.assertExecutionScheduled(false);
  }

  @Test public void recycleSchedulesCleanup() {
    cleanupExecutor.assertExecutionScheduled(false);
    pool.recycle(httpA);
    cleanupExecutor.assertExecutionScheduled(true);
  }

  @Test public void shareSchedulesCleanup() {
    cleanupExecutor.assertExecutionScheduled(false);
    pool.share(spdyA);
    cleanupExecutor.assertExecutionScheduled(true);
  }

  @Test public void poolPrefersMostRecentlyRecycled() throws Exception {
    pool.recycle(httpA);
    pool.recycle(httpB);
    pool.recycle(httpC);
    assertPooled(pool, httpC, httpB, httpA);

    pool.performCleanup();
    assertPooled(pool, httpC, httpB);
  }

  @Test public void getSpdyConnection() throws Exception {
    pool.share(spdyA);
    assertSame(spdyA, pool.get(spdyAddress));
    assertPooled(pool, spdyA);
  }

  @Test public void getHttpConnection() throws Exception {
    pool.recycle(httpA);
    assertSame(httpA, pool.get(httpAddress));
    assertPooled(pool);
  }

  @Test public void expiredConnectionNotReturned() throws Exception {
    pool.recycle(httpA);

    // Allow enough time to pass so that the connection is now expired.
    Thread.sleep(KEEP_ALIVE_DURATION_MS * 2);

    // The connection is held, but will not be returned.
    assertNull(pool.get(httpAddress));
    assertPooled(pool, httpA);

    // The connection must be cleaned up.
    pool.performCleanup();
    assertPooled(pool);
  }

  @Test public void maxIdleConnectionLimitIsEnforced() throws Exception {
    pool.recycle(httpA);
    pool.recycle(httpB);
    pool.recycle(httpC);
    pool.recycle(httpD);
    assertPooled(pool, httpD, httpC, httpB, httpA);

    pool.performCleanup();
    assertPooled(pool, httpD, httpC);
  }

  @Test public void expiredConnectionsAreEvicted() throws Exception {
    pool.recycle(httpA);
    pool.recycle(httpB);

    // Allow enough time to pass so that the connections are now expired.
    Thread.sleep(2 * KEEP_ALIVE_DURATION_MS);
    assertPooled(pool, httpB, httpA);

    // The connections must be cleaned up.
    pool.performCleanup();
    assertPooled(pool);
  }

  @Test public void nonAliveConnectionNotReturned() throws Exception {
    pool.recycle(httpA);

    // Close the connection. It is an ex-connection. It has ceased to be.
    httpA.getSocket().close();
    assertPooled(pool, httpA);
    assertNull(pool.get(httpAddress));

    // The connection must be cleaned up.
    pool.performCleanup();
    assertPooled(pool);
  }

  @Test public void differentAddressConnectionNotReturned() throws Exception {
    pool.recycle(httpA);
    assertNull(pool.get(spdyAddress));
    assertPooled(pool, httpA);
  }

  @Test public void gettingSpdyConnectionPromotesItToFrontOfQueue() throws Exception {
    pool.share(spdyA);
    pool.recycle(httpA);
    assertPooled(pool, httpA, spdyA);
    assertSame(spdyA, pool.get(spdyAddress));
    assertPooled(pool, spdyA, httpA);
  }

  @Test public void gettingConnectionReturnsOldestFirst() throws Exception {
    pool.recycle(httpA);
    pool.recycle(httpB);
    assertSame(httpA, pool.get(httpAddress));
  }

  @Test public void recyclingNonAliveConnectionClosesThatConnection() throws Exception {
    httpA.getSocket().shutdownInput();
    pool.recycle(httpA); // Should close httpA.
    assertTrue(httpA.getSocket().isClosed());

    // The pool should remain empty, and there is no need to schedule a cleanup.
    assertPooled(pool);
    cleanupExecutor.assertExecutionScheduled(false);
  }

  @Test public void shareHttpConnectionFails() throws Exception {
    try {
      pool.share(httpA);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    // The pool should remain empty, and there is no need to schedule a cleanup.
    assertPooled(pool);
    cleanupExecutor.assertExecutionScheduled(false);
  }

  @Test public void recycleSpdyConnectionDoesNothing() throws Exception {
    pool.recycle(spdyA);
    // The pool should remain empty, and there is no need to schedule the cleanup.
    assertPooled(pool);
    cleanupExecutor.assertExecutionScheduled(false);
  }

  @Test public void validateIdleSpdyConnectionTimeout() throws Exception {
    pool.share(spdyA);
    assertPooled(pool, spdyA); // Connection should be in the pool.

    Thread.sleep((long) (KEEP_ALIVE_DURATION_MS * 0.7));
    pool.performCleanup();
    assertPooled(pool, spdyA); // Connection should still be in the pool.

    Thread.sleep((long) (KEEP_ALIVE_DURATION_MS * 0.4));
    pool.performCleanup();
    assertPooled(pool); // Connection should have been removed.
  }

  @Test public void validateIdleHttpConnectionTimeout() throws Exception {
    pool.recycle(httpA);
    assertPooled(pool, httpA); // Connection should be in the pool.
    cleanupExecutor.assertExecutionScheduled(true);

    Thread.sleep((long) (KEEP_ALIVE_DURATION_MS * 0.7));
    pool.performCleanup();
    assertPooled(pool, httpA); // Connection should still be in the pool.

    Thread.sleep((long) (KEEP_ALIVE_DURATION_MS * 0.4));
    pool.performCleanup();
    assertPooled(pool); // Connection should have been removed.
  }

  @Test public void maxConnections() throws IOException, InterruptedException {
    // Pool should be empty.
    assertEquals(0, pool.getConnectionCount());

    // http A should be added to the pool.
    pool.recycle(httpA);
    assertEquals(1, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(0, pool.getMultiplexedConnectionCount());

    // http B should be added to the pool.
    pool.recycle(httpB);
    assertEquals(2, pool.getConnectionCount());
    assertEquals(2, pool.getHttpConnectionCount());
    assertEquals(0, pool.getMultiplexedConnectionCount());

    // http C should be added
    pool.recycle(httpC);
    assertEquals(3, pool.getConnectionCount());
    assertEquals(3, pool.getHttpConnectionCount());
    assertEquals(0, pool.getSpdyConnectionCount());

    pool.performCleanup();

    // http A should be removed by cleanup.
    assertEquals(2, pool.getConnectionCount());
    assertEquals(2, pool.getHttpConnectionCount());
    assertEquals(0, pool.getMultiplexedConnectionCount());

    // spdy A should be added
    pool.share(spdyA);
    assertEquals(3, pool.getConnectionCount());
    assertEquals(2, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    pool.performCleanup();

    // http B should be removed by cleanup.
    assertEquals(2, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(1, pool.getMultiplexedConnectionCount());

    // http C should be returned.
    Connection recycledHttpConnection = pool.get(httpAddress);
    recycledHttpConnection.setOwner(owner);
    assertNotNull(recycledHttpConnection);
    assertTrue(recycledHttpConnection.isAlive());
    assertEquals(1, pool.getConnectionCount());
    assertEquals(0, pool.getHttpConnectionCount());
    assertEquals(1, pool.getMultiplexedConnectionCount());

    // spdy A will be returned but also kept in the pool.
    Connection sharedSpdyConnection = pool.get(spdyAddress);
    assertNotNull(sharedSpdyConnection);
    assertEquals(spdyA, sharedSpdyConnection);
    assertEquals(1, pool.getConnectionCount());
    assertEquals(0, pool.getHttpConnectionCount());
    assertEquals(1, pool.getMultiplexedConnectionCount());

    // http C should be added to the pool
    pool.recycle(httpC);
    assertEquals(2, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(1, pool.getMultiplexedConnectionCount());

    // An http connection should be removed from the pool.
    recycledHttpConnection = pool.get(httpAddress);
    assertNotNull(recycledHttpConnection);
    assertTrue(recycledHttpConnection.isAlive());
    assertEquals(1, pool.getConnectionCount());
    assertEquals(0, pool.getHttpConnectionCount());
    assertEquals(1, pool.getMultiplexedConnectionCount());

    // spdy A will be returned but also kept in the pool.
    sharedSpdyConnection = pool.get(spdyAddress);
    assertEquals(spdyA, sharedSpdyConnection);
    assertNotNull(sharedSpdyConnection);
    assertEquals(1, pool.getConnectionCount());
    assertEquals(0, pool.getHttpConnectionCount());
    assertEquals(1, pool.getMultiplexedConnectionCount());

    // http D should be added to the pool.
    pool.recycle(httpD);
    assertEquals(2, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(1, pool.getMultiplexedConnectionCount());

    // http E should be added to the pool.
    pool.recycle(httpE);
    assertEquals(3, pool.getConnectionCount());
    assertEquals(2, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    pool.performCleanup();

    // spdy A should be removed from the pool by cleanup.
    assertEquals(2, pool.getConnectionCount());
    assertEquals(2, pool.getHttpConnectionCount());
    assertEquals(0, pool.getMultiplexedConnectionCount());
  }

  @Test public void connectionCleanup() throws Exception {
    ConnectionPool pool = new ConnectionPool(10, KEEP_ALIVE_DURATION_MS);

    // Add 3 connections to the pool.
    pool.recycle(httpA);
    pool.recycle(httpB);
    pool.share(spdyA);

    // Give the cleanup callable time to run and settle down.
    Thread.sleep(100);

    // Kill http A.
    Util.closeQuietly(httpA.getSocket());

    assertEquals(3, pool.getConnectionCount());
    assertEquals(2, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    // Http A should be removed.
    pool.performCleanup();
    assertPooled(pool, spdyA, httpB);
    assertEquals(2, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(1, pool.getMultiplexedConnectionCount());

    // Now let enough time pass for the connections to expire.
    Thread.sleep(2 * KEEP_ALIVE_DURATION_MS);

    // All remaining connections should be removed.
    pool.performCleanup();
    assertEquals(0, pool.getConnectionCount());
  }

  @Test public void maxIdleConnectionsLimitEnforced() throws Exception {
    ConnectionPool pool = new ConnectionPool(2, KEEP_ALIVE_DURATION_MS);

    // Hit the max idle connections limit of 2.
    pool.recycle(httpA);
    pool.recycle(httpB);
    Thread.sleep(100); // Give the cleanup callable time to run.
    assertPooled(pool, httpB, httpA);

    // Adding httpC bumps httpA.
    pool.recycle(httpC);
    Thread.sleep(100); // Give the cleanup callable time to run.
    assertPooled(pool, httpC, httpB);

    // Adding httpD bumps httpB.
    pool.recycle(httpD);
    Thread.sleep(100); // Give the cleanup callable time to run.
    assertPooled(pool, httpD, httpC);

    // Adding httpE bumps httpC.
    pool.recycle(httpE);
    Thread.sleep(100); // Give the cleanup callable time to run.
    assertPooled(pool, httpE, httpD);
  }

  @Test public void evictAllConnections() throws Exception {
    resetWithPoolSize(10);
    pool.recycle(httpA);
    Util.closeQuietly(httpA.getSocket()); // Include a closed connection in the pool.
    pool.recycle(httpB);
    pool.share(spdyA);
    int connectionCount = pool.getConnectionCount();
    assertTrue(connectionCount == 2 || connectionCount == 3);

    pool.evictAll();
    assertEquals(0, pool.getConnectionCount());
  }

  @Test public void closeIfOwnedBy() throws Exception {
    httpA.closeIfOwnedBy(owner);
    assertFalse(httpA.isAlive());
    assertFalse(httpA.clearOwner());
  }

  @Test public void closeIfOwnedByDoesNothingIfNotOwner() throws Exception {
    httpA.closeIfOwnedBy(new Object());
    assertTrue(httpA.isAlive());
    assertTrue(httpA.clearOwner());
  }

  @Test public void closeIfOwnedByFailsForSpdyConnections() throws Exception {
    try {
      spdyA.closeIfOwnedBy(owner);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void cleanupRunnableStopsEventually() throws Exception {
    pool.recycle(httpA);
    pool.share(spdyA);
    assertPooled(pool, spdyA, httpA);

    // The cleanup should terminate once the pool is empty again.
    cleanupExecutor.fakeExecute();
    assertPooled(pool);

    cleanupExecutor.assertExecutionScheduled(false);

    // Adding a new connection should cause the cleanup to start up again.
    pool.recycle(httpB);

    cleanupExecutor.assertExecutionScheduled(true);

    // The cleanup should terminate once the pool is empty again.
    cleanupExecutor.fakeExecute();
    assertPooled(pool);
  }

  private void assertPooled(ConnectionPool pool, Connection... connections) throws Exception {
    assertEquals(Arrays.asList(connections), pool.getConnections());
  }

  /**
   * An executor that does not actually execute anything by default. See
   * {@link #fakeExecute()}.
   */
  private static class FakeExecutor implements Executor {

    private Runnable runnable;

    @Override
    public void execute(Runnable runnable) {
      // This is a bonus assertion for the invariant: At no time should two runnables be scheduled.
      assertNull(this.runnable);
      this.runnable = runnable;
    }

    public void assertExecutionScheduled(boolean expected) {
      assertEquals(expected, runnable != null);
    }

    /**
     * Executes the runnable.
     */
    public void fakeExecute() {
      Runnable toRun = this.runnable;
      this.runnable = null;
      toRun.run();
    }
  }
}
