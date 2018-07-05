package okhttp3;

import java.io.IOException;
import java.security.Security;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.internal.platform.Platform;
import org.conscrypt.Conscrypt;
import org.conscrypt.OpenSSLProvider;

public class TestTls13Request {
  private TestTls13Request() {
  }

  public static void main(String[] args) {
    String spec28 = Integer.toString(0x7f00 | 28, 16);
    System.setProperty("jdk.tls13.version", spec28);

    //System.setProperty("javax.net.debug", "ssl:handshake:verbose");
    OkHttpClient client =
        new OkHttpClient.Builder().connectionSpecs(Collections.singletonList(ConnectionSpec.TLS_13))
            .build();

    System.out.println("Running tests using "
        + Platform.get().getClass().getSimpleName()
        + " "
        + System.getProperty("java.vm.version"));

    // Allow for TLS_CHACHA20_POLY1305_SHA256 cipher suite
    if (Conscrypt.isAvailable()) {
      Security.addProvider(new OpenSSLProvider());
    }

    // https://github.com/tlswg/tls13-spec/wiki/Implementations
    List<String> urls =
        Arrays.asList("https://enabled.tls13.com", "https://www.howsmyssl.com/a/check",
            "https://tls13.cloudflare.com", "https://www.allizom.org/",
            "https://tls13.crypto.mozilla.org/", "https://tls.ctf.network/",
            "https://rustls.jbp.io/", "https://h2o.examp1e.net", "https://mew.org/",
            "https://tls13.baishancloud.com/", "https://tls13.akamai.io/", "https://swifttls.org/");

    try {
      for (String url : urls) {
        sendRequest(client, url);
      }
    } finally {
      client.dispatcher.executorService().shutdownNow();
      client.connectionPool.evictAll();
    }
  }

  private static void sendRequest(OkHttpClient client, String url) {
    System.out.println();
    System.out.println(url);

    Request request = new Request.Builder().url(url).build();

    Response response = null;
    try {
      response = client.newCall(request).execute();

      System.out.println(response.protocol());
      System.out.println(response.handshake().cipherSuite());
      System.out.println(response.handshake().tlsVersion());
      System.out.println(response.body.bytes().length);
    } catch (IOException ioe) {
      System.out.println(ioe.toString());
    } finally {
      if (response != null) {
        response.close();
      }
    }
  }
}
