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
package okhttp3.internal;

import java.net.MalformedURLException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLSocket;
import okhttp3.Address;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.internal.cache.InternalCache;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.RouteDatabase;
import okhttp3.internal.connection.StreamAllocation;

/**
 * Escalate internal APIs in {@code okhttp3} so they can be used from OkHttp's implementation
 * packages. The only implementation of this interface is in {@link OkHttpClient}.
 */
public abstract class Internal {

  public static void initializeInstanceForTests() {
    // Needed in tests to ensure that the instance is actually pointing to something.
    new OkHttpClient();
  }

  public static Internal instance;

  public abstract void addLenient(Headers.Builder builder, String line);

  public abstract void addLenient(Headers.Builder builder, String name, String value);

  public abstract void setCache(OkHttpClient.Builder builder, InternalCache internalCache);

  public abstract RealConnection get(
      ConnectionPool pool, Address address, StreamAllocation streamAllocation);

  public abstract void put(ConnectionPool pool, RealConnection connection);

  public abstract boolean connectionBecameIdle(ConnectionPool pool, RealConnection connection);

  public abstract RouteDatabase routeDatabase(ConnectionPool connectionPool);

  public abstract void apply(ConnectionSpec tlsConfiguration, SSLSocket sslSocket,
      boolean isFallback);

  public abstract HttpUrl getHttpUrlChecked(String url)
      throws MalformedURLException, UnknownHostException;
}
