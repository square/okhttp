/*
 * Copyright (C) 2017 Square, Inc.
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
package okhttp3;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import okhttp3.internal.DoubleInetAddressDns;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.SingleInetAddressDns;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.TestUtil.defaultClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class EventListenerTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private final SingleInetAddressDns singleDns = new SingleInetAddressDns();
  private final RecordingEventListener listener = new RecordingEventListener();
  private final SslClient sslClient = SslClient.localhost();

  private OkHttpClient client;
  private SocksProxy socksProxy;

  @Before public void setUp() throws IOException {
    client = defaultClient().newBuilder()
        .dns(singleDns)
        .eventListener(listener)
        .build();
  }

  @After public void tearDown() throws Exception {
    if (socksProxy != null) {
      socksProxy.shutdown();
    }
  }

  @Test public void successfulCallEventSequence() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    List<Class<? extends CallEvent>> expectedEvents = Arrays.asList(FetchStart.class,
        DnsStart.class, DnsEnd.class, ConnectStart.class, ConnectEnd.class,
        ConnectionAcquired.class, RequestHeadersStart.class, RequestHeadersEnd.class,
        ResponseHeadersStart.class, ResponseHeadersEnd.class, ResponseBodyStart.class,
        ResponseBodyEnd.class, FetchEnd.class);
    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  @Test public void successfulHttpsCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    List<Class<? extends CallEvent>> expectedEvents = Arrays.asList(FetchStart.class,
        DnsStart.class, DnsEnd.class, ConnectStart.class, SecureConnectStart.class,
        SecureConnectEnd.class, ConnectEnd.class,
        ConnectionAcquired.class, RequestHeadersStart.class, RequestHeadersEnd.class,
        ResponseHeadersStart.class, ResponseHeadersEnd.class, ResponseBodyStart.class,
        ResponseBodyEnd.class, FetchEnd.class);
    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  @Test public void successfulDnsLookup() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    DnsStart dnsStart = listener.removeUpToEvent(DnsStart.class);
    assertSame(call, dnsStart.call);
    assertEquals("localhost", dnsStart.domainName);

    DnsEnd dnsEnd = listener.removeUpToEvent(DnsEnd.class);
    assertSame(call, dnsEnd.call);
    assertEquals("localhost", dnsEnd.domainName);
    assertEquals(1, dnsEnd.inetAddressList.size());
    assertNull(dnsEnd.throwable);
  }

  @Test public void noDnsLookupOnPooledConnection() throws IOException {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertEquals(200, response1.code());
    response1.body().close();

    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals(200, response2.code());
    response2.body().close();

    List<Class<?>> recordedEvents = listener.recordedEventTypes();
    assertFalse(recordedEvents.contains(DnsStart.class));
    assertFalse(recordedEvents.contains(DnsEnd.class));
  }

  @Test public void multipleDnsLookupsForSingleCall() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .setHeader("Location", "http://www.fakeurl:" + server.getPort()));
    server.enqueue(new MockResponse());

    FakeDns dns = new FakeDns();
    dns.set("fakeurl", singleDns.lookup(server.getHostName()));
    dns.set("www.fakeurl", singleDns.lookup(server.getHostName()));

    client = client.newBuilder()
        .dns(dns)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url("http://fakeurl:" + server.getPort())
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    listener.removeUpToEvent(DnsStart.class);
    listener.removeUpToEvent(DnsEnd.class);
    listener.removeUpToEvent(DnsStart.class);
    listener.removeUpToEvent(DnsEnd.class);
  }

  @Test public void failedDnsLookup() {
    client = client.newBuilder()
        .dns(new FakeDns())
        .build();
    Call call = client.newCall(new Request.Builder()
        .url("http://fakeurl/")
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    listener.removeUpToEvent(DnsStart.class);

    DnsEnd dnsEnd = listener.removeUpToEvent(DnsEnd.class);
    assertSame(call, dnsEnd.call);
    assertEquals("fakeurl", dnsEnd.domainName);
    assertNull(dnsEnd.inetAddressList);
    assertTrue(dnsEnd.throwable instanceof UnknownHostException);
  }

  @Test public void emptyDnsLookup() {
    Dns emptyDns = new Dns() {
      @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        return Collections.emptyList();
      }
    };

    client = client.newBuilder()
        .dns(emptyDns)
        .build();
    Call call = client.newCall(new Request.Builder()
        .url("http://fakeurl/")
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    listener.removeUpToEvent(DnsStart.class);

    DnsEnd dnsEnd = listener.removeUpToEvent(DnsEnd.class);
    assertSame(call, dnsEnd.call);
    assertEquals("fakeurl", dnsEnd.domainName);
    assertNull(dnsEnd.inetAddressList);
    assertTrue(dnsEnd.throwable instanceof UnknownHostException);
  }

  @Test public void successfulConnect() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    InetAddress address = singleDns.lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    assertSame(call, connectStart.call);
    assertEquals(expectedAddress, connectStart.inetSocketAddress);
    assertEquals(Proxy.NO_PROXY, connectStart.proxy);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    assertSame(call, connectEnd.call);
    assertEquals(expectedAddress, connectEnd.inetSocketAddress);
    assertEquals(Protocol.HTTP_1_1, connectEnd.protocol);
    assertNull(connectEnd.throwable);
  }

  @Test public void failedConnect() throws UnknownHostException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    InetAddress address = singleDns.lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    assertSame(call, connectStart.call);
    assertEquals(expectedAddress, connectStart.inetSocketAddress);
    assertEquals(Proxy.NO_PROXY, connectStart.proxy);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    assertSame(call, connectEnd.call);
    assertEquals(expectedAddress, connectEnd.inetSocketAddress);
    assertNull(connectEnd.protocol);
    assertTrue(connectEnd.throwable instanceof IOException);
  }

  @Test public void multipleConnectsForSingleCall() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    listener.removeUpToEvent(ConnectStart.class);
    listener.removeUpToEvent(ConnectEnd.class);
    listener.removeUpToEvent(ConnectStart.class);
    listener.removeUpToEvent(ConnectEnd.class);
  }

  @Test public void successfulHttpProxyConnect() throws IOException {
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url("http://www.fakeurl")
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    InetAddress address = singleDns.lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    assertSame(call, connectStart.call);
    assertEquals(expectedAddress, connectStart.inetSocketAddress);
    assertEquals(server.toProxyAddress(), connectStart.proxy);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    assertSame(call, connectEnd.call);
    assertEquals(expectedAddress, connectEnd.inetSocketAddress);
    assertEquals(Protocol.HTTP_1_1, connectEnd.protocol);
    assertNull(connectEnd.throwable);
  }

  @Test public void successfulSocksProxyConnect() throws Exception {
    server.enqueue(new MockResponse());

    socksProxy = new SocksProxy();
    socksProxy.play();
    Proxy proxy = socksProxy.proxy();

    client = client.newBuilder()
        .proxy(proxy)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url("http://" + SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS + ":" + server.getPort())
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    InetSocketAddress expectedAddress = InetSocketAddress.createUnresolved(
        SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    assertSame(call, connectStart.call);
    assertEquals(expectedAddress, connectStart.inetSocketAddress);
    assertEquals(proxy, connectStart.proxy);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    assertSame(call, connectEnd.call);
    assertEquals(expectedAddress, connectEnd.inetSocketAddress);
    assertEquals(Protocol.HTTP_1_1, connectEnd.protocol);
    assertNull(connectEnd.throwable);
  }

  @Test public void authenticatingTunnelProxyConnect() throws IOException {
    enableTlsWithTunnel(true);
    server.enqueue(new MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
        .addHeader("Connection: close"));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(new RecordingOkAuthenticator("password"))
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    listener.removeUpToEvent(ConnectStart.class);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    assertNull(connectEnd.protocol);
    assertNull(connectEnd.throwable);

    listener.removeUpToEvent(ConnectStart.class);
    listener.removeUpToEvent(ConnectEnd.class);
  }

  @Test public void successfulSecureConnect() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    assertSame(call, secureStart.call);

    SecureConnectEnd secureEnd = listener.removeUpToEvent(SecureConnectEnd.class);
    assertSame(call, secureEnd.call);
    assertNotNull(secureEnd.handshake);
    assertNull(secureEnd.throwable);
  }

  @Test public void failedSecureConnect() {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    assertSame(call, secureStart.call);

    SecureConnectEnd secureEnd = listener.removeUpToEvent(SecureConnectEnd.class);
    assertSame(call, secureEnd.call);
    assertNull(secureEnd.handshake);
    assertTrue(secureEnd.throwable instanceof IOException);
  }

  @Test public void secureConnectWithTunnel() throws IOException {
    enableTlsWithTunnel(true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    assertSame(call, secureStart.call);

    SecureConnectEnd secureEnd = listener.removeUpToEvent(SecureConnectEnd.class);
    assertSame(call, secureEnd.call);
    assertNotNull(secureEnd.handshake);
    assertNull(secureEnd.throwable);
  }

  @Test public void multipleSecureConnectsForSingleCall() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    listener.removeUpToEvent(SecureConnectStart.class);
    listener.removeUpToEvent(SecureConnectEnd.class);

    listener.removeUpToEvent(SecureConnectStart.class);
    listener.removeUpToEvent(SecureConnectEnd.class);
  }

  @Test public void noSecureConnectsOnPooledConnection() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertEquals(200, response1.code());
    response1.body().close();

    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals(200, response2.code());
    response2.body().close();

    List<Class<?>> recordedEvents = listener.recordedEventTypes();
    assertFalse(recordedEvents.contains(SecureConnectStart.class));
    assertFalse(recordedEvents.contains(SecureConnectEnd.class));
  }

  @Test public void successfulConnectionFound() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    ConnectionAcquired connectionFound = listener.removeUpToEvent(ConnectionAcquired.class);
    assertSame(call, connectionFound.call);
    assertNotNull(connectionFound.connection);
  }

  @Test public void noConnectionFoundOnFollowUp() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location", "/foo"));
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals("ABC", response.body().string());

    listener.removeUpToEvent(ConnectionAcquired.class);

    List<Class<?>> remainingEvents = listener.recordedEventTypes();
    assertFalse(remainingEvents.contains(ConnectionAcquired.class));
  }

  @Test public void pooledConnectionFound() throws IOException {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertEquals(200, response1.code());
    response1.body().close();

    ConnectionAcquired connectionFound1 = listener.removeUpToEvent(ConnectionAcquired.class);
    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals(200, response2.code());
    response2.body().close();

    ConnectionAcquired connectionFound2 = listener.removeUpToEvent(ConnectionAcquired.class);
    assertSame(connectionFound1.connection, connectionFound2.connection);
  }

  @Test public void multipleConnectionsFoundForSingleCall() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location", "/foo")
        .addHeader("Connection", "Close"));
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals("ABC", response.body().string());

    listener.removeUpToEvent(ConnectionAcquired.class);
    listener.removeUpToEvent(ConnectionAcquired.class);
  }

  private void enableTlsWithTunnel(boolean tunnelProxy) {
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(sslClient.socketFactory, tunnelProxy);
  }

  static class CallEvent {
    final Call call;
    final List<Object> params;

    CallEvent(Call call, Object... params) {
      this.call = call;
      this.params = Arrays.asList(params);
    }
  }

  static final class DnsStart extends CallEvent {
    final String domainName;

    DnsStart(Call call, String domainName) {
      super(call, domainName);
      this.domainName = domainName;
    }
  }

  static final class DnsEnd extends CallEvent {
    final String domainName;
    final List<InetAddress> inetAddressList;
    final Throwable throwable;

    DnsEnd(Call call, String domainName, List<InetAddress> inetAddressList, Throwable throwable) {
      super(call, domainName, inetAddressList, throwable);
      this.domainName = domainName;
      this.inetAddressList = inetAddressList;
      this.throwable = throwable;
    }
  }

  static final class ConnectStart extends CallEvent {
    final InetSocketAddress inetSocketAddress;
    final Proxy proxy;

    ConnectStart(Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
      super(call, inetSocketAddress, proxy);
      this.inetSocketAddress = inetSocketAddress;
      this.proxy = proxy;
    }
  }

  static final class ConnectEnd extends CallEvent {
    final InetSocketAddress inetSocketAddress;
    final Protocol protocol;
    final Throwable throwable;

    ConnectEnd(Call call, InetSocketAddress inetSocketAddress, Protocol protocol,
        Throwable throwable) {
      super(call, inetSocketAddress, protocol, throwable);
      this.inetSocketAddress = inetSocketAddress;
      this.protocol = protocol;
      this.throwable = throwable;
    }
  }

  static final class SecureConnectStart extends CallEvent {
    SecureConnectStart(Call call) {
      super(call);
    }
  }

  static final class SecureConnectEnd extends CallEvent {
    final Handshake handshake;
    final Throwable throwable;

    SecureConnectEnd(Call call, Handshake handshake, Throwable throwable) {
      super(call, handshake, throwable);
      this.handshake = handshake;
      this.throwable = throwable;
    }
  }

  static final class ConnectionAcquired extends CallEvent {
    final Connection connection;

    ConnectionAcquired(Call call, Connection connection) {
      super(call, connection);
      this.connection = connection;
    }
  }

  static final class ConnectionReleased extends CallEvent {
    final Connection connection;

    ConnectionReleased(Call call, Connection connection) {
      super(call, connection);
      this.connection = connection;
    }
  }

  static final class FetchStart extends CallEvent {
    FetchStart(Call call) {
      super(call);
    }
  }

  static final class FetchEnd extends CallEvent {
    final Throwable throwable;

    FetchEnd(Call call, Throwable throwable) {
      super(call, throwable);
      this.throwable = throwable;
    }
  }

  static final class RequestHeadersStart extends CallEvent {
    RequestHeadersStart(Call call) {
      super(call);
    }
  }

  static final class RequestHeadersEnd extends CallEvent {
    final Throwable throwable;

    RequestHeadersEnd(Call call, Throwable throwable) {
      super(call, throwable);
      this.throwable = throwable;
    }
  }

  static final class RequestBodyStart extends CallEvent {
    RequestBodyStart(Call call) {
      super(call);
    }
  }

  static final class RequestBodyEnd extends CallEvent {
    final Throwable throwable;

    RequestBodyEnd(Call call, Throwable throwable) {
      super(call, throwable);
      this.throwable = throwable;
    }
  }

  static final class ResponseHeadersStart extends CallEvent {
    ResponseHeadersStart(Call call) {
      super(call);
    }
  }

  static final class ResponseHeadersEnd extends CallEvent {
    final Throwable throwable;

    ResponseHeadersEnd(Call call, Throwable throwable) {
      super(call, throwable);
      this.throwable = throwable;
    }
  }

  static final class ResponseBodyStart extends CallEvent {
    ResponseBodyStart(Call call) {
      super(call);
    }
  }

  static final class ResponseBodyEnd extends CallEvent {
    final Throwable throwable;

    ResponseBodyEnd(Call call, Throwable throwable) {
      super(call, throwable);
      this.throwable = throwable;
    }
  }

  static final class RecordingEventListener extends EventListener {
    final Deque<CallEvent> eventSequence = new ArrayDeque<>();

    /**
     * Removes recorded events up to (and including) an event is found whose class equals
     * {@code eventClass} and returns it.
     */
    <T> T removeUpToEvent(Class<T> eventClass) {
      Object event = eventSequence.poll();
      while (event != null && !eventClass.isInstance(event)) {
        event = eventSequence.poll();
      }
      if (event == null) throw new AssertionError();
      return (T) event;
    }

    List<Class<?>> recordedEventTypes() {
      List<Class<?>> eventTypes = new ArrayList<>();
      for (Object event : eventSequence) {
        eventTypes.add(event.getClass());
      }
      return eventTypes;
    }

    void clearAllEvents() {
      eventSequence.clear();
    }

    private void logEvent(CallEvent e) {
      eventSequence.offer(e);
    }

    @Override public void dnsStart(Call call, String domainName) {
      logEvent(new DnsStart(call, domainName));
    }

    @Override public void dnsEnd(Call call, String domainName, List<InetAddress> inetAddressList,
        Throwable throwable) {
      logEvent(new DnsEnd(call, domainName, inetAddressList, throwable));
    }

    @Override public void connectStart(Call call, InetSocketAddress inetSocketAddress,
        Proxy proxy) {
      logEvent(new ConnectStart(call, inetSocketAddress, proxy));
    }

    @Override public void secureConnectStart(Call call) {
      logEvent(new SecureConnectStart(call));
    }

    @Override public void secureConnectEnd(Call call, Handshake handshake, Throwable throwable) {
      logEvent(new SecureConnectEnd(call, handshake, throwable));
    }

    @Override public void connectEnd(Call call, InetSocketAddress inetSocketAddress,
        Protocol protocol, Throwable throwable) {
      logEvent(new ConnectEnd(call, inetSocketAddress, protocol, throwable));
    }

    @Override public void connectionAcquired(Call call, Connection connection) {
      logEvent(new ConnectionAcquired(call, connection));
    }

    @Override public void connectionReleased(Call call, Connection connection) {
      logEvent(new ConnectionAcquired(call, connection));
    }

    @Override public void fetchStart(Call call) {
      logEvent(new FetchStart(call));
    }

    @Override public void requestHeadersStart(Call call) {
      logEvent(new RequestHeadersStart(call));
    }

    @Override public void requestHeadersEnd(Call call, Throwable throwable) {
      logEvent(new RequestHeadersEnd(call, throwable));
    }

    @Override public void requestBodyStart(Call call) {
      logEvent(new RequestBodyStart(call));
    }

    @Override public void requestBodyEnd(Call call, Throwable throwable) {
      logEvent(new RequestBodyEnd(call, throwable));
    }

    @Override public void responseHeadersStart(Call call) {
      logEvent(new ResponseHeadersStart(call));
    }

    @Override public void responseHeadersEnd(Call call, Throwable throwable) {
      logEvent(new ResponseHeadersEnd(call, throwable));
    }

    @Override public void responseBodyStart(Call call) {
      logEvent(new ResponseBodyStart(call));
    }

    @Override public void responseBodyEnd(Call call, Throwable throwable) {
      logEvent(new ResponseBodyEnd(call, throwable));
    }

    @Override public void fetchEnd(Call call, Throwable throwable) {
      logEvent(new FetchEnd(call, throwable));
    }
  }
}
