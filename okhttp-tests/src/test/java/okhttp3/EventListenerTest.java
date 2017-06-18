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
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import okhttp3.internal.SingleInetAddressDns;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class EventListenerTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private OkHttpClient client;
  private final RecordingEventListener listener = new RecordingEventListener();

  @Before public void setUp() {
    client = new OkHttpClient.Builder()
        .dns(new SingleInetAddressDns())
        .eventListener(listener)
        .build();
  }

  @Test public void successfulDnsLookup() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals(200, response.code());
    response.body().close();

    DnsStart dnsStart = listener.expectNextEvent(DnsStart.class);
    assertSame(call, dnsStart.call);
    assertEquals("localhost", dnsStart.domainName);

    DnsEnd dnsEnd = listener.expectNextEvent(DnsEnd.class);
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

    listener.expectNextEvent(DnsStart.class);

    DnsEnd dnsEnd = listener.expectNextEvent(DnsEnd.class);
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

    listener.expectNextEvent(DnsStart.class);

    DnsEnd dnsEnd = listener.expectNextEvent(DnsEnd.class);
    assertSame(call, dnsEnd.call);
    assertEquals("fakeurl", dnsEnd.domainName);
    assertNull(dnsEnd.inetAddressList);
    assertTrue(dnsEnd.throwable instanceof UnknownHostException);
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

  static final class RecordingEventListener extends EventListener {
    final Deque<Object> eventSequence = new ArrayDeque<>();

    <T> T expectNextEvent(Class<T> eventClass) {
      Object event = eventSequence.poll();
      if (!eventClass.isInstance(event)) {
        fail("Expected event type: " + eventClass.getName());
      }
      return (T) event;
    }

    @Override public void dnsStart(Call call, String domainName) {
      eventSequence.offer(new DnsStart(call, domainName));
    }

    @Override public void dnsEnd(Call call, String domainName, List<InetAddress> inetAddressList,
        Throwable throwable) {
      eventSequence.offer(new DnsEnd(call, domainName, inetAddressList, throwable));
    }
  }
}
