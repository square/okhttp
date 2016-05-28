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
package okhttp3.internal.huc;

import java.net.URL;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Handshake;
import okhttp3.OkHttpClient;
import okhttp3.internal.URLFilter;

public final class HttpsURLConnectionImpl extends DelegatingHttpsURLConnection {
  private final HttpURLConnectionImpl delegate;

  public HttpsURLConnectionImpl(URL url, OkHttpClient client) {
    this(new HttpURLConnectionImpl(url, client));
  }

  public HttpsURLConnectionImpl(URL url, OkHttpClient client, URLFilter filter) {
    this(new HttpURLConnectionImpl(url, client, filter));
  }

  public HttpsURLConnectionImpl(HttpURLConnectionImpl delegate) {
    super(delegate);
    this.delegate = delegate;
  }

  @Override protected Handshake handshake() {
    if (delegate.httpEngine == null) {
      throw new IllegalStateException("Connection has not yet been established");
    }

    // If there's a response, get the handshake from there so that caching
    // works. Otherwise get the handshake from the connection because we might
    // have not connected yet.
    return delegate.httpEngine.hasResponse()
        ? delegate.httpEngine.getResponse().handshake()
        : delegate.handshake;
  }

  @Override public void setHostnameVerifier(HostnameVerifier hostnameVerifier) {
    delegate.client = delegate.client.newBuilder()
        .hostnameVerifier(hostnameVerifier)
        .build();
  }

  @Override public HostnameVerifier getHostnameVerifier() {
    return delegate.client.hostnameVerifier();
  }

  @Override public void setSSLSocketFactory(SSLSocketFactory sslSocketFactory) {
    // will fail in JDK9
    delegate.client = delegate.client.newBuilder()
        .sslSocketFactory(sslSocketFactory)
        .build();
  }

  @Override public SSLSocketFactory getSSLSocketFactory() {
    return delegate.client.sslSocketFactory();
  }

  @Override public long getContentLengthLong() {
    return delegate.getContentLengthLong();
  }

  @Override public void setFixedLengthStreamingMode(long contentLength) {
    delegate.setFixedLengthStreamingMode(contentLength);
  }

  @Override public long getHeaderFieldLong(String field, long defaultValue) {
    return delegate.getHeaderFieldLong(field, defaultValue);
  }
}
