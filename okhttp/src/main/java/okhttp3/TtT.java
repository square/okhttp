package okhttp3;

import java.io.IOException;
import java.util.Collections;
import okhttp3.internal.platform.Platform;

public class TtT {
  public static void main(String[] args) throws IOException {
    ConnectionSpec modernTls = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build();
    OkHttpClient client = new OkHttpClient.Builder().connectionSpecs(
        Collections.singletonList(modernTls)).build();

    Request request = new Request.Builder().url("https://www.howsmyssl.com/a/check").build();

    Response response = client.newCall(request).execute();

    try {
      System.out.println(response.protocol());
      System.out.println(response.handshake().cipherSuite());
      System.out.println(response.handshake().tlsVersion());
      System.out.println(Platform.get());

      System.out.println(response.body.string());
    } finally {
      response.close();
    }
  }
}
