package com.squareup.okhttp;

import com.squareup.okhttp.internal.Platform;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link ConnectionStrategy} which, if given a hostname, will attempt a connection to each IP
 * behind it in parallel. Which ever connection is established first will be returned.
 *
 * If a hostname resolves to multiple IPs in different geographic locations, this will generally
 * give you a connection to the IP with the lowest latency.
 */
public final class ParallelConnectionStrategy extends ConnectionStrategy {
  public static final ParallelConnectionStrategy DEFAULT = new ParallelConnectionStrategy();

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
    CompletionService<Socket> completionService = new ExecutorCompletionService<>(executor);
    final AtomicReference<Socket> winner = new AtomicReference<>();

    for (final InetSocketAddress address : addressesToTry) {
      completionService.submit(new Callable<Socket>() {
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

    int numPendingTasks = addressesToTry.size();
    Throwable latestFailure = null;
    while (winner.get() == null && numPendingTasks > 0) {
      try {
        completionService.take().get(); // Block until a connection task completes.
      } catch (InterruptedException e) {
        throw new InterruptedIOException();
      } catch (ExecutionException e) {
        latestFailure = e.getCause();
      }
      numPendingTasks--;
    }

    if (winner.get() == null) {
      throw new IOException("Failed to connect to " + route.inetSocketAddress, latestFailure);
    }
    return winner.get();
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
