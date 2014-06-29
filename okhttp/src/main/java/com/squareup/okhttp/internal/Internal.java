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
package com.squareup.okhttp.internal;

import com.squareup.okhttp.Connection;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.internal.http.HttpEngine;
import com.squareup.okhttp.internal.http.Transport;
import java.io.IOException;

/**
 * Escalate internal APIs in {@code com.squareup.okhttp} so they can be used
 * from OkHttp's implementation packages. The only implementation of this
 * interface is in {@link com.squareup.okhttp.OkHttpClient}.
 */
public abstract class Internal {
  public static Internal instance;

  public abstract Transport newTransport(Connection connection, HttpEngine httpEngine)
      throws IOException;

  public abstract boolean clearOwner(Connection connection);

  public abstract void closeIfOwnedBy(Connection connection, Object owner) throws IOException;

  public abstract int recycleCount(Connection connection);

  public abstract void setProtocol(Connection connection, Protocol protocol);

  public abstract void setOwner(Connection connection, HttpEngine httpEngine);

  public abstract boolean isReadable(Connection pooled);

  public abstract void addLine(Headers.Builder builder, String line);

  public abstract void setCache(OkHttpClient client, InternalCache internalCache);

  public abstract InternalCache internalCache(OkHttpClient client);

  public abstract void recycle(ConnectionPool pool, Connection connection);

  public abstract RouteDatabase routeDatabase(OkHttpClient client);

  public abstract void connectAndSetOwner(OkHttpClient client, Connection connection,
      HttpEngine owner, Request request) throws IOException;
}
