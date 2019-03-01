package okhttp3.internal.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Route;
import okhttp3.internal.connection.RouteSelector.Selection;

final class SyncRouteState implements RouteState {

  private final Address address;

  /* State for negotiating the next socket address to use. */
  private List<InetSocketAddress> inetSocketAddresses = Collections.emptyList();

  /* State for negotiating failed routes */
  private List<Route> postponedRoutes = new ArrayList<>();

  List<Route> computedRoutes;

  public SyncRouteState(Address address) {
    this.address = address;
  }

  @Override public boolean hasPostponedRoutes() {
    return !postponedRoutes.isEmpty();
  }

  @Override public void addPostponedRoutes() {
    computedRoutes.addAll(postponedRoutes);
    postponedRoutes.clear();
  }

  @Override public void initComputedRoutes() {
    computedRoutes = new ArrayList<>();
  }

  @Override public boolean hasComputedRoutes() {
    return !computedRoutes.isEmpty();
  }

  public Selection getComputedRouteSelection() {
    return new Selection(computedRoutes);
  }

  @Override public void computeRoutes(Proxy proxy, RouteDatabase routeDatabase) {
    for (int i = 0, size = inetSocketAddresses.size(); i < size; i++) {
      Route route = new Route(address, proxy, inetSocketAddresses.get(i));
      if (routeDatabase.shouldPostpone(route)) {
        postponedRoutes.add(route);
      } else {
        computedRoutes.add(route);
      }
    }
  }

  @Override public void resetAddresses() {
    inetSocketAddresses = new ArrayList<>();
  }

  @Override public void addUnresolvedAddress(String socketHost, int socketPort) {
    inetSocketAddresses.add(InetSocketAddress.createUnresolved(socketHost, socketPort));
  }

  @Override public void addAddresses(String socketHost, int socketPort,
      Call call, EventListener eventListener) throws IOException {
    eventListener.dnsStart(call, socketHost);

    // Try each address for best behavior in mixed IPv4/IPv6 environments.
    List<InetAddress> addresses = address.dns().lookup(socketHost);
    if (addresses.isEmpty()) {
      throw new UnknownHostException(address.dns() + " returned no addresses for " + socketHost);
    }

    eventListener.dnsEnd(call, socketHost, addresses);

    for (int i = 0, size = addresses.size(); i < size; i++) {
      InetAddress inetAddress = addresses.get(i);
      inetSocketAddresses.add(new InetSocketAddress(inetAddress, socketPort));
    }
  }

}
