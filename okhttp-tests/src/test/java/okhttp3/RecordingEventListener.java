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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

final class RecordingEventListener extends EventListener {
  final Deque<CallEvent> eventSequence = new ArrayDeque<>();

  final List<Object> forbiddenLocks = new ArrayList<>();

  /** Confirm that the thread does not hold a lock on {@code lock} during the callback. */
  public void forbidLock(Object lock) {
    forbiddenLocks.add(lock);
  }

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

  List<String> recordedEventTypes() {
    List<String> eventTypes = new ArrayList<>();
    for (CallEvent event : eventSequence) {
      eventTypes.add(event.getName());
    }
    return eventTypes;
  }

  void clearAllEvents() {
    eventSequence.clear();
  }

  private void logEvent(CallEvent e) {
    for (Object lock : forbiddenLocks) {
      assertFalse(lock.toString(), Thread.holdsLock(lock));
    }

    CallEvent startEvent = e.closes();

    if (startEvent != null) {
      assertTrue(e.getName() + " without matching " + startEvent.getName(),
          eventSequence.contains(startEvent));
    }

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
      @Nullable Proxy proxy, Protocol protocol, Throwable throwable) {
    logEvent(new ConnectEnd(call, inetSocketAddress, proxy, protocol, throwable));
  }

  @Override public void connectionAcquired(Call call, Connection connection) {
    logEvent(new ConnectionAcquired(call, connection));
  }

  @Override public void connectionReleased(Call call, Connection connection) {
    logEvent(new ConnectionReleased(call, connection));
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

  static class CallEvent {
    final Call call;
    final List<Object> params;

    CallEvent(Call call, Object... params) {
      this.call = call;
      this.params = Arrays.asList(params);
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

    @Nullable @Override public CallEvent closes() {
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
    final Throwable throwable;
    final Proxy proxy;

    ConnectEnd(Call call, InetSocketAddress inetSocketAddress, Proxy proxy, Protocol protocol,
        Throwable throwable) {
      super(call, inetSocketAddress, proxy, protocol, throwable);
      this.inetSocketAddress = inetSocketAddress;
      this.proxy = proxy;
      this.protocol = protocol;
      this.throwable = throwable;
    }

    @Nullable @Override public CallEvent closes() {
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
    final Throwable throwable;

    SecureConnectEnd(Call call, Handshake handshake, Throwable throwable) {
      super(call, handshake, throwable);
      this.handshake = handshake;
      this.throwable = throwable;
    }

    @Nullable @Override public CallEvent closes() {
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

    @Nullable @Override public CallEvent closes() {
      return new ConnectionAcquired(call, connection);
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

    @Nullable @Override public CallEvent closes() {
      return new FetchStart(call);
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

    @Nullable @Override public CallEvent closes() {
      return new RequestHeadersStart(call);
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

    @Nullable @Override public CallEvent closes() {
      return new RequestBodyStart(call);
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

    @Nullable @Override public CallEvent closes() {
      return new RequestHeadersStart(call);
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

    @Nullable @Override public CallEvent closes() {
      return new ResponseBodyStart(call);
    }
  }
}
