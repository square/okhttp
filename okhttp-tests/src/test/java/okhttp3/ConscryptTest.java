package okhttp3;

import java.io.IOException;
import java.util.Arrays;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static okhttp3.internal.platform.PlatformTest.getPlatform;
import static org.junit.Assert.assertEquals;

// https://github.com/tlswg/tls13-spec/wiki/Implementations
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
        .cipherSuites(MANDATORY_CIPHER_SUITES)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build();

    return new OkHttpClient.Builder().connectionSpecs(Arrays.asList(spec)).build();
  }

  @Before
  public void setup() {
    Assume.assumeTrue(getPlatform().equals("conscrypt"));
  }

  @Test
  public void testTls2() throws IOException {
    Request request = new Request.Builder().url("https://mozilla.org/").build();

    Response response = client.newCall(request).execute();

    assertEquals(TlsVersion.TLS_1_2, response.handshake().tlsVersion());
    assertEquals(Protocol.HTTP_2, response.protocol());
  }

  @Test
  @Ignore
  public void testTls3() throws IOException {
    // Caused by: javax.net.ssl.SSLProtocolException: SSL handshake aborted: ssl=0x7fc31ae42b90: Failure in SSL library, usually a protocol error
    // error:1000042e:SSL routines:OPENSSL_internal:TLSV1_ALERT_PROTOCOL_VERSION (../ssl/tls_record.c:547 0x7fc31ad7c1d0:0x00000001)

    Request request = new Request.Builder().url("https://tls13.crypto.mozilla.org/").build();

    Response response = client.newCall(request).execute();

    assertEquals(TlsVersion.TLS_1_3, response.handshake().tlsVersion());
    assertEquals(Protocol.HTTP_2, response.protocol());
  }

  @Test
  public void testTls3BoringSSL() throws IOException {
    Request request = new Request.Builder().url("https://tls.ctf.network/").build();

    Response response = client.newCall(request).execute();

    // TODO why 1.2?
    assertEquals(TlsVersion.TLS_1_2, response.handshake().tlsVersion());
    assertEquals(Protocol.HTTP_1_1, response.protocol());
  }

  @Test
  public void testTls3OpenSSLNghttpx() throws IOException {
    Request request = new Request.Builder().url("https://nghttp2.org:13443/").build();

    Response response = client.newCall(request).execute();

    // TODO why 1.2?
    assertEquals(TlsVersion.TLS_1_2, response.handshake().tlsVersion());
    assertEquals(Protocol.HTTP_2, response.protocol());
  }

  @Test
  public void testTls3OpenSSLNginx() throws IOException {
    Request request = new Request.Builder().url("https://www.henrock.net/").build();

    Response response = client.newCall(request).execute();

    // TODO why 1.2?
    assertEquals(TlsVersion.TLS_1_2, response.handshake().tlsVersion());
    assertEquals(Protocol.HTTP_2, response.protocol());
  }
}
