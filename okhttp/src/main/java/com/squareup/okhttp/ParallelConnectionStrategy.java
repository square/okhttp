package com.squareup.okhttp;

import com.squareup.okhttp.internal.Platform;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * See {@link ConnectionStrategy#PARALLEL}.
 */
final class ParallelConnectionStrategy extends ConnectionStrategy {
  /**
   * The maximum number of concurrent TCP handshakes we will perform for a single hostname. If a
   * hostname has many IPs behind it, we will initially connect to MAX_CONCURRENT_HANDSHAKES of
   * them. Only if those connections fail will we will try the rest of the IPs.
   */
  private static final int MAX_CONCURRENT_HANDSHAKES = 3;

  // Use an unbounded thread pool to make sure we don't introduce an unnecessary bottleneck.
  private static final ExecutorService executor = Executors.newCachedThreadPool();

  private final HostResolver hostResolver;

  ParallelConnectionStrategy() {
    this(HostResolver.DEFAULT);
  }

  ParallelConnectionStrategy(HostResolver hostResolver) {
    this.hostResolver = hostResolver;
  }

  @Override public Socket connect(final Route route, final int connectTimeout) throws IOException {
    Collection<InetSocketAddress> addressesToTry = getAddressesToTry(route.inetSocketAddress);

    // If there's only one address, we can avoid the overhead of parallelization.
    if (addressesToTry.size() == 1) {
      Socket socket = createSocket(route);
      Platform.get().connectSocket(socket, addressesToTry.iterator().next(), connectTimeout);
      return socket;
    }

    CompletionService<Socket> completionService = new ExecutorCompletionService<>(executor);
    final AtomicReference<Socket> winner = new AtomicReference<>();

    // Prepare a set of connection tasks, one for each address we want to try.
    Queue<Callable<Socket>> connectionTaskQueue = new ArrayDeque<>();
    for (final InetSocketAddress address : addressesToTry) {
      connectionTaskQueue.add(new Callable<Socket>() {
        @Override public Socket call() throws IOException {
          Socket socket = createSocket(route);
          Platform.get().connectSocket(socket, address, connectTimeout);
          if (!winner.compareAndSet(null, socket)) {
            socket.close(); // Another connection won the race.
          }
          return socket;
        }
      });
    }

    // Trigger the initial set of (up to MAX_CONCURRENT_HANDSHAKES) connection tasks.
    int handshakesInProgress = 0;
    while (handshakesInProgress < MAX_CONCURRENT_HANDSHAKES && !connectionTaskQueue.isEmpty()) {
      completionService.submit(connectionTaskQueue.remove());
      handshakesInProgress++;
    }

    // Wait for connection tasks to complete until we either get a successful connection, or run out
    // of addresses to try.
    Throwable latestFailure = null;
    while (handshakesInProgress > 0) {
      try {
        completionService.take().get(); // Block until a connection task completes.
        return winner.get();
      } catch (InterruptedException e) {
        throw new InterruptedIOException();
      } catch (ExecutionException e) {
        latestFailure = e.getCause();
      }

      // If there are more addresses remaining, start a task to try the next one.
      if (!connectionTaskQueue.isEmpty()) {
        completionService.submit(connectionTaskQueue.remove());
      } else {
        handshakesInProgress--;
      }
    }

    throw new IOException("Failed to connect to " + route.inetSocketAddress, latestFailure);
  }

  /**
   * Given a socket address, determines which socket addresses we shall attempt to connect to.
   *
   * <p>The purpose of this is to convert a socket address with a hostname into one or more socket
   * addresses, each containing one of the IPs behind the hostname.
   */
  private Collection<InetSocketAddress> getAddressesToTry(InetSocketAddress address) {
    Collection<InetSocketAddress> addressesToTry = new ArrayList<>();
    try {
      for (InetAddress inetAddress : hostResolver.getAllByName(address.getHostString())) {
        addressesToTry.add(new InetSocketAddress(inetAddress, address.getPort()));
      }
    } catch (UnknownHostException e) {
      // We weren't able to resolve the hostname, but a proxy may be able to. Pass it along.
      addressesToTry.add(address);
    }
    return addressesToTry;
  }
}
