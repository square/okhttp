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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.RecordingEventListener.CallEnd;
import okhttp3.RecordingEventListener.CallFailed;
import okhttp3.RecordingEventListener.ConnectEnd;
import okhttp3.RecordingEventListener.ConnectFailed;
import okhttp3.RecordingEventListener.ConnectStart;
import okhttp3.RecordingEventListener.ConnectionAcquired;
import okhttp3.RecordingEventListener.DnsEnd;
import okhttp3.RecordingEventListener.DnsStart;
import okhttp3.RecordingEventListener.RequestBodyEnd;
import okhttp3.RecordingEventListener.RequestHeadersEnd;
import okhttp3.RecordingEventListener.ResponseBodyEnd;
import okhttp3.RecordingEventListener.ResponseHeadersEnd;
import okhttp3.RecordingEventListener.SecureConnectEnd;
import okhttp3.RecordingEventListener.SecureConnectStart;
import okhttp3.internal.DoubleInetAddressDns;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.SingleInetAddressDns;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.mockwebserver.internal.tls.SslClient;
import okio.Buffer;
import okio.BufferedSink;
import org.hamcrest.BaseMatcher;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Arrays.asList;
import static okhttp3.TestUtil.defaultClient;
import static org.hamcrest.CoreMatchers.any;
import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

public final class EventListenerTest {
  public static final Matcher<Response> anyResponse = CoreMatchers.any(Response.class);
  @Rule public final MockWebServer server = new MockWebServer();

  private final SingleInetAddressDns singleDns = new SingleInetAddressDns();
  private final RecordingEventListener listener = new RecordingEventListener();
  private final SslClient sslClient = SslClient.localhost();

  private OkHttpClient client;
  private SocksProxy socksProxy;

  @Before public void setUp() {
    client = defaultClient().newBuilder()
        .dns(singleDns)
        .eventListener(listener)
        .build();

    listener.forbidLock(client.connectionPool());
    listener.forbidLock(client.dispatcher());
  }

  @After public void tearDown() throws Exception {
    if (socksProxy != null) {
      socksProxy.shutdown();
    }
  }

  @Test public void successfulCallEventSequence() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    assertEquals("abc", response.body().string());
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  @Test public void successfulCallEventSequenceForEnqueue() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    final CountDownLatch completionLatch = new CountDownLatch(1);
    Callback callback = new Callback() {
      @Override public void onFailure(Call call, IOException e) {
        completionLatch.countDown();
      }

      @Override public void onResponse(Call call, Response response) {
        response.close();
        completionLatch.countDown();
      }
    };

    call.enqueue(callback);

