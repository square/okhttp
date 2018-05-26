package okhttp3.internal.ws;

import java.net.ProtocolException;
import java.util.Random;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Implementation of Websocket upgrade and connection establishment.
 */
public class WebsocketUpgradeHandler implements UpgradeHandler<WebSocket> {
  private final RealWebSocket webSocket;
  private final Request originalRequest;
  private WebSocketListener listener;
  private final String key;

  public WebsocketUpgradeHandler(Request request, WebSocketListener listener, Random random,
      int pingInterval) {
    this.listener = listener;

    this.originalRequest = request;
    webSocket = new RealWebSocket(request, listener, random, pingInterval);

    byte[] nonce = new byte[16];
    random.nextBytes(nonce);
    this.key = ByteString.of(nonce).base64();
  }

  public void checkResponse(Response response) throws ProtocolException {
    String headerAccept = response.header("Sec-WebSocket-Accept");
    String acceptExpected =
        ByteString.encodeUtf8(key + WebSocketProtocol.ACCEPT_MAGIC).sha1().base64();
    if (!acceptExpected.equals(headerAccept)) {
      ProtocolException pe = new ProtocolException("Expected 'Sec-WebSocket-Accept' header value '"
          + acceptExpected
          + "' but was '"
          + headerAccept
          + "'");
      webSocket.failWebSocket(pe, response);
      throw pe;
    }
  }

  @Override public void process(Streams streams, Response response) {
    // Process all web socket messages.
    try {
      listener.onOpen(webSocket, response);
      String name = "OkHttp WebSocket " + originalRequest.url().redact();
      webSocket.initReaderAndWriter(name, streams);
      webSocket.loopReader();
    } catch (Exception e) {
      webSocket.failWebSocket(e, null);
    }
  }

  @Override public boolean supportsProtocol(Protocol protocol) {
    return protocol == Protocol.HTTP_1_1;
  }

  @Override public String upgradeProtocolToken() {
    return "websocket";
  }

  @Override public WebSocket result() {
    return webSocket;
  }

  @Override public Request addUpgradeHeaders(Request upgradeRequest) {
    return upgradeRequest.newBuilder()
        .header("Sec-WebSocket-Key", key)
        .header("Sec-WebSocket-Version", "13")
        .build();
  }

  @Override public void failConnect(Response response, Exception e) {
    webSocket.failWebSocket(e, response);
  }
}
