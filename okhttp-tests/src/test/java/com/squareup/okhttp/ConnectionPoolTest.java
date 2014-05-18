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

import com.squareup.okhttp.internal.RecordingHostnameVerifier;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.AuthenticatorAdapter;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.squareup.okhttp.internal.http.RouteSelector.TLS_V1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ConnectionPoolTest {
  private static final int KEEP_ALIVE_DURATION_MS = 5000;
  private static final SSLContext sslContext = SslContextBuilder.localhost();

  private MockWebServer spdyServer;
  private InetSocketAddress spdySocketAddress;
  private Address spdyAddress;

  private MockWebServer httpServer;
  private Address httpAddress;
  private InetSocketAddress httpSocketAddress;

  private ConnectionPool pool;
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

    spdyServer = new MockWebServer();
    httpServer = new MockWebServer();
    spdyServer.useHttps(sslContext.getSocketFactory(), false);

    httpServer.play();
    httpAddress = new Address(httpServer.getHostName(), httpServer.getPort(), socketFactory, null,
        null, AuthenticatorAdapter.INSTANCE, null,
        Util.immutableList(Protocol.SPDY_3, Protocol.HTTP_1_1));
    httpSocketAddress = new InetSocketAddress(InetAddress.getByName(httpServer.getHostName()),
        httpServer.getPort());

    spdyServer.play();
    spdyAddress = new Address(spdyServer.getHostName(), spdyServer.getPort(), socketFactory,
        sslContext.getSocketFactory(), new RecordingHostnameVerifier(),
        AuthenticatorAdapter.INSTANCE, null,
        Util.immutableList(Protocol.SPDY_3, Protocol.HTTP_1_1));
    spdySocketAddress = new InetSocketAddress(InetAddress.getByName(spdyServer.getHostName()),
        spdyServer.getPort());

    Route httpRoute = new Route(httpAddress, Proxy.NO_PROXY, httpSocketAddress, TLS_V1);
    Route spdyRoute = new Route(spdyAddress, Proxy.NO_PROXY, spdySocketAddress, TLS_V1);
    pool = new ConnectionPool(poolSize, KEEP_ALIVE_DURATION_MS);
    httpA = new Connection(pool, httpRoute);
    httpA.connect(200, 200, 200, null);
    httpB = new Connection(pool, httpRoute);
    httpB.connect(200, 200, 200, null);
    httpC = new Connection(pool, httpRoute);
    httpC.connect(200, 200, 200, null);
    httpD = new Connection(pool, httpRoute);
    httpD.connect(200, 200, 200, null);
    httpE = new Connection(pool, httpRoute);
    httpE.connect(200, 200, 200, null);
    spdyA = new Connection(pool, spdyRoute);
    spdyA.connect(20000, 20000, 2000, null);

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

    connection = new Connection(
        pool, new Route(httpAddress, Proxy.NO_PROXY, httpSocketAddress, TLS_V1));
    connection.connect(200, 200, 200, null);
    connection.setOwner(owner);
    assertEquals(0, pool.getConnectionCount());

    pool.recycle(connection);
    assertNull(connection.getOwner());
    assertEquals(1, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(0, pool.getSpdyConnectionCount());

    Connection recycledConnection = pool.get(httpAddress);
    assertNull(connection.getOwner());
    assertEquals(connection, recycledConnection);
    assertTrue(recycledConnection.isAlive());

    recycledConnection = pool.get(httpAddress);
    assertNull(recycledConnection);
  }

  @Test public void poolPrefersMostRecentlyRecycled() throws Exception {
    pool.recycle(httpA);
    pool.recycle(httpB);
    pool.recycle(httpC);
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

  @Test public void idleConnectionNotReturned() throws Exception {
    pool.recycle(httpA);
    Thread.sleep(KEEP_ALIVE_DURATION_MS * 2);
    assertNull(pool.get(httpAddress));
    assertPooled(pool);
  }

  @Test public void maxIdleConnectionLimitIsEnforced() throws Exception {
    pool.recycle(httpA);
    pool.recycle(httpB);
    pool.recycle(httpC);
    pool.recycle(httpD);
    assertPooled(pool, httpD, httpC);
  }

  @Test public void expiredConnectionsAreEvicted() throws Exception {
    pool.recycle(httpA);
    pool.recycle(httpB);
    Thread.sleep(2 * KEEP_ALIVE_DURATION_MS);
    pool.get(spdyAddress); // Force the cleanup callable to run.
    assertPooled(pool);
  }

  @Test public void nonAliveConnectionNotReturned() throws Exception {
    pool.recycle(httpA);
    httpA.getSocket().close();
    assertNull(pool.get(httpAddress));
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
  }

  @Test public void shareHttpConnectionFails() throws Exception {
    try {
      pool.share(httpA);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertPooled(pool);
  }

  @Test public void recycleSpdyConnectionDoesNothing() throws Exception {
    pool.recycle(spdyA);
    assertPooled(pool);
  }

  @Test public void validateIdleSpdyConnectionTimeout() throws Exception {
    pool.share(spdyA);
    Thread.sleep((int) (KEEP_ALIVE_DURATION_MS * 0.7));
    assertNull(pool.get(httpAddress));
    assertPooled(pool, spdyA); // Connection should still be in the pool.
    Thread.sleep((int) (KEEP_ALIVE_DURATION_MS * 0.4));
    assertNull(pool.get(httpAddress));
    assertPooled(pool);
  }

  @Test public void validateIdleHttpConnectionTimeout() throws Exception {
    pool.recycle(httpA);
    Thread.sleep((int) (KEEP_ALIVE_DURATION_MS * 0.7));
    assertNull(pool.get(spdyAddress));
    assertPooled(pool, httpA); // Connection should still be in the pool.
    Thread.sleep((int) (KEEP_ALIVE_DURATION_MS * 0.4));
    assertNull(pool.get(spdyAddress));
    assertPooled(pool);
  }

  @Test public void maxConnections() throws IOException, InterruptedException {
    // Pool should be empty.
    assertEquals(0, pool.getConnectionCount());

    // http A should be added to the pool.
    pool.recycle(httpA);
    assertEquals(1, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(0, pool.getSpdyConnectionCount());

    // http B should be added to the pool.
    pool.recycle(httpB);
    assertEquals(2, pool.getConnectionCount());
    assertEquals(2, pool.getHttpConnectionCount());
    assertEquals(0, pool.getSpdyConnectionCount());

    // http C should be added and http A should be removed.
    pool.recycle(httpC);
    Thread.sleep(50);
    assertEquals(2, pool.getConnectionCount());
    assertEquals(2, pool.getHttpConnectionCount());
    assertEquals(0, pool.getSpdyConnectionCount());

    // spdy A should be added and http B should be removed.
    pool.share(spdyA);
    Thread.sleep(50);
    assertEquals(2, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    // http C should be removed from the pool.
    Connection recycledHttpConnection = pool.get(httpAddress);
    recycledHttpConnection.setOwner(owner);
    assertNotNull(recycledHttpConnection);
    assertTrue(recycledHttpConnection.isAlive());
    assertEquals(1, pool.getConnectionCount());
    assertEquals(0, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    // spdy A will be returned and kept in the pool.
    Connection sharedSpdyConnection = pool.get(spdyAddress);
    assertNotNull(sharedSpdyConnection);
    assertEquals(spdyA, sharedSpdyConnection);
    assertEquals(1, pool.getConnectionCount());
    assertEquals(0, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    // Nothing should change.
    pool.recycle(httpC);
    Thread.sleep(50);
    assertEquals(2, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    // An http connection should be removed from the pool.
    recycledHttpConnection = pool.get(httpAddress);
    assertNotNull(recycledHttpConnection);
    assertTrue(recycledHttpConnection.isAlive());
    assertEquals(1, pool.getConnectionCount());
    assertEquals(0, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    // spdy A will be returned and kept in the pool. Pool shouldn't change.
    sharedSpdyConnection = pool.get(spdyAddress);
    assertEquals(spdyA, sharedSpdyConnection);
    assertNotNull(sharedSpdyConnection);
    assertEquals(1, pool.getConnectionCount());
    assertEquals(0, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    // http D should be added to the pool.
    pool.recycle(httpD);
    Thread.sleep(50);
    assertEquals(2, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    // http E should be added to the pool. spdy A should be removed from the pool.
    pool.recycle(httpE);
    Thread.sleep(50);
    assertEquals(2, pool.getConnectionCount());
    assertEquals(2, pool.getHttpConnectionCount());
    assertEquals(0, pool.getSpdyConnectionCount());
  }

  @Test public void connectionCleanup() throws IOException, InterruptedException {
    ConnectionPool pool = new ConnectionPool(10, KEEP_ALIVE_DURATION_MS);

    // Add 3 connections to the pool.
    pool.recycle(httpA);
    pool.recycle(httpB);
    pool.share(spdyA);
    assertEquals(3, pool.getConnectionCount());
    assertEquals(2, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    // Kill http A.
    Util.closeQuietly(httpA.getSocket());

    // Force pool to run a clean up.
    assertNotNull(pool.get(spdyAddress));
    Thread.sleep(50);

    assertEquals(2, pool.getConnectionCount());
    assertEquals(1, pool.getHttpConnectionCount());
    assertEquals(1, pool.getSpdyConnectionCount());

    Thread.sleep(KEEP_ALIVE_DURATION_MS);
    // Force pool to run a clean up.
    assertNull(pool.get(httpAddress));
    assertNull(pool.get(spdyAddress));

    Thread.sleep(50);

    assertEquals(0, pool.getConnectionCount());
    assertEquals(0, pool.getHttpConnectionCount());
    assertEquals(0, pool.getSpdyConnectionCount());
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

  private void assertPooled(ConnectionPool pool, Connection... connections) throws Exception {
    assertEquals(Arrays.asList(connections), pool.getConnections());
  }
}
