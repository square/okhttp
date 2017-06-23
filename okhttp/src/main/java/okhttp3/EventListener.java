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
import java.util.List;
import javax.annotation.Nullable;

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

  public void connectStart(Call call, InetAddress address, int port) {
  }

  /**
   * Invoked just prior to initiating a TLS connection.
   *
   * <p>This method is invoked if the following conditions are met:
   * <ul>
   *   <li>The {@link Call#request()} requires TLS.</li>
   *   <li>No existing connection from the {@link ConnectionPool} can be reused.</li>
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

  public void connectEnd(Call call,  InetAddress address, int port, String protocol,
      Throwable throwable) {
  }

  public void requestHeadersStart(Call call) {
  }

  public void requestHeadersEnd(Call call, Throwable throwable) {
  }

  public void requestBodyStart(Call call) {
  }

  public void requestBodyEnd(Call call, Throwable throwable) {
  }

  public void responseHeadersStart(Call call) {
  }

  public void responseHeadersEnd(Call call, Throwable throwable) {
  }

  public void responseBodyStart(Call call) {
  }

  public void responseBodyEnd(Call call, Throwable throwable) {
  }

  public void fetchEnd(Call call, Throwable throwable) {
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
