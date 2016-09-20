package okhttp3.recipes;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.Buffer;
import okio.ByteString;

import static okhttp3.WebSocket.BINARY;
import static okhttp3.WebSocket.TEXT;

public final class WebSocketEcho implements WebSocketListener {
  private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();

  private void run() {
    OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(0,  TimeUnit.MILLISECONDS)
        .build();

    Request request = new Request.Builder()
        .url("ws://echo.websocket.org")
        .build();
    client.newWebSocketCall(request).enqueue(this);

    // Trigger shutdown of the dispatcher's executor so this process can exit cleanly.
    client.dispatcher().executorService().shutdown();
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
    writeExecutor.shutdown();
  }

  @Override public void onFailure(Throwable t, Response response) {
    t.printStackTrace();
    writeExecutor.shutdown();
  }

  public static void main(String... args) {
    new WebSocketEcho().run();
  }
}
