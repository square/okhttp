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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.annotation.Nullable;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;

public class RecordingEventListener extends EventListener {
  final Deque<CallEvent> eventSequence = new ConcurrentLinkedDeque<>();

  final List<Object> forbiddenLocks = new ArrayList<>();

  /** Confirm that the thread does not hold a lock on {@code lock} during the callback. */
  public void forbidLock(Object lock) {
    forbiddenLocks.add(lock);
  }

  /**
   * Removes recorded events up to (and including) an event is found whose class equals
   * {@code eventClass} and returns it.
   */
  public <T> T removeUpToEvent(Class<T> eventClass) {
    List<CallEvent> fullEventSequence = new ArrayList<>(eventSequence);
    Object event = eventSequence.poll();
    while (event != null && !eventClass.isInstance(event)) {
      event = eventSequence.poll();
    }
    if (event == null) {
      throw new AssertionError(
          eventClass.getSimpleName() + " not found. Found " + fullEventSequence + ".");
    }
    return eventClass.cast(event);
  }

  public List<String> recordedEventTypes() {
    List<String> eventTypes = new ArrayList<>();
    for (CallEvent event : eventSequence) {
      eventTypes.add(event.getName());
    }
    return eventTypes;
  }

  public void clearAllEvents() {
    eventSequence.clear();
  }

  private void logEvent(CallEvent e) {
    for (Object lock : forbiddenLocks) {
      assertThat(Thread.holdsLock(lock)).overridingErrorMessage(lock.toString()).isFalse();
    }

    CallEvent startEvent = e.closes();

    if (startEvent != null) {
      assertTrue(eventSequence.contains(startEvent));
    }

    eventSequence.offer(e);
  }

  @Override public void proxySelectStart(Call call, HttpUrl url) {
    logEvent(new ProxySelectStart(call, url));
  }

  @Override public void proxySelectEnd(Call call, HttpUrl url,
      List<Proxy> proxies) {
    logEvent(new ProxySelectEnd(call, url, proxies));
  }

  @Override public void dnsStart(Call call, String domainName) {
    logEvent(new DnsStart(call, domainName));
  }

  @Override public void dnsEnd(Call call, String domainName, List<InetAddress> inetAddressList) {
    logEvent(new DnsEnd(call, domainName, inetAddressList));
  }

  @Override public void connectStart(Call call, InetSocketAddress inetSocketAddress,
      Proxy proxy) {
    logEvent(new ConnectStart(call, inetSocketAddress, proxy));
  }

  @Override public void secureConnectStart(Call call) {
    logEvent(new SecureConnectStart(call));
  }

  @Override public void secureConnectEnd(Call call, Handshake handshake) {
    logEvent(new SecureConnectEnd(call, handshake));
  }

  @Override public void connectEnd(Call call, InetSocketAddress inetSocketAddress,
      @Nullable Proxy proxy, Protocol protocol) {
    logEvent(new ConnectEnd(call, inetSocketAddress, proxy, protocol));
  }

  @Override public void connectFailed(Call call, InetSocketAddress inetSocketAddress, Proxy proxy,
      @Nullable Protocol protocol, IOException ioe) {
    logEvent(new ConnectFailed(call, inetSocketAddress, proxy, protocol, ioe));
  }

  @Override public void connectionAcquired(Call call, Connection connection) {
    logEvent(new ConnectionAcquired(call, connection));
  }

  @Override public void connectionReleased(Call call, Connection connection) {
    logEvent(new ConnectionReleased(call, connection));
  }

  @Override public void callStart(Call call) {
    logEvent(new CallStart(call));
  }

  @Override public void requestHeadersStart(Call call) {
    logEvent(new RequestHeadersStart(call));
  }

  @Override public void requestHeadersEnd(Call call, Request request) {
    logEvent(new RequestHeadersEnd(call, request.headers().byteCount()));
  }

  @Override public void requestBodyStart(Call call) {
    logEvent(new RequestBodyStart(call));
  }

  @Override public void requestBodyEnd(Call call, long byteCount) {
    logEvent(new RequestBodyEnd(call, byteCount));
  }

  @Override public void requestFailed(Call call, IOException ioe) {
    logEvent(new RequestFailed(call, ioe));
  }

  @Override public void responseHeadersStart(Call call) {
    logEvent(new ResponseHeadersStart(call));
  }

  @Override public void responseHeadersEnd(Call call, Response response) {
    logEvent(new ResponseHeadersEnd(call, response.headers().byteCount()));
  }

  @Override public void responseBodyStart(Call call) {
    logEvent(new ResponseBodyStart(call));
  }

  @Override public void responseBodyEnd(Call call, long byteCount) {
    logEvent(new ResponseBodyEnd(call, byteCount));
  }

  @Override public void responseFailed(Call call, IOException ioe) {
    logEvent(new ResponseFailed(call, ioe));
  }

  @Override public void callEnd(Call call) {
    logEvent(new CallEnd(call));
  }

  @Override public void callFailed(Call call, IOException ioe) {
    logEvent(new CallFailed(call, ioe));
  }

  static class CallEvent {
    final Call call;
    final List<Object> params;

    CallEvent(Call call, Object... params) {
      this.call = call;
      this.params = asList(params);
    }

    public String getName() {
      return getClass().getSimpleName();
    }

