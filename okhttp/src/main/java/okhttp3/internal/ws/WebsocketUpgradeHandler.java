package okhttp3.internal.ws;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.internal.Internal;
import okhttp3.internal.connection.StreamAllocation;
import okio.ByteString;

import static okhttp3.internal.Util.closeQuietly;

public class WebsocketUpgradeHandler implements UpgradeHandler<WebSocket> {
  private static final List<Protocol> ONLY_HTTP1 = Collections.singletonList(Protocol.HTTP_1_1);

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

  void checkResponse(Response response) throws ProtocolException {
    if (response.code() != 101) {
      throw new ProtocolException("Expected HTTP 101 response but was '"
          + response.code()
          + " "
          + response.message()
          + "'");
    }

    String headerConnection = response.header("Connection");
    if (!"Upgrade".equalsIgnoreCase(headerConnection)) {
      throw new ProtocolException(
          "Expected 'Connection' header value 'Upgrade' but was '" + headerConnection + "'");
    }

    String headerUpgrade = response.header("Upgrade");
    if (!"websocket".equalsIgnoreCase(headerUpgrade)) {
      throw new ProtocolException(
          "Expected 'Upgrade' header value 'websocket' but was '" + headerUpgrade + "'");
    }

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
  }

  public WebSocket connect(OkHttpClient client) {
    client = client.newBuilder().eventListener(EventListener.NONE).protocols(ONLY_HTTP1).build();
    final Request request = addUpgradeHeaders(originalRequest.newBuilder()
        .header("Upgrade", "websocket")
        .header("Connection", "Upgrade")
        .build());
    Call call = Internal.instance.newWebSocketCall(client, request);
    call.enqueue(new Callback() {
      @Override public void onResponse(Call call, Response response) {
        try {
          checkResponse(response);
        } catch (ProtocolException e) {
          webSocket.failWebSocket(e, response);
          closeQuietly(response);
          return;
        }

        // Promote the HTTP streams into web socket streams.
        StreamAllocation streamAllocation = Internal.instance.streamAllocation(call);
        streamAllocation.noNewStreams(); // Prevent connection pooling!
        Streams streams = streamAllocation.connection().newWebSocketStreams(streamAllocation);

        // Process all web socket messages.
        try {
          listener.onOpen(webSocket, response);
          String name = "OkHttp WebSocket " + request.url().redact();
          webSocket.initReaderAndWriter(name, streams);
          streamAllocation.connection().socket().setSoTimeout(0);
          webSocket.loopReader();
        } catch (Exception e) {
          webSocket.failWebSocket(e, null);
        }
      }

      @Override public void onFailure(Call call, IOException e) {
        webSocket.failWebSocket(e, null);
      }
    });

    return webSocket;
  }

  @Override public boolean supportsHttp11() {
    return true;
  }

  @Override public boolean supportsHttp2() {
    return false;
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

  @Override public void failConnect(Response response, IOException e) throws IOException {
    listener.onFailure(webSocket, e, response);
    throw e;
  }
}
