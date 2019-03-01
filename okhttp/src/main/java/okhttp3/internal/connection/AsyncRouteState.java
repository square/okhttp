package okhttp3.internal.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import okhttp3.Address;
import okhttp3.AsyncDns;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Route;

public class AsyncRouteState implements RouteState {

  private final Address address;
  private final AsyncDns asyncDns;

  private CompletableFuture<List<InetSocketAddress>> futureInetSocketAddresses;

  private CompletableFuture<AllRoutes> futureComputedRoutes;

  private AllRoutes computedRoutes;

  public AsyncRouteState(Address address) {
    this.address = address;
    this.asyncDns = (AsyncDns) address.dns();
  }

  @Override public boolean hasPostponedRoutes() {
    if (futureComputedRoutes.isDone()) {
      AllRoutes allRoutes = launderedGetComputedRoutes();
      return !allRoutes.postponedRoutes.isEmpty();
    }

    return false;
  }

  private AllRoutes launderedGetComputedRoutes() {
    try {
      return getComputedRoutes();
    } catch (IOException e) {
      if (e instanceof UnknownHostException)
        throw new UnknownHostRuntimeException(e);

      throw new RuntimeException(e);
    }
  }

  private AllRoutes getComputedRoutes() throws IOException {
    try {
      if (computedRoutes == null)
        computedRoutes = futureComputedRoutes.get();

      return computedRoutes;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return AllRoutes.EMPTY;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException)
        throw (IOException) cause;

      throw new RuntimeException(e);
    }
  }

  @Override public void addPostponedRoutes() throws IOException {
    if (futureComputedRoutes.isDone()) {
      AllRoutes allRoutes = getComputedRoutes();
      List<Route> routes = allRoutes.routes;
      List<Route> postponedRoutes = allRoutes.postponedRoutes;
      routes.addAll(postponedRoutes);
      postponedRoutes.clear();
    }
  }

  @Override public void initComputedRoutes() {
    computedRoutes = null;
  }

  @Override public boolean hasComputedRoutes() throws IOException {
    if (futureComputedRoutes.isDone()) {
      AllRoutes allRoutes = getComputedRoutes();
      return !allRoutes.routes.isEmpty();
    }

    return false;
  }

  @Override public RouteSelector.Selection getComputedRouteSelection() throws IOException {
    if (futureComputedRoutes.isDone()) {
      AllRoutes allRoutes = getComputedRoutes();
      return new RouteSelector.Selection(allRoutes.routes);
    }

    throw new IOException("Asynchronous DNS lookup not yet completed");
  }

  @Override
  public void computeRoutes(Proxy proxy, RouteDatabase routeDatabase) {
    futureComputedRoutes = futureInetSocketAddresses.thenApply(inetSocketAddresses -> {
      List<Route> postponedRoutes = new ArrayList<>();
      List<Route> routes = new ArrayList<>();

      for (int i = 0, size = inetSocketAddresses.size(); i < size; i++) {
        Route route = new Route(address, proxy, inetSocketAddresses.get(i));
        if (routeDatabase.shouldPostpone(route)) {
          postponedRoutes.add(route);
        } else {
          routes.add(route);
        }
      }

      return new AllRoutes(routes, postponedRoutes);
    });
  }

  @Override public void resetAddresses() {
    futureInetSocketAddresses = null;
    // futureComputedRoutes = null;
  }

  @Override public void addUnresolvedAddress(String socketHost, int socketPort) {
    List<InetSocketAddress> addresses =
        Collections.singletonList(InetSocketAddress.createUnresolved(socketHost, socketPort));
    futureInetSocketAddresses = CompletableFuture.completedFuture(addresses);
  }

  @Override public void addAddresses(String socketHost, int socketPort, Call call,
      EventListener eventListener) throws IOException {
    eventListener.dnsStart(call, socketHost);

    // Try each address for best behavior in mixed IPv4/IPv6 environments.
    CompletableFuture<List<InetAddress>> futureAddresses = asyncDns.lookupAsync(socketHost);
    futureInetSocketAddresses = futureAddresses
        .thenApply(addresses -> {
          eventListener.dnsEnd(call, socketHost, addresses);
          return addresses;
        })
        .thenApply(addresses ->
          addresses.stream()
              .map(inetAddress -> new InetSocketAddress(inetAddress, socketPort))
              .collect(Collectors.toList())
        );
  }

  private static final class AllRoutes {
    private static final AllRoutes EMPTY =
        new AllRoutes(Collections.emptyList(), Collections.emptyList());

    final List<Route> routes;
    final List<Route> postponedRoutes;

    private AllRoutes(List<Route> routes, List<Route> postponedRoutes) {
      this.routes = routes;
      this.postponedRoutes = postponedRoutes;
    }
  }

}
