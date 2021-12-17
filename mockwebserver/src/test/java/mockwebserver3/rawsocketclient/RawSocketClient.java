package mockwebserver3.rawsocketclient;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

import static java.net.InetAddress.getLoopbackAddress;
import static java.nio.charset.StandardCharsets.UTF_8;

public class RawSocketClient implements AutoCloseable {
  private static final int MAX_RESPONSE_CHUNK_SIZE_IN_BYTES = 1000;
  private static final int MAX_RESPONSE_LATENCY_IN_MS = 5000;

  private final Socket socket;

  public RawSocketClient(int port) throws IOException {
    this.socket = new Socket(getLoopbackAddress(), port);
    this.socket.setSoTimeout(MAX_RESPONSE_LATENCY_IN_MS);
  }

  public void sendInUtf8(String stringToSend) throws IOException {
    socket.getOutputStream().write(stringToSend.getBytes(UTF_8));
  }

  public String readResponseUtf8String() throws IOException {
    InputStream in = socket.getInputStream();
    byte[] array = new byte[MAX_RESPONSE_CHUNK_SIZE_IN_BYTES];
    int bytesRead = in.read(array);
    return new String(array, 0, bytesRead, UTF_8);
  }

  @Override
  public void close() throws Exception {
    socket.close();
  }
}
