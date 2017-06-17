package okhttp3;

import java.io.IOException;
import java.util.Arrays;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static okhttp3.internal.platform.PlatformTest.getPlatform;
import static org.junit.Assert.assertEquals;

public class ConscryptTest {
  public static final CipherSuite[] MANDATORY_CIPHER_SUITES = new CipherSuite[] {
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
      CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
  };

  private OkHttpClient client = buildClient();

  private OkHttpClient buildClient() {
    ConnectionSpec spec = new ConnectionSpec.Builder(true)
        .cipherSuites(MANDATORY_CIPHER_SUITES) // Check we are using strong ciphers
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2) // and modern TLS
        .supportsTlsExtensions(true)
        .build();

    return new OkHttpClient.Builder().connectionSpecs(Arrays.asList(spec)).build();
  }

  @Before
  public void setup() {
    Assume.assumeTrue(getPlatform().equals("conscrypt"));
  }

  @Test
  public void testMozilla() throws IOException {
    Request request = new Request.Builder().url("https://mozilla.org/robots.txt").build();

    Response response = client.newCall(request).execute();

    assertEquals(Protocol.HTTP_2, response.protocol());
  }

  @Test
  public void testGoogle() throws IOException {
    Request request = new Request.Builder().url("https://google.com/robots.txt").build();

    Response response = client.newCall(request).execute();

    assertEquals(Protocol.HTTP_2, response.protocol());
  }

  @Test
  @Ignore
  public void testNullSession() throws Exception {
    // TODO test against a null session
    Handshake.get(new FakeSSLSession());
  }
}
