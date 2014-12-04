package com.squareup.okhttp.recipes;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.internal.ws.WebSocket;
import com.squareup.okhttp.internal.ws.WebSocketCall;
import com.squareup.okhttp.internal.ws.WebSocketListener;
import java.io.IOException;
import okio.Buffer;
import okio.BufferedSource;

import static com.squareup.okhttp.internal.ws.WebSocket.PayloadType;
import static com.squareup.okhttp.internal.ws.WebSocket.PayloadType.BINARY;
import static com.squareup.okhttp.internal.ws.WebSocket.PayloadType.TEXT;

/**
 * WARNING: This recipe is for an API that is not final and subject to change at any time!
 */
public final class WebSocketEcho implements WebSocketListener {
  private void run() throws IOException {
    OkHttpClient client = new OkHttpClient();

    Request request = new Request.Builder()
        .url("ws://echo.websocket.org")
        .build();
    WebSocketCall call = WebSocketCall.newWebSocketCall(client, request);
    WebSocket webSocket = call.execute(this);

    webSocket.sendMessage(TEXT, new Buffer().writeUtf8("Hello..."));
    webSocket.sendMessage(TEXT, new Buffer().writeUtf8("...World!"));
    webSocket.sendMessage(BINARY, new Buffer().writeInt(0xdeadbeef));
    webSocket.close(1000, "Goodbye, World!");
  }

  @Override public void onMessage(BufferedSource payload, PayloadType type) throws IOException {
    switch (type) {
      case TEXT:
        System.out.println(payload.readUtf8());
        break;
      case BINARY:
        System.out.println(payload.readByteString().hex());
        break;
      default:
        throw new IllegalStateException("Unknown payload type: " + type);
    }
    payload.close();
  }

  @Override public void onClose(int code, String reason) {
    System.out.println("CLOSE: " + code + " " + reason);
  }

  @Override public void onFailure(IOException e) {
    e.printStackTrace();
  }

  public static void main(String... args) throws IOException {
    new WebSocketEcho().run();
  }
}
