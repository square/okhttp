package com.squareup.okhttp;

import com.squareup.okhttp.internal.Platform;
import java.io.IOException;
import java.net.Socket;

/**
 * A {@link ConnectionStrategy} which, if given a hostname, will attempt to connect to one of the
 * IPs behind it. If there are multiple IPs behind the hostname, the method by which one IP is
 * selected is undefined.
 */
final class SimpleConnectionStrategy extends ConnectionStrategy {
  @Override public Socket connect(Route route, int connectTimeout) throws IOException {
    Socket socket = createSocket(route);
    Platform.get().connectSocket(socket, route.getSocketAddress(), connectTimeout);
    return socket;
  }
}
