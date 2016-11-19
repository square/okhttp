package okhttp3.recipes;

import java.util.concurrent.TimeUnit;
import okhttp3.NewWebSocket;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.ByteString;

public final class WebSocketEcho extends NewWebSocket.Listener {
  private void run() {
    OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(0,  TimeUnit.MILLISECONDS)
        .build();

    Request request = new Request.Builder()
        .url("ws://echo.websocket.org")
        .build();
    client.newWebSocket(request, this);

    // Trigger shutdown of the dispatcher's executor so this process can exit cleanly.
    client.dispatcher().executorService().shutdown();
  }

  @Override public void onOpen(NewWebSocket webSocket, Response response) {
    webSocket.send("Hello...");
    webSocket.send("...World!");
    webSocket.send(ByteString.decodeHex("deadbeef"));
    webSocket.close(1000, "Goodbye, World!");
  }

  @Override public void onMessage(NewWebSocket webSocket, String text) {
    System.out.println("MESSAGE: " + text);
  }

  @Override public void onMessage(NewWebSocket webSocket, ByteString bytes) {
    System.out.println("MESSAGE: " + bytes.hex());
  }

  @Override public void onClosing(NewWebSocket webSocket, int code, String reason) {
    webSocket.close(1000, null);
    System.out.println("CLOSE: " + code + " " + reason);
  }

  @Override public void onFailure(NewWebSocket webSocket, Throwable t, Response response) {
    t.printStackTrace();
  }

  public static void main(String... args) {
    new WebSocketEcho().run();
  }
}
