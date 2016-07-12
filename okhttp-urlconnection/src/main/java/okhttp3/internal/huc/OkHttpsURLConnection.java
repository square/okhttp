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

public final class OkHttpsURLConnection extends DelegatingHttpsURLConnection {
  private final OkHttpURLConnection delegate;

  public OkHttpsURLConnection(URL url, OkHttpClient client) {
    this(new OkHttpURLConnection(url, client));
  }

  public OkHttpsURLConnection(URL url, OkHttpClient client, URLFilter filter) {
    this(new OkHttpURLConnection(url, client, filter));
  }

  public OkHttpsURLConnection(OkHttpURLConnection delegate) {
    super(delegate);
    this.delegate = delegate;
  }

  @Override protected Handshake handshake() {
    if (delegate.call == null) {
      throw new IllegalStateException("Connection has not yet been established");
    }

    return delegate.handshake;
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
    // This fails in JDK 9 because OkHttp is unable to extract the trust manager.
    delegate.client = delegate.client.newBuilder()
        .sslSocketFactory(sslSocketFactory)
        .build();
  }

  @Override public SSLSocketFactory getSSLSocketFactory() {
    return delegate.client.sslSocketFactory();
  }

}
