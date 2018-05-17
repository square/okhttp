package okhttp3.internal.ws;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.Random;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Streams;
import okhttp3.UpgradeHandler;
import okio.ByteString;

public class WebsocketUpgradeHandler implements UpgradeHandler {
  private final Random random;
  private final String key;

  public WebsocketUpgradeHandler(Random random) {
    this.random = random;

    byte[] nonce = new byte[16];
    random.nextBytes(nonce);
    this.key = ByteString.of(nonce).base64();
  }

  @Override public boolean supportsHttp11() {
    return true;
  }

  @Override public boolean supportsHttp2() {
    return true;
  }

  @Override public String connectProtocol() {
    return "websocket";
  }

  @Override public Request addUpgradeHeaders(Request upgradeRequest) {
    return upgradeRequest.newBuilder()
        .header("Sec-WebSocket-Key", key)
        .header("Sec-WebSocket-Version", "13")
        .build();
  }

  @Override public Object complete(Response response, Streams streams) throws IOException {
    String headerAccept = response.header("Sec-WebSocket-Accept");
    String acceptExpected =
        ByteString.encodeUtf8(key + WebSocketProtocol.ACCEPT_MAGIC).sha1().base64();
    if (!acceptExpected.equals(headerAccept)) {
      throw new ProtocolException("Expected 'Sec-WebSocket-Accept' header value '"
          + acceptExpected
          + "' but was '"
          + headerAccept
          + "'");
    }

    return null;
  }
}
