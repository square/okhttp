package okhttp3.internal.connection;

import java.io.IOException;
import java.net.Proxy;
import java.util.List;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Route;
import okhttp3.internal.connection.RouteSelector.Selection;

interface RouteState {

  boolean hasPostponedRoutes();

  void addPostponedRoutes() throws IOException;

  void initComputedRoutes();

  boolean hasComputedRoutes() throws IOException;

  Selection getComputedRouteSelection() throws IOException;

  void computeRoutes(Proxy proxy, RouteDatabase routeDatabase);

  void resetAddresses();

  void addUnresolvedAddress(String socketHost, int socketPort);

  void addAddresses(String socketHost, int socketPort,
      Call call, EventListener eventListener) throws IOException;

}
