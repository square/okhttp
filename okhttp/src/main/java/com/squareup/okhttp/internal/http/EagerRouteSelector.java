package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Connection;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.internal.Internal;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link RouteSelector} which eagerly initiates handshakes with multiple routes concurrently,
 * and returns them in completion order.
 *
 * <p>The handshakes are triggered in batches, so that we are able to compare the latency of
 * different routes under the same network conditions.
 */
final class EagerRouteSelector extends RouteSelector {
  private static final Executor executor = Executors.newCachedThreadPool(new ThreadFactory() {
    final AtomicInteger threadNumber = new AtomicInteger(1);
    @Override public Thread newThread(Runnable r) {
      return new Thread(r, "handshake-task-" + threadNumber);
    }
  });

  private final SimpleRouteSelector simpleRouteSelector;
  private final OkHttpClient client;
  private final Request request;
  private final CompletionService<Connection> completionService;
  private int handshakesInProgress = 0;

  private EagerRouteSelector(SimpleRouteSelector simpleRouteSelector, OkHttpClient client,
      Request request) {
    this.simpleRouteSelector = simpleRouteSelector;
    this.client = client;
    this.request = request;
    this.completionService = new ExecutorCompletionService<>(executor);
  }

  public static EagerRouteSelector get(Request request, OkHttpClient client) throws IOException {
    return new EagerRouteSelector(SimpleRouteSelector.get(request, client), client, request);
  }

  @Override
  public boolean hasNext() {
    return handshakesInProgress > 0 || simpleRouteSelector.hasNext();
  }

  @Override
  public Connection next(HttpEngine owner) throws IOException {
    // If there are no handshake tasks in progress, trigger another batch of them.
    if (handshakesInProgress == 0) {
      triggerMoreHandshakeTasks(owner);
      if (handshakesInProgress == 0) {
        throw new NoSuchElementException(); // Out of routes to try.
      }
    }

    return waitForNextConnection();
  }

  @Override
  public void connectFailed(Connection connection, IOException failure) {
    simpleRouteSelector.connectFailed(connection, failure);
  }

  @Override
  public void close() {
    // If we eagerly created connections which ended up not being needed, we should now return them
    // to the connection pool.
    while (handshakesInProgress > 0) {
      try {
        Connection connection = waitForNextConnection();
        Internal.instance.recycle(client.getConnectionPool(), connection);
      } catch (IOException ignored) {
        // The connection failed, so there's nothing to cleanup.
      }
    }
  }

  /**
   * Triggers new handshake tasks until we either reach maximum concurrency or run out of routes.
   */
  private void triggerMoreHandshakeTasks(final HttpEngine owner) throws IOException {
    while (handshakesInProgress < client.getMaxConcurrentHandshakes()
        && simpleRouteSelector.hasNext()) {
      final Connection connection = simpleRouteSelector.nextUnconnected();
      completionService.submit(new Callable<Connection>() {
        @Override public Connection call() throws Exception {
          Internal.instance.connectAndSetOwner(client, connection, owner, request);
          return connection;
        }
      });
      handshakesInProgress++;
    }
  }

  /**
   * Waits for the next handshake task to complete and returns the resulting connection.
   *
   * @throws IOException if the handshake failed
   */
  private Connection waitForNextConnection() throws IOException {
    try {
      Future<Connection> connection = completionService.take();
      handshakesInProgress--;
      return connection.get();
    } catch (InterruptedException e) {
      throw new InterruptedIOException();
    } catch (ExecutionException e) {
      throw new IOException("Failed to connect", e.getCause());
    }
  }
}
