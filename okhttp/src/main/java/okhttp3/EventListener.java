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
import java.util.List;
import javax.annotation.Nullable;

/**
 * EventListener for analytic events for an OkHttpClient instance.
 *
 * <p>All start/connect/acquire events will eventually receive a matching end/release event,
 * either successful (non-null parameters), or failed (non-null throwable).  The first common
 * parameters of each event pair are used to link the event in case of concurrent or repeated
 * events e.g. dnsStart(call, domainName) -> dnsEnd(call, domainName, inetAddressList, throwable).
 *
 * <p>Nesting is as follows
 * <ul>
 * <li>call -> (dns -> connect -> secure connect)* -> request events</li>
 * <li>call -> (connection acquire/release)*</li>
 * </ul>
 *
 * <p>Request events are ordered: requestHeaders -> requestBody -> responseHeaders -> responseBody
 *
 * <p>Since connections may be reused, the dns and connect events may not be present for a call,
 * or may be repeated in case of failure retries, even concurrently in case of happy eyeballs type
 * scenarios. A redirect cross domain, or to use https may cause additional connection and request
 * events.
 *
 * <p>All events methods must execute fast, without external locking, cannot throw exceptions,
 * attempt to mutate the event parameters, or be reentrant back into the client.
 * Any IO - writing to files or network should be done asynchronously.
 */
public abstract class EventListener {
  public static final EventListener NONE = new EventListener() {
  };

  static EventListener.Factory factory(final EventListener listener) {
    return new EventListener.Factory() {
      public EventListener create(Call call) {
        return listener;
      }
    };
  }

  /**
   * Invoked as soon as a call is enqueued or executed by a client. In case of thread or stream
   * limits, this call may be executed well before processing the request is able to begin.
   *
   * <p>This will be invoked only once for a single {@link Call}. Retries of different routes
   * or redirects will be handled within the boundaries of a single fetchStart and
   * {@link #fetchEnd(Call, Throwable)} pair.
   */
  public void fetchStart(Call call) {
  }

  /**
   * Invoked just prior to a DNS lookup. See {@link Dns#lookup(String)}.
   *
   * <p>This can be invoked more than 1 time for a single {@link Call}. For example, if the response
   * to the {@link Call#request()} is a redirect to a different host.
   *
   * <p>If the {@link Call} is able to reuse an existing pooled connection, this method will not be
   * invoked. See {@link ConnectionPool}.
   */
  public void dnsStart(Call call, String domainName) {
  }

  /**
   * Invoked immediately after a DNS lookup.
   *
   * <p>This method is always invoked after {@link #dnsStart(Call, String)}.
   *
   * <p>{@code inetAddressList} will be non-null and {@code throwable} will be null in the case of a
   * successful DNS lookup.
   *
   * <p>{@code inetAddressList} will be null and {@code throwable} will be non-null in the case of a
   * failed DNS lookup.
   */
  public void dnsEnd(Call call, String domainName, @Nullable List<InetAddress> inetAddressList,
      @Nullable Throwable throwable) {
  }

  /**
   * Invoked just prior to initiating a socket connection.
   *
   * <p>This method will be invoked if no existing connection in the {@link ConnectionPool} can be
   * reused.
   *
   * <p>This can be invoked more than 1 time for a single {@link Call}. For example, if the response
   * to the {@link Call#request()} is a redirect to a different address, or a connection is retried.
   */
  public void connectStart(Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
  }

  /**
   * Invoked just prior to initiating a TLS connection.
   *
   * <p>This method is invoked if the following conditions are met:
   * <ul>
   * <li>The {@link Call#request()} requires TLS.</li>
   * <li>No existing connection from the {@link ConnectionPool} can be reused.</li>
   * </ul>
   *
   * <p>This can be invoked more than 1 time for a single {@link Call}. For example, if the response
   * to the {@link Call#request()} is a redirect to a different address, or a connection is retried.
   */
  public void secureConnectStart(Call call) {
  }

  /**
   * Invoked immediately after a TLS connection was attempted.
   *
   * <p>This method is always invoked after {@link #secureConnectStart(Call)}.
   *
   * <p>{@code handshake} will be non-null and {@code throwable} will be null in the case of a
   * successful TLS connection.
   *
   * <p>{@code handshake} will be null and {@code throwable} will be non-null in the case of a
   * failed TLS connection attempt.
   */
  public void secureConnectEnd(Call call, @Nullable Handshake handshake,
      @Nullable Throwable throwable) {
  }

  /**
   * Invoked immediately after a socket connection was attempted.
   *
   * <p>If the {@code call} uses HTTPS, this will be invoked after
   * {@link #secureConnectEnd(Call, Handshake, Throwable)}, otherwise it will invoked after
   * {@link #connectStart(Call, InetSocketAddress, Proxy)}.
   *
   * <p>{@code protocol} and {@code proxy} will be non-null and {@code throwable} will be null when
   * the connection is successfully established.
   *
   * <p>{@code protocol} and {@code proxy} will be null and {@code throwable} will be non-null in
   * the case of a failed connection attempt.
   */
  public void connectEnd(Call call, InetSocketAddress inetSocketAddress,
      @Nullable Proxy proxy, @Nullable Protocol protocol,
      @Nullable Throwable throwable) {
  }

