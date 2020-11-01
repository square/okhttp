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

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.SocketPolicy;
import okhttp3.CallEvent.CallEnd;
import okhttp3.CallEvent.CallFailed;
import okhttp3.CallEvent.CallStart;
import okhttp3.CallEvent.ConnectEnd;
import okhttp3.CallEvent.ConnectFailed;
import okhttp3.CallEvent.ConnectStart;
import okhttp3.CallEvent.ConnectionAcquired;
import okhttp3.CallEvent.ConnectionReleased;
import okhttp3.CallEvent.DnsEnd;
import okhttp3.CallEvent.DnsStart;
import okhttp3.CallEvent.RequestBodyEnd;
import okhttp3.CallEvent.RequestBodyStart;
import okhttp3.CallEvent.RequestHeadersEnd;
import okhttp3.CallEvent.RequestHeadersStart;
import okhttp3.CallEvent.ResponseBodyEnd;
import okhttp3.CallEvent.ResponseBodyStart;
import okhttp3.CallEvent.ResponseFailed;
import okhttp3.CallEvent.ResponseHeadersEnd;
import okhttp3.CallEvent.ResponseHeadersStart;
import okhttp3.CallEvent.SecureConnectEnd;
import okhttp3.CallEvent.SecureConnectStart;
import okhttp3.internal.DoubleInetAddressDns;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.connection.RealConnectionPool;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.testing.Flaky;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.BufferedSink;
import org.hamcrest.BaseMatcher;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import static java.util.Arrays.asList;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.any;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

@Flaky // STDOUT logging enabled for test
@Timeout(30)
public final class EventListenerTest {
  public static final Matcher<Response> anyResponse = CoreMatchers.any(Response.class);

  @RegisterExtension public final PlatformRule platform = new PlatformRule();
  @RegisterExtension public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private MockWebServer server;
  private final RecordingEventListener listener = new RecordingEventListener();
  private final HandshakeCertificates handshakeCertificates = localhost();

  private OkHttpClient client = clientTestRule.newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build();
  private SocksProxy socksProxy;
  private Cache cache = null;

  @BeforeEach public void setUp(MockWebServer server) {
    this.server = server;

    platform.assumeNotOpenJSSE();
    platform.assumeNotBouncyCastle();

    listener.forbidLock(RealConnectionPool.Companion.get(client.connectionPool()));
    listener.forbidLock(client.dispatcher());
  }

  @AfterEach public void tearDown() throws Exception {
    if (socksProxy != null) {
      socksProxy.shutdown();
    }
    if (cache != null) {
      cache.delete();
    }
  }

