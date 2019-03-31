package okhttp3;

import java.io.IOException;
import java.security.Security;
import java.util.List;
import okhttp3.internal.platform.Platform;
import org.conscrypt.Conscrypt;

import static java.util.Arrays.asList;

public class TestTls13Request {

  // TLS 1.3
  private static final CipherSuite[] TLS13_CIPHER_SUITES = new CipherSuite[] {
      CipherSuite.TLS_AES_128_GCM_SHA256,
      CipherSuite.TLS_AES_256_GCM_SHA384,
      CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
      CipherSuite.TLS_AES_128_CCM_SHA256,
      CipherSuite.TLS_AES_128_CCM_8_SHA256
  };

  /**
   * A TLS 1.3 only Connection Spec. This will be eventually be exposed
   * as part of MODERN_TLS or folded into the default OkHttp client once published and
   * available in JDK11 or Conscrypt.
   */
  private static final ConnectionSpec TLS_13 = new ConnectionSpec.Builder(true)
      .cipherSuites(TLS13_CIPHER_SUITES)
      .tlsVersions(TlsVersion.TLS_1_3)
      .build();


  private static final ConnectionSpec TLS_12 =
      new ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_2)
          .build();

  private TestTls13Request() {
  }

  public static void main(String[] args) {
    //System.setProperty("javax.net.debug", "ssl:handshake:verbose");
    Security.insertProviderAt(Conscrypt.newProviderBuilder().provideTrustManager().build(), 1);

    System.out.println(
        "Running tests using " + Platform.get() + " " + System.getProperty("java.vm.version"));

    // https://github.com/tlswg/tls13-spec/wiki/Implementations
    List<String> urls =
        asList("https://enabled.tls13.com", "https://www.howsmyssl.com/a/check",
            "https://tls13.cloudflare.com", "https://www.allizom.org/robots.txt",
            "https://tls13.crypto.mozilla.org/", "https://tls.ctf.network/robots.txt",
            "https://rustls.jbp.io/", "https://h2o.examp1e.net", "https://mew.org/",
            "https://tls13.baishancloud.com/", "https://tls13.akamai.io/", "https://swifttls.org/",
            "https://www.googleapis.com/robots.txt", "https://graph.facebook.com/robots.txt",
            "https://api.twitter.com/robots.txt", "https://connect.squareup.com/robots.txt");

    System.out.println("TLS1.3+TLS1.2");
    testClient(urls, buildClient(ConnectionSpec.RESTRICTED_TLS));

    System.out.println("\nTLS1.3 only");
    testClient(urls, buildClient(TLS_13));

    System.out.println("\nTLS1.3 then fallback");
    testClient(urls, buildClient(TLS_13, TLS_12));
  }

  private static void testClient(List<String> urls, OkHttpClient client) {
    try {
      for (String url : urls) {
        sendRequest(client, url);
      }
    } finally {
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }
  }

  private static OkHttpClient buildClient(ConnectionSpec... specs) {
    return new OkHttpClient.Builder().connectionSpecs(asList(specs)).build();
  }

  private static void sendRequest(OkHttpClient client, String url) {
    System.out.printf("%-40s ", url);
    System.out.flush();

    System.out.println(Platform.get());

    Request request = new Request.Builder().url(url).build();

    try (Response response = client.newCall(request).execute()) {
      Handshake handshake = response.handshake();
      System.out.println(handshake.tlsVersion()
          + " "
          + handshake.cipherSuite()
          + " "
          + response.protocol()
          + " "
          + response.code()
          + " "
          + response.body().bytes().length
          + "b");
    } catch (IOException ioe) {
      System.out.println(ioe.toString());
    }
  }
}
