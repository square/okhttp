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
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import okhttp3.internal.SingleInetAddressDns;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class EventListenerTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private OkHttpClient client;
  private final RecordingEventListener listener = new RecordingEventListener();
  private final SslClient sslClient = SslClient.localhost();

  @Before public void setUp() {
    client = new OkHttpClient.Builder()
        .dns(new SingleInetAddressDns())
        .eventListener(listener)
        .build();
  }

  @Test public void successfulCallEventSequence() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    List<Class<?>> expectedEvents = Arrays.asList(DnsStart.class, DnsEnd.class);
    assertEquals(expectedEvents, listener.recordedEventTypes());
  }

  @Test public void successfulHttpsCallEventSequence() throws IOException {
    enableTls(false);
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    List<Class<?>> expectedEvents = Arrays.asList(
        DnsStart.class, DnsEnd.class,
        SecureConnectStart.class, SecureConnectEnd.class);
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

    DnsStart dnsStart = listener.findNextEvent(DnsStart.class);
    assertSame(call, dnsStart.call);
    assertEquals("localhost", dnsStart.domainName);

    DnsEnd dnsEnd = listener.findNextEvent(DnsEnd.class);
    assertSame(call, dnsEnd.call);
    assertEquals("localhost", dnsEnd.domainName);
    assertEquals(1, dnsEnd.inetAddressList.size());
    assertNull(dnsEnd.throwable);
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

    listener.findNextEvent(DnsStart.class);

    DnsEnd dnsEnd = listener.findNextEvent(DnsEnd.class);
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

    listener.findNextEvent(DnsStart.class);

    DnsEnd dnsEnd = listener.findNextEvent(DnsEnd.class);
    assertSame(call, dnsEnd.call);
    assertEquals("fakeurl", dnsEnd.domainName);
    assertNull(dnsEnd.inetAddressList);
    assertTrue(dnsEnd.throwable instanceof UnknownHostException);
  }

  @Test public void successfulSecureConnect() throws IOException {
    enableTls(false);
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    SecureConnectStart secureStart = listener.findNextEvent(SecureConnectStart.class);
    assertSame(call, secureStart.call);

    SecureConnectEnd secureEnd = listener.findNextEvent(SecureConnectEnd.class);
    assertSame(call, secureEnd.call);
    assertNotNull(secureEnd.handshake);
    assertNull(secureEnd.throwable);
  }

  @Test public void failedSecureConnect() {
    enableTls(false);
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

    SecureConnectStart secureStart = listener.findNextEvent(SecureConnectStart.class);
    assertSame(call, secureStart.call);

    SecureConnectEnd secureEnd = listener.findNextEvent(SecureConnectEnd.class);
    assertSame(call, secureEnd.call);
    assertNull(secureEnd.handshake);
    assertTrue(secureEnd.throwable instanceof IOException);
  }

  @Test public void secureConnectWithTunnel() throws IOException {
    enableTls(true);
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

    SecureConnectStart secureStart = listener.findNextEvent(SecureConnectStart.class);
    assertSame(call, secureStart.call);

    SecureConnectEnd secureEnd = listener.findNextEvent(SecureConnectEnd.class);
    assertSame(call, secureEnd.call);
    assertNotNull(secureEnd.handshake);
    assertNull(secureEnd.throwable);
  }

  private void enableTls(boolean tunnelProxy) {
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(sslClient.socketFactory, tunnelProxy);
  }

  static final class DnsStart {
    final Call call;
    final String domainName;

    DnsStart(Call call, String domainName) {
      this.call = call;
      this.domainName = domainName;
    }
  }

  static final class DnsEnd {
    final Call call;
    final String domainName;
    final List<InetAddress> inetAddressList;
    final Throwable throwable;

    DnsEnd(Call call, String domainName, List<InetAddress> inetAddressList, Throwable throwable) {
      this.call = call;
      this.domainName = domainName;
      this.inetAddressList = inetAddressList;
      this.throwable = throwable;
    }
  }

  static final class SecureConnectStart {
    final Call call;

    SecureConnectStart(Call call) {
      this.call = call;
    }
  }

  static final class SecureConnectEnd {
    final Call call;
    final Handshake handshake;
    final Throwable throwable;

    SecureConnectEnd(Call call, Handshake handshake, Throwable throwable) {
      this.call = call;
      this.handshake = handshake;
      this.throwable = throwable;
    }
  }

  static final class RecordingEventListener extends EventListener {
    final Deque<Object> eventSequence = new ArrayDeque<>();

    <T> T findNextEvent(Class<T> eventClass) {
      Object event = eventSequence.poll();
      while (event != null && !eventClass.isInstance(event)) {
        event = eventSequence.poll();
      }
      if (event == null) {
        fail("Expected event type: " + eventClass.getName());
      }
      return (T) event;
    }

    List<Class<?>> recordedEventTypes() {
      List<Class<?>> eventTypes = new ArrayList<>();
      for (Object event : eventSequence) {
        eventTypes.add(event.getClass());
      }
      return eventTypes;
    }

    @Override public void dnsStart(Call call, String domainName) {
      eventSequence.offer(new DnsStart(call, domainName));
    }

    @Override public void dnsEnd(Call call, String domainName, List<InetAddress> inetAddressList,
        Throwable throwable) {
      eventSequence.offer(new DnsEnd(call, domainName, inetAddressList, throwable));
    }

    @Override public void secureConnectStart(Call call) {
      eventSequence.offer(new SecureConnectStart(call));
    }

    @Override public void secureConnectEnd(Call call, Handshake handshake, Throwable throwable) {
      eventSequence.offer(new SecureConnectEnd(call, handshake, throwable));
    }
  }
}
