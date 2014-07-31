package com.squareup.okhttp;

import java.io.IOException;
import java.net.Proxy;
import java.net.Socket;

/**
 * Establishes connections to a route.
 */
public abstract class ConnectionStrategy {
  /**
   * A {@link ConnectionStrategy} which, if given a hostname, will attempt to connect to one of the
   * IPs behind it. If there are multiple IPs behind the hostname, the method by which one IP is
   * selected is undefined.
   */
  public static final ConnectionStrategy SIMPLE = new SimpleConnectionStrategy();

  /**
   * A {@link ConnectionStrategy} which, if given a hostname, will attempt a connection to each IP
   * behind it in parallel. Which ever connection is established first will be returned.
   *
   * <p>If a hostname resolves to multiple IPs in different geographic locations, this will
   * generally give you a connection to the IP with the lowest latency.
   */
  public static final ConnectionStrategy PARALLEL = new ParallelConnectionStrategy();

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
