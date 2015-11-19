/*
 * Copyright (C) 2014 Square, Inc.
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

import com.squareup.okhttp.internal.huc.HttpURLConnectionImpl;
import com.squareup.okhttp.internal.huc.HttpsURLConnectionImpl;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

public final class OkUrlFactory implements URLStreamHandlerFactory, Cloneable {
  private final OkHttpClient client;

  public OkUrlFactory(OkHttpClient client) {
    this.client = client;
  }

  public OkHttpClient client() {
    return client;
  }

  /**
   * Returns a copy of this stream handler factory that includes a shallow copy
   * of the internal {@linkplain OkHttpClient HTTP client}.
   */
  @Override public OkUrlFactory clone() {
    return new OkUrlFactory(client.clone());
  }

  public HttpURLConnection open(URL url) {
    return open(url, client.getProxy());
  }

  HttpURLConnection open(URL url, Proxy proxy) {
    String protocol = url.getProtocol();
    OkHttpClient copy = client.copyWithDefaults();
    copy.setProxy(proxy);

    if ("http".equals(protocol)) return new HttpURLConnectionImpl(url, copy);
    if ("https".equals(protocol)) return new HttpsURLConnectionImpl(url, copy);
    throw new IllegalArgumentException("Unexpected protocol: " + protocol);
  }

  /**
   * Creates a URLStreamHandler as a {@link java.net.URL#setURLStreamHandlerFactory}.
   *
   * <p>This code configures OkHttp to handle all HTTP and HTTPS connections
   * created with {@link java.net.URL#openConnection()}: <pre>   {@code
   *
   *   OkHttpClient okHttpClient = new OkHttpClient();
   *   URL.setURLStreamHandlerFactory(new OkUrlFactory(okHttpClient));
   * }</pre>
   */
  @Override public URLStreamHandler createURLStreamHandler(final String protocol) {
    if (!"http".equals(protocol) && !"https".equals(protocol)) return null;

    return new URLStreamHandler() {
      @Override protected URLConnection openConnection(URL url) {
        return open(url);
      }

      @Override protected URLConnection openConnection(URL url, Proxy proxy) {
        return open(url, proxy);
      }

      @Override protected int getDefaultPort() {
        if ("http".equals(protocol)) return 80;
        if ("https".equals(protocol)) return 443;
        throw new AssertionError();
      }
    };
  }
}
