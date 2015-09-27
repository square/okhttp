package com.squareup.okhttp.recipes;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.ws.WebSocket;
import com.squareup.okhttp.ws.WebSocketCall;
import com.squareup.okhttp.ws.WebSocketListener;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import okio.Buffer;
import okio.ByteString;

import static com.squareup.okhttp.ws.WebSocket.BINARY;
import static com.squareup.okhttp.ws.WebSocket.TEXT;

public final class WebSocketEcho implements WebSocketListener {
  private final Executor writeExecutor = Executors.newSingleThreadExecutor();

  private void run() throws IOException {
    OkHttpClient client = new OkHttpClient();

    Request request = new Request.Builder()
        .url("ws://echo.websocket.org")
        .build();
    WebSocketCall.create(client, request).enqueue(this);

    // Trigger shutdown of the dispatcher's executor so this process can exit cleanly.
    client.getDispatcher().getExecutorService().shutdown();
  }

  @Override public void onOpen(final WebSocket webSocket, Response response) {
    writeExecutor.execute(new Runnable() {
      @Override public void run() {
        try {
          webSocket.sendMessage(RequestBody.create(TEXT, "Hello..."));
          webSocket.sendMessage(RequestBody.create(TEXT, "...World!"));
          webSocket.sendMessage(RequestBody.create(BINARY, ByteString.decodeHex("deadbeef")));
          webSocket.close(1000, "Goodbye, World!");
        } catch (IOException e) {
          System.err.println("Unable to send messages: " + e.getMessage());
        }
      }
    });
  }

  @Override public void onMessage(ResponseBody message) throws IOException {
    if (message.contentType() == TEXT) {
      System.out.println("MESSAGE: " + message.string());
    } else {
      System.out.println("MESSAGE: " + message.source().readByteString().hex());
    }
    message.close();
  }

  @Override public void onPong(Buffer payload) {
    System.out.println("PONG: " + payload.readUtf8());
  }

  @Override public void onClose(int code, String reason) {
    System.out.println("CLOSE: " + code + " " + reason);
  }

  @Override public void onFailure(IOException e, Response response) {
    e.printStackTrace();
  }

  public static void main(String... args) throws IOException {
    new WebSocketEcho().run();
  }
}
