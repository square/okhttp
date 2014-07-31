package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Connection;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Route;
import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * Provides a sequence of {@link Route}s which should be tried for a particular HTTP request.
 *
 * <p>After callers are done requesting connections with {@link #next(HttpEngine)}, they should call
 * {@link #close()} to release resources.
 */
public abstract class RouteSelector {
  public static final String TLS_V1 = "TLSv1";
  public static final String SSL_V3 = "SSLv3";

  public static RouteSelector get(Request request, OkHttpClient client) throws IOException {
    if (client.getMaxConcurrentHandshakes() == 1) {
      // No concurrency is allowed, so use the simpler and faster SimpleRouteSelector.
      return SimpleRouteSelector.get(request, client);
    } else {
      return EagerRouteSelector.get(request, client);
    }
  }

  /**
   * Returns true if there's another route to attempt. Every address has at
   * least one route.
   */
  abstract boolean hasNext();

  /**
   * Selects a route to attempt and connects it if it isn't already.
   *
   * @throws IOException if the connection failed. Clients may continue to call this in order to
   *     try other routes.
   * @throws NoSuchElementException if there are no more routes left to try
   */
  abstract Connection next(HttpEngine owner) throws IOException;

  /**
   * Clients should invoke this method when they encounter a connectivity
   * failure on a connection returned by this route selector.
   */
  abstract void connectFailed(Connection connection, IOException failure);

  /**
   * This should be invoked after a caller is done requesting connections with
   * {@link #next(HttpEngine)}. It is illegal to continue calling {@link #next(HttpEngine)} after
   * this.
   */
  abstract void close();
}
