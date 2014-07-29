package com.squareup.okhttp;

import java.io.IOException;
import java.net.Proxy;
import java.net.Socket;

/**
 * Establishes connections to a route.
 */
public abstract class ConnectionStrategy {
  /**
   * Establish a connection to the given route.
   *
   * @param route the route to connect to
   * @param connectTimeout the connection timeout in milliseconds
   * @return a socket connected to the given route
   * @throws IOException if unable to connect
   */
  public abstract Socket connect(Route route, int connectTimeout) throws IOException;

  /**
   * Create a new, unconnected socket for the given route.
   */
  static Socket createSocket(Route route) throws IOException {
    if (route.proxy.type() == Proxy.Type.DIRECT || route.proxy.type() == Proxy.Type.HTTP) {
      return route.address.socketFactory.createSocket();
    } else {
      return new Socket(route.proxy);
    }
  }
}
