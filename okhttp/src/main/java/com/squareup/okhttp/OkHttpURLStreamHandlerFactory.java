/*
 * Copyright (C) 2013 Square, Inc.
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

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

/**
 * Provides a URLStreamHandlerFactory implementation for use with
 * {@link java.net.URL#setURLStreamHandlerFactory}.
 *
 * Using this factory will ensure that all HTTP/HTTPS connections opened using {@link java.net.URL},
 * such as {@link java.net.URL#openConnection()}, will be handled by the given {@link OkHttpClient}.
 *
 * Example of how to use this factory:
 * <pre>   {@code
 *     OkHttpClient okHttpClient = new OkHttpClient();
 *     URL.setURLStreamHandlerFactory(new OkHttpURLStreamHandlerFactory(okHttpClient));
 * }</pre>
 *
 */
public class OkHttpURLStreamHandlerFactory implements URLStreamHandlerFactory {

  protected static class OkHttpClientHandler extends URLStreamHandler {

    private final OkHttpClient okHttpClient;
    private final int defaultPort;

    public OkHttpClientHandler(OkHttpClient okHttpClient, int defaultPort) {
      this.okHttpClient = okHttpClient;
      this.defaultPort = defaultPort;
    }

    @Override protected URLConnection openConnection(URL url) throws IOException {
      return okHttpClient.open(url);
    }

    @Override protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
      return okHttpClient.open(url, proxy);
    }

    @Override protected int getDefaultPort() {
      return defaultPort;
    }
  }

  private final OkHttpClient okHttpClient;

  public OkHttpURLStreamHandlerFactory(OkHttpClient okHttpClient) {
    this.okHttpClient = okHttpClient;
  }

  public URLStreamHandler createURLStreamHandler(String protocol) {
    if (protocol.equals("http")) {
      return new OkHttpClientHandler(okHttpClient, 80);
    } else if (protocol.equals("https")) {
      return new OkHttpClientHandler(okHttpClient, 443);
    }
    return null;
  }
}