    completionLatch.await();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  @Test public void failedCallEventSequence() {
    server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS));

    client = client.newBuilder().readTimeout(250, TimeUnit.MILLISECONDS).build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage(), either(equalTo("timeout")).or(equalTo("Read timed out")));
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ConnectionReleased", "CallFailed");
    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  @Test public void failedDribbledCallEventSequence() throws IOException {
    server.enqueue(new MockResponse().setBody("0123456789")
        .throttleBody(2, 100, TimeUnit.MILLISECONDS)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    client = client.newBuilder()
        .protocols(Collections.singletonList(Protocol.HTTP_1_1))
        .readTimeout(250, TimeUnit.MILLISECONDS)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    Response response = call.execute();
    try {
      response.body.string();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage(), equalTo("unexpected end of stream"));
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallFailed");
    assertEquals(expectedEvents, listener.recordedEventTypes());
    ResponseBodyEnd bodyEnd = listener.removeUpToEvent(ResponseBodyEnd.class);
    assertEquals(5, bodyEnd.bytesRead);
  }

  @Test public void canceledCallEventSequence() {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    call.cancel();
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      assertEquals("Canceled", expected.getMessage());
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "CallFailed");
    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  private void assertSuccessfulEventOrder(Matcher<Response> responseMatcher) throws IOException {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().string();
    response.body().close();

    assumeThat(response, responseMatcher);

    List<String> expectedEvents = asList("CallStart", "DnsStart", "DnsEnd", "ConnectStart",
        "SecureConnectStart", "SecureConnectEnd", "ConnectEnd", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");

    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  @Test public void secondCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build()).execute().close();

    listener.removeUpToEvent(CallEnd.class);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    response.close();

    List<String> expectedEvents = asList("CallStart", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");

    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  private void assertBytesReadWritten(RecordingEventListener listener,
      @Nullable Matcher<Long> requestHeaderLength, @Nullable Matcher<Long> requestBodyBytes,
      @Nullable Matcher<Long> responseHeaderLength, @Nullable Matcher<Long> responseBodyBytes) {

    if (requestHeaderLength != null) {
      RequestHeadersEnd responseHeadersEnd = listener.removeUpToEvent(RequestHeadersEnd.class);
      assertThat("request header length", responseHeadersEnd.headerLength, requestHeaderLength);
    } else {
      assertFalse("Found RequestHeadersEnd",
          listener.recordedEventTypes().contains("RequestHeadersEnd"));
    }

    if (requestBodyBytes != null) {
      RequestBodyEnd responseBodyEnd = listener.removeUpToEvent(RequestBodyEnd.class);
      assertThat("request body bytes", responseBodyEnd.bytesWritten, requestBodyBytes);
    } else {
      assertFalse("Found RequestBodyEnd", listener.recordedEventTypes().contains("RequestBodyEnd"));
    }

    if (responseHeaderLength != null) {
      ResponseHeadersEnd responseHeadersEnd = listener.removeUpToEvent(ResponseHeadersEnd.class);
      assertThat("response header length", responseHeadersEnd.headerLength, responseHeaderLength);
    } else {
      assertFalse("Found ResponseHeadersEnd",
          listener.recordedEventTypes().contains("ResponseHeadersEnd"));
    }

    if (responseBodyBytes != null) {
      ResponseBodyEnd responseBodyEnd = listener.removeUpToEvent(ResponseBodyEnd.class);
      assertThat("response body bytes", responseBodyEnd.bytesRead, responseBodyBytes);
    } else {
      assertFalse("Found ResponseBodyEnd",
          listener.recordedEventTypes().contains("ResponseBodyEnd"));
    }
  }

  private Matcher<Long> greaterThan(final long value) {
    return new BaseMatcher<Long>() {
      @Override public void describeTo(Description description) {
        description.appendText("> " + value);
      }

      @Override public boolean matches(Object o) {
        return ((Long)o) > value;
      }
    };
  }

  private Matcher<Long> lessThan(final long value) {
    return new BaseMatcher<Long>() {
      @Override public void describeTo(Description description) {
        description.appendText("< " + value);
      }

      @Override public boolean matches(Object o) {
        return ((Long)o) < value;
      }
    };
  }

  private Matcher<Response> matchesProtocol(final Protocol protocol) {
    return new BaseMatcher<Response>() {
      @Override public void describeTo(Description description) {
        description.appendText("is HTTP/2");
      }

      @Override public boolean matches(Object o) {
        return ((Response)o).protocol == protocol;
      }
    };
  }

  @Test public void successfulEmptyH2CallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    server.enqueue(new MockResponse());

    assertSuccessfulEventOrder(matchesProtocol(Protocol.HTTP_2));

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(0L));
  }

  @Test public void successfulEmptyHttpsCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    assertSuccessfulEventOrder(anyResponse);

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(3L));
  }

  @Test public void successfulChunkedHttpsCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    server.enqueue(
        new MockResponse().setBodyDelay(100, TimeUnit.MILLISECONDS).setChunkedBody("Hello!", 2));

    assertSuccessfulEventOrder(anyResponse);

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(6L));
  }

  @Test public void successfulChunkedH2CallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    server.enqueue(
        new MockResponse().setBodyDelay(100, TimeUnit.MILLISECONDS).setChunkedBody("Hello!", 2));

    assertSuccessfulEventOrder(matchesProtocol(Protocol.HTTP_2));

    assertBytesReadWritten(listener, any(Long.class), null, equalTo(0L),
        greaterThan(6L));
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
    assertEquals(server.getHostName(), dnsStart.domainName);

    DnsEnd dnsEnd = listener.removeUpToEvent(DnsEnd.class);
    assertSame(call, dnsEnd.call);
    assertEquals(server.getHostName(), dnsEnd.domainName);
    assertEquals(1, dnsEnd.inetAddressList.size());
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

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    assertSame(call, callFailed.call);
    assertTrue(callFailed.ioe instanceof UnknownHostException);
  }

  @Test public void emptyDnsLookup() {
    Dns emptyDns = new Dns() {
      @Override public List<InetAddress> lookup(String hostname) {
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

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    assertSame(call, callFailed.call);
    assertTrue(callFailed.ioe instanceof UnknownHostException);
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

    ConnectFailed connectFailed = listener.removeUpToEvent(ConnectFailed.class);
    assertSame(call, connectFailed.call);
    assertEquals(expectedAddress, connectFailed.inetSocketAddress);
    assertNull(connectFailed.protocol);
    assertNotNull(connectFailed.ioe);
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
    listener.removeUpToEvent(ConnectFailed.class);
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

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    assertSame(call, callFailed.call);
    assertNotNull(callFailed.ioe);
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
    listener.removeUpToEvent(ConnectFailed.class);

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

  @Test public void responseBodyFailHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    responseBodyFail(Protocol.HTTP_1_1);
  }

  @Test public void responseBodyFailHttp2OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    responseBodyFail(Protocol.HTTP_2);
  }

  @Test public void responseBodyFailHttp() throws IOException {
    responseBodyFail(Protocol.HTTP_1_1);
  }

  private void responseBodyFail(Protocol expectedProtocol) throws IOException {
    // Use a 2 MiB body so the disconnect won't happen until the client has read some data.
    int responseBodySize = 2 * 1024 * 1024; // 2 MiB
    server.enqueue(new MockResponse()
        .setBody(new Buffer().write(new byte[responseBodySize]))
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    if (expectedProtocol == Protocol.HTTP_2) {
      // soft failure since client may not support depending on Platform
      assumeThat(response, matchesProtocol(Protocol.HTTP_2));
    }
    assertEquals(expectedProtocol, response.protocol());
    try {
      response.body.string();
      fail();
    } catch (IOException expected) {
    }

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    assertNotNull(callFailed.ioe);
  }

  @Ignore("the CallEnd event is omitted")
  @Test public void emptyResponseBody() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("")
        .setBodyDelay(1, TimeUnit.SECONDS)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  @Ignore("this reports CallFailed not CallEnd")
  @Test public void responseBodyClosedClosedWithoutReadingAllData() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .setBodyDelay(1, TimeUnit.SECONDS)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  @Test public void requestBodyFailHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    requestBodyFail();
  }

  @Test public void requestBodyFailHttp2OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    requestBodyFail();
  }

  @Test public void requestBodyFailHttp() throws IOException {
    requestBodyFail();
  }

  private void requestBodyFail() {
    // Stream a 8 MiB body so the disconnect will happen before the server has read everything.
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.parse("text/plain");
      }

      @Override public long contentLength() {
        return 1024 * 8192;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        for (int i = 0; i < 1024; i++) {
          sink.write(new byte[8192]);
          sink.flush();
        }
      }
    };

    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(requestBody)
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    assertNotNull(callFailed.ioe);
  }

  @Test public void requestBodySuccessHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_1_1));
    requestBodySuccess(RequestBody.create(MediaType.parse("text/plain"), "Hello"), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessHttp2OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    requestBodySuccess(RequestBody.create(MediaType.parse("text/plain"), "Hello"), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessHttp() throws IOException {
    requestBodySuccess(RequestBody.create(MediaType.parse("text/plain"), "Hello"), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessStreaming() throws IOException {
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.parse("text/plain");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.write(new byte[8192]);
        sink.flush();
      }
    };

    requestBodySuccess(requestBody, equalTo(8192L), equalTo(19L));
  }

  @Test public void requestBodySuccessEmpty() throws IOException {
    requestBodySuccess(RequestBody.create(MediaType.parse("text/plain"), ""), equalTo(0L),
        equalTo(19L));
  }

  @Test public void successfulCallEventSequenceWithListener() throws IOException {
    server.enqueue(new MockResponse().setBody("abc"));

    client = client.newBuilder().addNetworkInterceptor(new HttpLoggingInterceptor().setLevel(
        HttpLoggingInterceptor.Level.BODY)).build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    assertEquals("abc", response.body().string());
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  private void requestBodySuccess(RequestBody body, Matcher<Long> requestBodyBytes,
      Matcher<Long> responseHeaderLength) throws IOException {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("World!"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(body)
        .build());
    Response response = call.execute();
    assertEquals("World!", response.body().string());

    assertBytesReadWritten(listener, any(Long.class), requestBodyBytes, responseHeaderLength,
        equalTo(6L));
  }

  private void enableTlsWithTunnel(boolean tunnelProxy) {
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(sslClient.socketFactory, tunnelProxy);
  }
}