  @Test public void successfulCallEventSequence() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("abc");
    response.body().close();

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
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

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
  }

  @Test public void failedCallEventSequence() {
    server.enqueue(new MockResponse()
        .setHeadersDelay(2, TimeUnit.SECONDS));

    client = client.newBuilder()
        .readTimeout(Duration.ofMillis(250))
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isIn("timeout", "Read timed out");
    }

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseFailed", "ConnectionReleased", "CallFailed");
  }

  @Test public void failedDribbledCallEventSequence() throws IOException {
    server.enqueue(new MockResponse().setBody("0123456789")
        .throttleBody(2, 100, TimeUnit.MILLISECONDS)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    client = client.newBuilder()
        .protocols(Collections.singletonList(Protocol.HTTP_1_1))
        .readTimeout(Duration.ofMillis(250))
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    Response response = call.execute();
    try {
      response.body().string();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("unexpected end of stream");
    }

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseFailed", "ConnectionReleased", "CallFailed");
    ResponseFailed responseFailed = listener.removeUpToEvent(ResponseFailed.class);
    assertThat(responseFailed.getIoe().getMessage()).isEqualTo("unexpected end of stream");
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
      assertThat(expected.getMessage()).isEqualTo("Canceled");
    }

    assertThat(listener.recordedEventTypes()).containsExactly(
        "Canceled", "CallStart", "CallFailed");
  }

  @Test public void cancelAsyncCall() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    call.enqueue(new Callback() {
      @Override public void onFailure(Call call, IOException e) {
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        response.close();
      }
    });
    call.cancel();

    assertThat(listener.recordedEventTypes()).contains("Canceled");
  }

  @Test public void multipleCancelsEmitsOnlyOneEvent() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    call.cancel();
    call.cancel();

    assertThat(listener.recordedEventTypes()).containsExactly("Canceled");
  }

  private void assertSuccessfulEventOrder(Matcher<Response> responseMatcher) throws IOException {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    response.body().string();
    response.body().close();

    assumeThat(response, responseMatcher);

    assertThat(listener.recordedEventTypes()).containsExactly(
        "CallStart", "ProxySelectStart", "ProxySelectEnd",
        "DnsStart", "DnsEnd", "ConnectStart",
        "SecureConnectStart", "SecureConnectEnd", "ConnectEnd", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
  }

  @Test public void secondCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
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

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
  }

  private void assertBytesReadWritten(RecordingEventListener listener,
      @Nullable Matcher<Long> requestHeaderLength, @Nullable Matcher<Long> requestBodyBytes,
      @Nullable Matcher<Long> responseHeaderLength, @Nullable Matcher<Long> responseBodyBytes) {

    if (requestHeaderLength != null) {
      RequestHeadersEnd responseHeadersEnd = listener.removeUpToEvent(RequestHeadersEnd.class);
      Assert.assertThat("request header length", responseHeadersEnd.getHeaderLength(),
          requestHeaderLength);
    } else {
      assertThat(listener.recordedEventTypes()).doesNotContain("RequestHeadersEnd");
    }

    if (requestBodyBytes != null) {
      RequestBodyEnd responseBodyEnd = listener.removeUpToEvent(RequestBodyEnd.class);
      Assert.assertThat("request body bytes", responseBodyEnd.getBytesWritten(), requestBodyBytes);
    } else {
      assertThat(listener.recordedEventTypes()).doesNotContain("RequestBodyEnd");
    }

    if (responseHeaderLength != null) {
      ResponseHeadersEnd responseHeadersEnd = listener.removeUpToEvent(ResponseHeadersEnd.class);
      Assert.assertThat("response header length", responseHeadersEnd.getHeaderLength(),
          responseHeaderLength);
    } else {
      assertThat(listener.recordedEventTypes()).doesNotContain("ResponseHeadersEnd");
    }

    if (responseBodyBytes != null) {
      ResponseBodyEnd responseBodyEnd = listener.removeUpToEvent(ResponseBodyEnd.class);
      Assert.assertThat("response body bytes", responseBodyEnd.getBytesRead(), responseBodyBytes);
    } else {
      assertThat(listener.recordedEventTypes()).doesNotContain("ResponseBodyEnd");
    }
  }

  private Matcher<Long> greaterThan(final long value) {
    return new BaseMatcher<Long>() {
      @Override public void describeTo(Description description) {
        description.appendText("> " + value);
      }

      @Override public boolean matches(Object o) {
        return ((Long) o) > value;
      }
    };
  }

  private Matcher<Response> matchesProtocol(final Protocol protocol) {
    return new BaseMatcher<Response>() {
      @Override public void describeTo(Description description) {
        description.appendText("is HTTP/2");
      }

      @Override public boolean matches(Object o) {
        return ((Response) o).protocol() == protocol;
      }
    };
  }

  @Test public void successfulEmptyH2CallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    server.enqueue(new MockResponse());

    assertSuccessfulEventOrder(matchesProtocol(Protocol.HTTP_2));

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(0L));
  }

  @Test public void successfulEmptyHttpsCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_1_1));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    assertSuccessfulEventOrder(anyResponse);

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(3L));
  }

  @Test public void successfulChunkedHttpsCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_1_1));
    server.enqueue(
        new MockResponse().setBodyDelay(100, TimeUnit.MILLISECONDS).setChunkedBody("Hello!", 2));

    assertSuccessfulEventOrder(anyResponse);

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(6L));
  }

  @Test public void successfulChunkedH2CallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
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
    assertThat(response.code()).isEqualTo(200);
    response.body().close();

    DnsStart dnsStart = listener.removeUpToEvent(DnsStart.class);
    assertThat(dnsStart.getCall()).isSameAs(call);
    assertThat(dnsStart.getDomainName()).isEqualTo(server.getHostName());

    DnsEnd dnsEnd = listener.removeUpToEvent(DnsEnd.class);
    assertThat(dnsEnd.getCall()).isSameAs(call);
    assertThat(dnsEnd.getDomainName()).isEqualTo(server.getHostName());
    assertThat(dnsEnd.getInetAddressList().size()).isEqualTo(1);
  }

  @Test public void noDnsLookupOnPooledConnection() throws IOException {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertThat(response1.code()).isEqualTo(200);
    response1.body().close();

    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.code()).isEqualTo(200);
    response2.body().close();

    List<String> recordedEvents = listener.recordedEventTypes();
    assertThat(recordedEvents).doesNotContain("DnsStart");
    assertThat(recordedEvents).doesNotContain("DnsEnd");
  }

  @Test public void multipleDnsLookupsForSingleCall() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .setHeader("Location", "http://www.fakeurl:" + server.getPort()));
    server.enqueue(new MockResponse());

    FakeDns dns = new FakeDns();
    dns.set("fakeurl", client.dns().lookup(server.getHostName()));
    dns.set("www.fakeurl", client.dns().lookup(server.getHostName()));

    client = client.newBuilder()
        .dns(dns)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url("http://fakeurl:" + server.getPort())
        .build());
    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
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
    assertThat(callFailed.getCall()).isSameAs(call);
    assertThat(callFailed.getIoe()).isInstanceOf(UnknownHostException.class);
  }

  @Test public void emptyDnsLookup() {
    Dns emptyDns = hostname -> Collections.emptyList();

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
    assertThat(callFailed.getCall()).isSameAs(call);
    assertThat(callFailed.getIoe()).isInstanceOf(UnknownHostException.class);
  }

  @Test public void successfulConnect() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    response.body().close();

    InetAddress address = client.dns().lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    assertThat(connectStart.getCall()).isSameAs(call);
    assertThat(connectStart.getInetSocketAddress()).isEqualTo(expectedAddress);
    assertThat(connectStart.getProxy()).isEqualTo(Proxy.NO_PROXY);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    assertThat(connectEnd.getCall()).isSameAs(call);
    assertThat(connectEnd.getInetSocketAddress()).isEqualTo(expectedAddress);
    assertThat(connectEnd.getProtocol()).isEqualTo(Protocol.HTTP_1_1);
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

    InetAddress address = client.dns().lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    assertThat(connectStart.getCall()).isSameAs(call);
    assertThat(connectStart.getInetSocketAddress()).isEqualTo(expectedAddress);
    assertThat(connectStart.getProxy()).isEqualTo(Proxy.NO_PROXY);

    ConnectFailed connectFailed = listener.removeUpToEvent(ConnectFailed.class);
    assertThat(connectFailed.getCall()).isSameAs(call);
    assertThat(connectFailed.getInetSocketAddress()).isEqualTo(expectedAddress);
    assertThat(connectFailed.getProtocol()).isNull();
    assertThat(connectFailed.getIoe()).isNotNull();
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
    assertThat(response.code()).isEqualTo(200);
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
    assertThat(response.code()).isEqualTo(200);
    response.body().close();

    InetAddress address = client.dns().lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    assertThat(connectStart.getCall()).isSameAs(call);
    assertThat(connectStart.getInetSocketAddress()).isEqualTo(expectedAddress);
    assertThat(connectStart.getProxy()).isEqualTo(server.toProxyAddress());

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    assertThat(connectEnd.getCall()).isSameAs(call);
    assertThat(connectEnd.getInetSocketAddress()).isEqualTo(expectedAddress);
    assertThat(connectEnd.getProtocol()).isEqualTo(Protocol.HTTP_1_1);
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
    assertThat(response.code()).isEqualTo(200);
    response.body().close();

    InetSocketAddress expectedAddress = InetSocketAddress.createUnresolved(
        SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    assertThat(connectStart.getCall()).isSameAs(call);
    assertThat(connectStart.getInetSocketAddress()).isEqualTo(expectedAddress);
    assertThat(connectStart.getProxy()).isEqualTo(proxy);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    assertThat(connectEnd.getCall()).isSameAs(call);
    assertThat(connectEnd.getInetSocketAddress()).isEqualTo(expectedAddress);
    assertThat(connectEnd.getProtocol()).isEqualTo(Protocol.HTTP_1_1);
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
        .proxyAuthenticator(new RecordingOkAuthenticator("password", "Basic"))
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    response.body().close();

    listener.removeUpToEvent(ConnectStart.class);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    assertThat(connectEnd.getProtocol()).isNull();

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
    assertThat(response.code()).isEqualTo(200);
    response.body().close();

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    assertThat(secureStart.getCall()).isSameAs(call);

    SecureConnectEnd secureEnd = listener.removeUpToEvent(SecureConnectEnd.class);
    assertThat(secureEnd.getCall()).isSameAs(call);
    assertThat(secureEnd.getHandshake()).isNotNull();
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
    assertThat(secureStart.getCall()).isSameAs(call);

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    assertThat(callFailed.getCall()).isSameAs(call);
    assertThat(callFailed.getIoe()).isNotNull();
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
    assertThat(response.code()).isEqualTo(200);
    response.body().close();

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    assertThat(secureStart.getCall()).isSameAs(call);

    SecureConnectEnd secureEnd = listener.removeUpToEvent(SecureConnectEnd.class);
    assertThat(secureEnd.getCall()).isSameAs(call);
    assertThat(secureEnd.getHandshake()).isNotNull();
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
    assertThat(response.code()).isEqualTo(200);
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
    assertThat(response1.code()).isEqualTo(200);
    response1.body().close();

    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.code()).isEqualTo(200);
    response2.body().close();

    List<String> recordedEvents = listener.recordedEventTypes();
    assertThat(recordedEvents).doesNotContain("SecureConnectStart");
    assertThat(recordedEvents).doesNotContain("SecureConnectEnd");
  }

  @Test public void successfulConnectionFound() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    response.body().close();

    ConnectionAcquired connectionAcquired = listener.removeUpToEvent(ConnectionAcquired.class);
    assertThat(connectionAcquired.getCall()).isSameAs(call);
    assertThat(connectionAcquired.getConnection()).isNotNull();
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
    assertThat(response.body().string()).isEqualTo("ABC");

    listener.removeUpToEvent(ConnectionAcquired.class);

    List<String> remainingEvents = listener.recordedEventTypes();
    assertThat(remainingEvents).doesNotContain("ConnectionAcquired");
  }

  @Test public void pooledConnectionFound() throws IOException {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertThat(response1.code()).isEqualTo(200);
    response1.body().close();

    ConnectionAcquired connectionAcquired1 = listener.removeUpToEvent(ConnectionAcquired.class);
    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.code()).isEqualTo(200);
    response2.body().close();

    ConnectionAcquired connectionAcquired2 = listener.removeUpToEvent(ConnectionAcquired.class);
    assertThat(connectionAcquired2.getConnection()).isSameAs(
        connectionAcquired1.getConnection());
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
    assertThat(response.body().string()).isEqualTo("ABC");

    listener.removeUpToEvent(ConnectionAcquired.class);
    listener.removeUpToEvent(ConnectionAcquired.class);
  }

  @Test public void responseBodyFailHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_1_1));
    responseBodyFail(Protocol.HTTP_1_1);
  }

  @Test public void responseBodyFailHttp2OverHttps() throws IOException {
    platform.assumeHttp2Support();

    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
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
    assertThat(response.protocol()).isEqualTo(expectedProtocol);
    try {
      response.body().string();
      fail();
    } catch (IOException expected) {
    }

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    assertThat(callFailed.getIoe()).isNotNull();
  }

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

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
  }

  @Test public void emptyResponseBodyConnectionClose() throws IOException {
    server.enqueue(new MockResponse()
        .addHeader("Connection", "close")
        .setBody(""));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    response.body().close();

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
  }

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

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
  }

  @Test public void requestBodyFailHttp1OverHttps() {
    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_1_1));

    requestBodyFail(Protocol.HTTP_1_1);
  }

  @Test public void requestBodyFailHttp2OverHttps() {
    platform.assumeHttp2Support();

    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_2, Protocol.HTTP_1_1));

    requestBodyFail(Protocol.HTTP_2);
  }

  @Test public void requestBodyFailHttp() {
    requestBodyFail(null);
  }

  private void requestBodyFail(@Nullable Protocol expectedProtocol) {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY));

    NonCompletingRequestBody request = new NonCompletingRequestBody();
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(request)
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    if (expectedProtocol != null) {
      ConnectionAcquired connectionAcquired = listener.removeUpToEvent(ConnectionAcquired.class);
      assertThat(connectionAcquired.getConnection().protocol()).isEqualTo(expectedProtocol);
    }

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    assertThat(callFailed.getIoe()).isNotNull();

    assertThat(request.ioe).isNotNull();
  }

  private class NonCompletingRequestBody extends RequestBody {
    IOException ioe;

    @Override public MediaType contentType() {
      return MediaType.get("text/plain");
    }

    @Override public long contentLength() {
      return 1024 * 1024 * 4;
    }

    @Override public void writeTo(BufferedSink sink) throws IOException {
      try {
        writeChunk(sink);
        writeChunk(sink);
        writeChunk(sink);
        writeChunk(sink);
        Thread.sleep(1000);
        writeChunk(sink);
        writeChunk(sink);
      } catch (IOException e) {
        ioe = e;
      } catch (InterruptedException e) {
      }
    }

    private void writeChunk(BufferedSink sink) throws IOException {
      sink.write(new byte[1024 * 512]);
      sink.flush();
    }
  }

  @Test public void requestBodyMultipleFailuresReportedOnlyOnce() {
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public long contentLength() {
        return 1024 * 1024 * 256;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        int failureCount = 0;
        for (int i = 0; i < 1024; i++) {
          try {
            sink.write(new byte[1024 * 256]);
            sink.flush();
          } catch (IOException e) {
            failureCount++;
            if (failureCount == 3) throw e;
          }
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

    assertThat(listener.recordedEventTypes()).containsExactly(
        "CallStart", "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd", "ConnectStart",
        "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd",
        "RequestBodyStart", "RequestFailed", "ResponseFailed", "ConnectionReleased", "CallFailed");
  }

  @Test public void requestBodySuccessHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_1_1));
    requestBodySuccess(RequestBody.create("Hello", MediaType.get("text/plain")), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessHttp2OverHttps() throws IOException {
    platform.assumeHttp2Support();

    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    requestBodySuccess(RequestBody.create("Hello", MediaType.get("text/plain")), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessHttp() throws IOException {
    requestBodySuccess(RequestBody.create("Hello", MediaType.get("text/plain")), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessStreaming() throws IOException {
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.write(new byte[8192]);
        sink.flush();
      }
    };

    requestBodySuccess(requestBody, equalTo(8192L), equalTo(19L));
  }

  @Test public void requestBodySuccessEmpty() throws IOException {
    requestBodySuccess(RequestBody.create("", MediaType.get("text/plain")), equalTo(0L),
        equalTo(19L));
  }

  @Test public void successfulCallEventSequenceWithListener() throws IOException {
    server.enqueue(new MockResponse().setBody("abc"));

    client = client.newBuilder()
        .addNetworkInterceptor(new HttpLoggingInterceptor()
            .setLevel(HttpLoggingInterceptor.Level.BODY))
        .build();
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("abc");
    response.body().close();

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
  }

  private void requestBodySuccess(RequestBody body, Matcher<Long> requestBodyBytes,
      Matcher<Long> responseHeaderLength) throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setBody("World!"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(body)
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("World!");

    assertBytesReadWritten(listener, any(Long.class), requestBodyBytes, responseHeaderLength,
        equalTo(6L));
  }

  @Test public void timeToFirstByteHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_1_1));

    timeToFirstByte();
  }

  @Test public void timeToFirstByteHttp2OverHttps() throws IOException {
    platform.assumeHttp2Support();
    enableTlsWithTunnel(false);
    server.setProtocols(asList(Protocol.HTTP_2, Protocol.HTTP_1_1));

    timeToFirstByte();
  }

  /**
   * Test to confirm that events are reported at the time they occur and no earlier and no later.
   * This inserts a bunch of synthetic 250 ms delays into both client and server and confirms that
   * the same delays make it back into the events.
   *
   * We've had bugs where we report an event when we request data rather than when the data actually
   * arrives. https://github.com/square/okhttp/issues/5578
   */
  private void timeToFirstByte() throws IOException {
    long applicationInterceptorDelay = 250L;
    long networkInterceptorDelay = 250L;
    long requestBodyDelay = 250L;
    long responseHeadersStartDelay = 250L;
    long responseBodyStartDelay = 250L;
    long responseBodyEndDelay = 250L;

    // Warm up the client so the timing part of the test gets a pooled connection.
    server.enqueue(new MockResponse());
    Call warmUpCall = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try (Response warmUpResponse = warmUpCall.execute()) {
      warmUpResponse.body().string();
    }
    listener.clearAllEvents();

    // Create a client with artificial delays.
    client = client.newBuilder()
        .addInterceptor(chain -> {
          try {
            Thread.sleep(applicationInterceptorDelay);
            return chain.proceed(chain.request());
          } catch (InterruptedException e) {
            throw new InterruptedIOException();
          }
        })
        .addNetworkInterceptor(chain -> {
          try {
            Thread.sleep(networkInterceptorDelay);
            return chain.proceed(chain.request());
          } catch (InterruptedException e) {
            throw new InterruptedIOException();
          }
        })
        .build();

    // Create a request body with artificial delays.
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new RequestBody() {
          @Override public @Nullable MediaType contentType() {
            return null;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            try {
              Thread.sleep(requestBodyDelay);
              sink.writeUtf8("abc");
            } catch (InterruptedException e) {
              throw new InterruptedIOException();
            }
          }
        })
        .build());

    // Create a response with artificial delays.
    server.enqueue(new MockResponse()
        .setHeadersDelay(responseHeadersStartDelay, TimeUnit.MILLISECONDS)
        .setBodyDelay(responseBodyStartDelay, TimeUnit.MILLISECONDS)
        .throttleBody(5, responseBodyEndDelay, TimeUnit.MILLISECONDS)
        .setBody("fghijk"));

    // Make the call.
    try (Response response = call.execute()) {
      assertThat(response.body().string()).isEqualTo("fghijk");
    }

    // Confirm the events occur when expected.
    listener.takeEvent(CallStart.class, 0L);
    listener.takeEvent(ConnectionAcquired.class, applicationInterceptorDelay);
    listener.takeEvent(RequestHeadersStart.class, networkInterceptorDelay);
    listener.takeEvent(RequestHeadersEnd.class, 0L);
    listener.takeEvent(RequestBodyStart.class, 0L);
    listener.takeEvent(RequestBodyEnd.class, requestBodyDelay);
    listener.takeEvent(ResponseHeadersStart.class, responseHeadersStartDelay);
    listener.takeEvent(ResponseHeadersEnd.class, 0L);
    listener.takeEvent(ResponseBodyStart.class, responseBodyStartDelay);
    listener.takeEvent(ResponseBodyEnd.class, responseBodyEndDelay);
    listener.takeEvent(ConnectionReleased.class, 0L);
    listener.takeEvent(CallEnd.class, 0L);
  }

  private void enableTlsWithTunnel(boolean tunnelProxy) {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), tunnelProxy);
  }

  @Test public void redirectUsingSameConnectionEventSequence() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    call.execute();

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
        "CallEnd");
  }

  @Test
  public void redirectUsingNewConnectionEventSequence() throws IOException {
    MockWebServer otherServer = new MockWebServer();
    server.enqueue(
        new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
            .addHeader("Location: " + otherServer.url("/foo")));
    otherServer.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    call.execute();

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "ProxySelectStart", "ProxySelectEnd",
        "DnsStart", "DnsEnd", "ConnectStart", "ConnectEnd",
        "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
        "CallEnd");
  }

  @Test public void applicationInterceptorProceedsMultipleTimes() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    client = client.newBuilder()
        .addInterceptor(chain -> {
          try (Response a = chain.proceed(chain.request())) {
            assertThat(a.body().string()).isEqualTo("a");
          }
          return chain.proceed(chain.request());
        })
        .build();

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("b");

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
        "CallEnd");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test public void applicationInterceptorShortCircuit() throws Exception {
    client = client.newBuilder()
        .addInterceptor(chain -> new Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ResponseBody.create("a", null))
            .build())
        .build();

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("a");

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "CallEnd");
  }

  /** Response headers start, then the entire request body, then response headers end. */
  @Test public void expectContinueStartsResponseHeadersEarly() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.EXPECT_CONTINUE));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create("abc", MediaType.get("text/plain")))
        .build();

    Call call = client.newCall(request);
    call.execute();

    assertThat(listener.recordedEventTypes()).containsExactly(
        "CallStart", "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd", "ConnectStart",
        "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd",
        "ResponseHeadersStart", "RequestBodyStart", "RequestBodyEnd", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
  }

  @Test public void timeToFirstByteGapBetweenResponseHeaderStartAndEnd() throws IOException {
    long responseHeadersStartDelay = 250L;
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.EXPECT_CONTINUE)
        .setHeadersDelay(responseHeadersStartDelay, TimeUnit.MILLISECONDS));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create("abc", MediaType.get("text/plain")))
        .build();

    Call call = client.newCall(request);
    try (Response response = call.execute()) {
      assertThat(response.body().string()).isEqualTo("");
    }

    listener.removeUpToEvent(ResponseHeadersStart.class);
    listener.takeEvent(RequestBodyStart.class, 0L);
    listener.takeEvent(RequestBodyEnd.class, 0L);
    listener.takeEvent(ResponseHeadersEnd.class, responseHeadersStartDelay);
  }

  @Test public void cacheMiss() throws IOException {
    enableCache();

    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("abc");
    response.close();

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "CacheMiss",
        "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
  }

  @Test public void conditionalCache() throws IOException {
    enableCache();

    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .setBody("abc"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    response.close();

    listener.clearAllEvents();

    call = call.clone();

    response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("abc");
    response.close();

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "CacheConditionalHit",
        "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "CacheHit", "ConnectionReleased", "CallEnd");
  }

  @Test public void conditionalCacheMiss() throws IOException {
    enableCache();

    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .setBody("abc"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_OK)
        .addHeader("ETag: v2")
        .setBody("abd"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    response.close();

    listener.clearAllEvents();

    call = call.clone();

    response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("abd");
    response.close();

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "CacheConditionalHit",
        "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "CacheMiss",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
  }

  @Test public void satisfactionFailure() throws IOException {
    enableCache();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .cacheControl(CacheControl.FORCE_CACHE)
        .build());
    Response response = call.execute();
    assertThat(response.code()).isEqualTo(504);
    response.close();

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "SatisfactionFailure", "CallEnd");
  }

  @Test public void cacheHit() throws IOException {
    enableCache();

    server.enqueue(new MockResponse().setBody("abc").addHeader("cache-control: public, max-age=300"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("abc");
    response.close();

    listener.clearAllEvents();

    call = call.clone();
    response = call.execute();
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("abc");
    response.close();

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "CacheHit", "CallEnd");
  }

  private Cache enableCache() throws IOException {
    cache = makeCache();
    client = client.newBuilder().cache(cache).build();
    return cache;
  }

  private Cache makeCache() throws IOException {
    File cacheDir = File.createTempFile("cache-", ".dir");
    cacheDir.delete();
    return new Cache(cacheDir, 1024 * 1024);
  }
}
