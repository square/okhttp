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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.RecordingEventListener.CallEvent;
import okhttp3.RecordingEventListener.ConnectEnd;
import okhttp3.RecordingEventListener.ConnectStart;
import okhttp3.RecordingEventListener.ConnectionAcquired;
import okhttp3.RecordingEventListener.ConnectionReleased;
import okhttp3.RecordingEventListener.DnsEnd;
import okhttp3.RecordingEventListener.DnsStart;
import okhttp3.RecordingEventListener.FetchEnd;
import okhttp3.RecordingEventListener.FetchStart;
import okhttp3.RecordingEventListener.RequestHeadersEnd;
import okhttp3.RecordingEventListener.RequestHeadersStart;
import okhttp3.RecordingEventListener.ResponseBodyEnd;
import okhttp3.RecordingEventListener.ResponseBodyStart;
import okhttp3.RecordingEventListener.ResponseHeadersEnd;
import okhttp3.RecordingEventListener.ResponseHeadersStart;
import okhttp3.RecordingEventListener.SecureConnectEnd;
import okhttp3.RecordingEventListener.SecureConnectStart;
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

    // TODO ResponseBodyEnd should not be last event
    List<String> expectedEvents = Arrays.asList("FetchStart",
        "DnsStart", "DnsEnd", "ConnectStart", "ConnectEnd",
        "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd",
        "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ConnectionReleased", "FetchEnd", "ResponseBodyEnd");
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

    // TODO ResponseBodyEnd should not be last event
    List<String> expectedEvents = Arrays.asList("FetchStart",
        "DnsStart", "DnsEnd", "ConnectStart", "SecureConnectStart",
        "SecureConnectEnd", "ConnectEnd",
        "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd",
        "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ConnectionReleased", "FetchEnd", "ResponseBodyEnd");
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

    List<String> recordedEvents = listener.recordedEventTypes();
    assertFalse(recordedEvents.contains("DnsStart"));
    assertFalse(recordedEvents.contains("DnsEnd"));
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

    List<String> recordedEvents = listener.recordedEventTypes();
    assertFalse(recordedEvents.contains("SecureConnectStart"));
    assertFalse(recordedEvents.contains("SecureConnectEnd"));
  }

  @Test public void successfulConnectionFound() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    ConnectionAcquired connectionAcquired = listener.removeUpToEvent(ConnectionAcquired.class);
    assertSame(call, connectionAcquired.call);
    assertNotNull(connectionAcquired.connection);
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

    List<String> remainingEvents = listener.recordedEventTypes();
    assertFalse(remainingEvents.contains("ConnectionAcquired"));
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

    ConnectionAcquired connectionAcquired1 = listener.removeUpToEvent(ConnectionAcquired.class);
    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals(200, response2.code());
    response2.body().close();

    ConnectionAcquired connectionAcquired2 = listener.removeUpToEvent(ConnectionAcquired.class);
    assertSame(connectionAcquired1.connection, connectionAcquired2.connection);
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
}