  /**
   * Invoked after a connection has been acquired for the {@code call}.
   *
   * <p>This can be invoked more than 1 time for a single {@link Call}. For example, if the response
   * to the {@link Call#request()} is a redirect to a different address.
   */
  public void connectionAcquired(Call call, Connection connection) {
  }

  /**
   * Invoked after a connection has been released for the {@code call}.
   *
   * <p>This method is always invoked after {@link #connectionAcquired(Call, Connection)}.
   *
   * <p>This can be invoked more than 1 time for a single {@link Call}. For example, if the response
   * to the {@link Call#request()} is a redirect to a different address.
   */
  public void connectionReleased(Call call, Connection connection) {
  }

  /**
   * Invoked just prior to sending request headers.
   *
   * <p>The connection is implicit, and will generally relate to the last
   * {@link #connectionAcquired(Call, Connection)} event.
   *
   * <p>This can be invoked more than 1 time for a single {@link Call}. For example, if the response
   * to the {@link Call#request()} is a redirect to a different address.
   */
  public void requestHeadersStart(Call call) {
  }

  /**
   * Invoked immediately after sending request headers.
   *
   * <p>This method is always invoked after {@link #requestHeadersStart(Call)}.
   *
   * <p>{@code throwable} will be null in the case of a successful attempt to send the headers.
   *
   * <p>{@code throwable} will be non-null in the case of a failed attempt to send the headers.
   */
  public void requestHeadersEnd(Call call, @Nullable Throwable throwable) {
  }

  /**
   * Invoked just prior to sending a request body.  Will only be invoked for request allowing and
   * having a request body to send.
   *
   * <p>The connection is implicit, and will generally relate to the last
   * {@link #connectionAcquired(Call, Connection)} event.
   *
   * <p>This can be invoked more than 1 time for a single {@link Call}. For example, if the response
   * to the {@link Call#request()} is a redirect to a different address.
   */
  public void requestBodyStart(Call call) {
  }

  /**
   * Invoked immediately after sending a request body.
   *
   * <p>This method is always invoked after {@link #requestBodyStart(Call)}.
   *
   * <p>{@code throwable} will be null in the case of a successful attempt to send the body.
   *
   * <p>{@code throwable} will be non-null in the case of a failed attempt to send the body.
   */
  public void requestBodyEnd(Call call, @Nullable Throwable throwable) {
  }

  /**
   * Invoked just prior to receiving response headers.
   *
   * <p>The connection is implicit, and will generally relate to the last
   * {@link #connectionAcquired(Call, Connection)} event.
   *
   * <p>This can be invoked more than 1 time for a single {@link Call}. For example, if the response
   * to the {@link Call#request()} is a redirect to a different address.
   */
  public void responseHeadersStart(Call call) {
  }

  /**
   * Invoked immediately after receiving response headers.
   *
   * <p>This method is always invoked after {@link #responseHeadersStart(Call)}.
   *
   * <p>{@code throwable} will be null in the case of a successful attempt to receive the headers.
   *
   * <p>{@code throwable} will be non-null in the case of a failed attempt to receive the headers.
   */
  public void responseHeadersEnd(Call call, @Nullable Throwable throwable) {
  }

  /**
   * Invoked just prior to receiving the response body.
   *
   * <p>The connection is implicit, and will generally relate to the last
   * {@link #connectionAcquired(Call, Connection)} event.
   *
   * <p>This will usually be invoked only 1 time for a single {@link Call},
   * exceptions are a limited set of cases including failure recovery.
   */
  public void responseBodyStart(Call call) {
  }

  /**
   * Invoked immediately after receiving a response body and completing reading it.
   *
   * <p>Will only be invoked for requests having a response body e.g. won't be invoked for a
   * websocket upgrade.
   *
   * <p>This method is always invoked after {@link #requestBodyStart(Call)}.
   *
   * <p>{@code throwable} will be null in the case of a successful attempt to send the body.
   *
   * <p>{@code throwable} will be non-null in the case of a failed attempt to send the body.
   */
  public void responseBodyEnd(Call call, @Nullable Throwable throwable) {
  }

  /**
   * Invoked immediately after a call has completely ended.  This includes delayed consumption
   * of response body by the caller.
   *
   * <p>This method is always invoked after {@link #fetchStart(Call)}.
   *
   * <p>{@code throwable} will be null in the case of a successful attempt to execute the call.
   *
   * <p>{@code throwable} will be non-null in the case of a failed attempt to execute the call.
   */
  public void fetchEnd(Call call, @Nullable Throwable throwable) {
  }

  public interface Factory {
    /**
     * Creates an instance of the {@link EventListener} for a particular {@link Call}. The returned
     * {@link EventListener} instance will be used during the lifecycle of the {@code call}.
     *
     * <p>This method is invoked after the {@code call} is created. See
     * {@link OkHttpClient#newCall(Request)}.
     *
     * <p><strong>It is an error for implementations to issue any mutating operations on the
     * {@code call} instance from this method.</strong>
     */
    EventListener create(Call call);
  }
}
