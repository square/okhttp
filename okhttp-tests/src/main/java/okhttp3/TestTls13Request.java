package okhttp3;

import java.io.IOException;
import java.security.Security;
import java.util.Arrays;
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
            "https://tls13.cloudflare.com", "https://www.allizom.org/robots.txt",
            "https://tls13.crypto.mozilla.org/", "https://tls.ctf.network/",
            "https://rustls.jbp.io/", "https://h2o.examp1e.net", "https://mew.org/",
            "https://tls13.baishancloud.com/", "https://tls13.akamai.io/", "https://swifttls.org/");

    System.out.println("TLS1.3 only");
    testClient(urls, buildClient(ConnectionSpec.TLS_13));

    System.out.println("TLS1.3 then fallback");
    testClient(urls, buildClient(ConnectionSpec.TLS_13, ConnectionSpec.RESTRICTED_TLS));

    System.out.println("TLS1.3+TLS1.2");
    testClient(urls, buildClient(merge(ConnectionSpec.TLS_13, ConnectionSpec.RESTRICTED_TLS)));
  }

  private static ConnectionSpec merge(ConnectionSpec first, ConnectionSpec second) {
    String[] versions = concat(first.tlsVersions, second.tlsVersions);
    String[] cipherSuites = concat(first.cipherSuites, second.cipherSuites);

    return new ConnectionSpec.Builder(true).tlsVersions(versions)
        .cipherSuites(cipherSuites)
        .supportsTlsExtensions(true)
        .build();
  }

  private static String[] concat(String[] first, String[] second) {
    String[] result = new String[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static void testClient(List<String> urls, OkHttpClient client) {
    try {
      for (String url : urls) {
        sendRequest(client, url);
      }
    } finally {
      client.dispatcher.executorService().shutdownNow();
      client.connectionPool.evictAll();
    }
  }

  private static OkHttpClient buildClient(ConnectionSpec... specs) {
    return new OkHttpClient.Builder().connectionSpecs(Arrays.asList(specs)).build();
  }

  private static void sendRequest(OkHttpClient client, String url) {
    System.out.printf("%-40s ", url);
    System.out.flush();

    Request request = new Request.Builder().url(url).build();

    Response response = null;
    try {
      response = client.newCall(request).execute();

      Handshake handshake = response.handshake();
      System.out.println(handshake.tlsVersion()
          + " "
          + handshake.cipherSuite()
          + " "
          + response.protocol()
          + " "
          + response.code
          + " "
          + response.body.bytes().length
          + "b");
    } catch (IOException ioe) {
      System.out.println(ioe.toString());
    } finally {
      if (response != null) {
        response.close();
      }
    }
  }
}
