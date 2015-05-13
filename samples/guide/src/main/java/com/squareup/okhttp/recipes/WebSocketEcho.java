package com.squareup.okhttp.recipes;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ws.WebSocket;
import com.squareup.okhttp.ws.WebSocketCall;
import com.squareup.okhttp.ws.WebSocketListener;
import java.io.IOException;
import okio.Buffer;
import okio.BufferedSource;

import static com.squareup.okhttp.ws.WebSocket.PayloadType;
import static com.squareup.okhttp.ws.WebSocket.PayloadType.BINARY;
import static com.squareup.okhttp.ws.WebSocket.PayloadType.TEXT;
import static com.squareup.okhttp.ws.WebSocket.UpgradeFailureReason;

public final class WebSocketEcho implements WebSocketListener {
  private void run() throws IOException {
    OkHttpClient client = new OkHttpClient();

    Request request = new Request.Builder().url("ws://echo.websocket.org").build();
    WebSocketCall.create(client, request).enqueue(this);

    // Trigger shutdown of the dispatcher's executor so this process can exit cleanly.
    client.getDispatcher().getExecutorService().shutdown();
  }

  @Override public void onOpen(WebSocket webSocket, Request request, Response response)
      throws IOException {
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

  @Override public void onPong(Buffer payload) {
    System.out.println("PONG: " + payload.readUtf8());
  }

  @Override public void onClose(int code, String reason) {
    System.out.println("CLOSE: " + code + " " + reason);
  }

  @Override public void onFailure(IOException e) {
    e.printStackTrace();
  }

  @Override
  public void onUpgradeFailed(UpgradeFailureReason reason, Request request, Response response) {
    System.out.println("UPGRADE FAILED: [" + reason + "] with response code: " + response.code());
  }

  public static void main(String... args) throws IOException {
    new WebSocketEcho().run();
  }
}