    @Override public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof CallEvent)) return false;

      CallEvent callEvent = (CallEvent) o;

      if (!getName().equals(callEvent.getName())) return false;
      if (!call.equals(callEvent.call)) return false;
      return params.equals(callEvent.params);
    }

    @Override public int hashCode() {
      int result = call.hashCode();
      result = 31 * result + getName().hashCode();
      result = 31 * result + params.hashCode();
      return result;
    }

    public @Nullable CallEvent closes() {
      return null;
    }
  }

  static final class ProxySelectStart extends CallEvent {
    final HttpUrl url;

    ProxySelectStart(Call call, HttpUrl url) {
      super(call, url);
      this.url = url;
    }
  }

  static final class ProxySelectEnd extends CallEvent {
    final HttpUrl url;

    ProxySelectEnd(Call call, HttpUrl url, List<Proxy> proxies) {
      super(call, url, proxies);
      this.url = url;
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

    DnsEnd(Call call, String domainName, List<InetAddress> inetAddressList) {
      super(call, domainName, inetAddressList);
      this.domainName = domainName;
      this.inetAddressList = inetAddressList;
    }

    @Override public @Nullable CallEvent closes() {
      return new DnsStart(call, domainName);
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
    final Proxy proxy;

    ConnectEnd(Call call, InetSocketAddress inetSocketAddress, Proxy proxy, Protocol protocol) {
      super(call, inetSocketAddress, proxy, protocol);
      this.inetSocketAddress = inetSocketAddress;
      this.proxy = proxy;
      this.protocol = protocol;
    }

    @Override public CallEvent closes() {
      return new ConnectStart(call, inetSocketAddress, proxy);
    }
  }

  static final class ConnectFailed extends CallEvent {
    final InetSocketAddress inetSocketAddress;
    final Protocol protocol;
    final Proxy proxy;
    final IOException ioe;

    ConnectFailed(Call call, InetSocketAddress inetSocketAddress, Proxy proxy, Protocol protocol,
        IOException ioe) {
      super(call, inetSocketAddress, proxy, protocol, ioe);
      this.inetSocketAddress = inetSocketAddress;
      this.proxy = proxy;
      this.protocol = protocol;
      this.ioe = ioe;
    }

    @Override public @Nullable CallEvent closes() {
      return new ConnectStart(call, inetSocketAddress, proxy);
    }
  }

  static final class SecureConnectStart extends CallEvent {
    SecureConnectStart(Call call) {
      super(call);
    }
  }

  static final class SecureConnectEnd extends CallEvent {
    final Handshake handshake;

    SecureConnectEnd(Call call, Handshake handshake) {
      super(call, handshake);
      this.handshake = handshake;
    }

    @Override public @Nullable CallEvent closes() {
      return new SecureConnectStart(call);
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

    @Override public @Nullable CallEvent closes() {
      return new ConnectionAcquired(call, connection);
    }
  }

  static final class CallStart extends CallEvent {
    CallStart(Call call) {
      super(call);
    }
  }

  static final class CallEnd extends CallEvent {
    CallEnd(Call call) {
      super(call);
    }

    @Override public @Nullable CallEvent closes() {
      return new CallStart(call);
    }
  }

  static final class CallFailed extends CallEvent {
    final IOException ioe;

    CallFailed(Call call, IOException ioe) {
      super(call, ioe);
      this.ioe = ioe;
    }
  }

  static final class RequestHeadersStart extends CallEvent {
    RequestHeadersStart(Call call) {
      super(call);
    }
  }

  static final class RequestHeadersEnd extends CallEvent {
    final long headerLength;

    RequestHeadersEnd(Call call, long headerLength) {
      super(call, headerLength);
      this.headerLength = headerLength;
    }

    @Override public @Nullable CallEvent closes() {
      return new RequestHeadersStart(call);
    }
  }

  static final class RequestBodyStart extends CallEvent {
    RequestBodyStart(Call call) {
      super(call);
    }
  }

  static final class RequestBodyEnd extends CallEvent {
    final long bytesWritten;

    RequestBodyEnd(Call call, long bytesWritten) {
      super(call, bytesWritten);
      this.bytesWritten = bytesWritten;
    }

    @Override public @Nullable CallEvent closes() {
      return new RequestBodyStart(call);
    }
  }

  static final class RequestFailed extends CallEvent {
    final IOException ioe;

    RequestFailed(Call call, IOException ioe) {
      super(call, ioe);
      this.ioe = ioe;
    }
  }

  static final class ResponseHeadersStart extends CallEvent {
    ResponseHeadersStart(Call call) {
      super(call);
    }
  }

  static final class ResponseHeadersEnd extends CallEvent {
    final long headerLength;

    ResponseHeadersEnd(Call call, long headerLength) {
      super(call, headerLength);
      this.headerLength = headerLength;
    }

    @Override public @Nullable CallEvent closes() {
      return new RequestHeadersStart(call);
    }
  }

  static final class ResponseBodyStart extends CallEvent {
    ResponseBodyStart(Call call) {
      super(call);
    }
  }

  static final class ResponseBodyEnd extends CallEvent {
    final long bytesRead;

    ResponseBodyEnd(Call call, long bytesRead) {
      super(call, bytesRead);
      this.bytesRead = bytesRead;
    }

    @Override public @Nullable CallEvent closes() {
      return new ResponseBodyStart(call);
    }
  }

  static final class ResponseFailed extends CallEvent {
    final IOException ioe;

    ResponseFailed(Call call, IOException ioe) {
      super(call, ioe);
      this.ioe = ioe;
    }
  }
}
