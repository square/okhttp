/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.http.RawHeaders;

import static com.squareup.okhttp.internal.Util.getDefaultPort;

/**
 * Routing and authentication information sent to an HTTP proxy to create a
 * HTTPS to an origin server. Everything in the tunnel request is sent
 * unencrypted to the proxy server.
 *
 * <p>See <a href="http://www.ietf.org/rfc/rfc2817.txt">RFC 2817, Section
 * 5.2</a>.
 */
public final class TunnelRequest {
  final String host;
  final int port;
  final String userAgent;
  final String proxyAuthorization;

  /**
   * @param host the origin server's hostname. Not null.
   * @param port the origin server's port, like 80 or 443.
   * @param userAgent the client's user-agent. Not null.
   * @param proxyAuthorization proxy authorization, or null if the proxy is
   * used without an authorization header.
   */
  public TunnelRequest(String host, int port, String userAgent, String proxyAuthorization) {
    if (host == null) throw new NullPointerException("host == null");
    if (userAgent == null) throw new NullPointerException("userAgent == null");
    this.host = host;
    this.port = port;
    this.userAgent = userAgent;
    this.proxyAuthorization = proxyAuthorization;
  }

  /**
   * If we're creating a TLS tunnel, send only the minimum set of headers.
   * This avoids sending potentially sensitive data like HTTP cookies to
   * the proxy unencrypted.
   */
  RawHeaders getRequestHeaders() {
    RawHeaders result = new RawHeaders();
    result.setRequestLine("CONNECT " + host + ":" + port + " HTTP/1.1");

    // Always set Host and User-Agent.
    result.set("Host", port == getDefaultPort("https") ? host : (host + ":" + port));
    result.set("User-Agent", userAgent);

    // Copy over the Proxy-Authorization header if it exists.
    if (proxyAuthorization != null) {
      result.set("Proxy-Authorization", proxyAuthorization);
    }

    // Always set the Proxy-Connection to Keep-Alive for the benefit of
    // HTTP/1.0 proxies like Squid.
    result.set("Proxy-Connection", "Keep-Alive");
    return result;
  }
}
