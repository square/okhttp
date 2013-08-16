/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Connection;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.TunnelRequest;
import java.io.IOException;
import java.net.CacheResponse;
import java.net.SecureCacheResponse;
import java.net.URL;
import javax.net.ssl.SSLSocket;

import static com.squareup.okhttp.internal.Util.getEffectivePort;

public final class HttpsEngine extends HttpEngine {
  /**
   * Stash of HttpsEngine.connection.socket to implement requests like {@code
   * HttpsURLConnection#getCipherSuite} even after the connection has been
   * recycled.
   */
  private SSLSocket sslSocket;

  public HttpsEngine(OkHttpClient client, Policy policy, String method, RawHeaders requestHeaders,
      Connection connection, RetryableOutputStream requestBody) throws IOException {
    super(client, policy, method, requestHeaders, connection, requestBody);
    this.sslSocket = connection != null ? (SSLSocket) connection.getSocket() : null;
  }

  @Override protected void connected(Connection connection) {
    this.sslSocket = (SSLSocket) connection.getSocket();
    super.connected(connection);
  }

  @Override protected boolean acceptCacheResponseType(CacheResponse cacheResponse) {
    return cacheResponse instanceof SecureCacheResponse;
  }

  @Override protected boolean includeAuthorityInRequestLine() {
    // Even if there is a proxy, it isn't involved. Always request just the path.
    return false;
  }

  public SSLSocket getSslSocket() {
    return sslSocket;
  }

  @Override protected TunnelRequest getTunnelConfig() {
    String userAgent = requestHeaders.getUserAgent();
    if (userAgent == null) {
      userAgent = getDefaultUserAgent();
    }

    URL url = policy.getURL();
    return new TunnelRequest(url.getHost(), getEffectivePort(url), userAgent,
        requestHeaders.getProxyAuthorization());
  }
}
