package com.squareup.okhttp;

import com.squareup.okhttp.internal.Platform;
import java.io.IOException;
import java.net.Socket;

/**
 * See {@link ConnectionStrategy#SIMPLE}.
 */
final class SimpleConnectionStrategy extends ConnectionStrategy {
  @Override public Socket connect(Route route, int connectTimeout) throws IOException {
    Socket socket = createSocket(route);
    Platform.get().connectSocket(socket, route.getSocketAddress(), connectTimeout);
    return socket;
  }
}
