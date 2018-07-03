package okhttp3;

import java.io.IOException;
import java.util.Collections;
import okhttp3.internal.platform.Platform;

public class TestTls13Request {
  public static void main(String[] args) throws IOException {
    String spec28 = Integer.toString(0x7f00 | 28, 16);
    System.setProperty("jdk.tls13.version", spec28);

    //System.setProperty("javax.net.debug", "ssl:handshake:verbose");
    OkHttpClient client = new OkHttpClient.Builder().connectionSpecs(
        Collections.singletonList(ConnectionSpec.TLS_13)).build();

    Request request = new Request.Builder().url("https://enabled.tls13.com").build();

    Response response = client.newCall(request).execute();

    try {
      System.out.println(response.protocol());
      System.out.println(response.handshake().cipherSuite());
      System.out.println(response.handshake().tlsVersion());
      //System.out.println(Platform.get().get);

      System.out.println(response.body.string());
    } finally {
      response.close();
      client.dispatcher.executorService().shutdownNow();
      client.connectionPool.evictAll();
    }
  }
}
