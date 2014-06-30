package com.squareup.okhttp.recipes;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.WebSocket;
import com.squareup.okhttp.WebSocketListener;
import java.io.IOException;
import okio.Buffer;
import okio.BufferedSource;

import static com.squareup.okhttp.WebSocket.PayloadType;
import static com.squareup.okhttp.WebSocket.PayloadType.BINARY;
import static com.squareup.okhttp.WebSocket.PayloadType.TEXT;

public final class WebSocketEcho implements WebSocketListener {
  private void run() throws IOException {
    OkHttpClient client = new OkHttpClient();

    Request request = new Request.Builder()
        .url("ws://echo.websocket.org")
        .build();
    WebSocket webSocket = client.newWebSocket(request);
    Response response = webSocket.connect(this);
    if (response.code() != 101) {
      System.err.println("Unable to connect: " + response.code() + " " + response.message());
      System.err.println(response.body().string());
      return;
    }

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
