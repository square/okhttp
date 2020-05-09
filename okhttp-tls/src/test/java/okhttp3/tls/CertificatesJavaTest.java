package okhttp3.tls;

import java.security.cert.X509Certificate;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CertificatesJavaTest {
  @Test
  public void testRoundtrip() {
    String certificateString = ""
      + "-----BEGIN CERTIFICATE-----\n"
      + "MIIBmjCCAQOgAwIBAgIBATANBgkqhkiG9w0BAQsFADATMREwDwYDVQQDEwhjYXNo\n"
      + "LmFwcDAeFw03MDAxMDEwMDAwMDBaFw03MDAxMDEwMDAwMDFaMBMxETAPBgNVBAMT\n"
      + "CGNhc2guYXBwMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCApFHhtrLan28q\n"
      + "+oMolZuaTfWBA0V5aMIvq32BsloQu6LlvX1wJ4YEoUCjDlPOtpht7XLbUmBnbIzN\n"
      + "89XK4UJVM6Sqp3K88Km8z7gMrdrfTom/274wL25fICR+yDEQ5fUVYBmJAKXZF1ao\n"
      + "I0mIoEx0xFsQhIJ637v2MxJDupd61wIDAQABMA0GCSqGSIb3DQEBCwUAA4GBADam\n"
      + "UVwKh5Ry7es3OxtY3IgQunPUoLc0Gw71gl9Z+7t2FJ5VkcI5gWfutmdxZ2bDXCI8\n"
      + "8V0vxo1pHXnbBrnxhS/Z3TBerw8RyQqcaWOdp+pBXyIWmR+jHk9cHZCqQveTIBsY\n"
      + "jaA9VEhgdaVhxBsT2qzUNDsXlOzGsliznDfoqETb\n"
      + "-----END CERTIFICATE-----\n";

    X509Certificate certificate =
        Certificates.decodeCertificatePem(certificateString);

    assertEquals(certificateString, Certificates.certificatePem(certificate));
  }
}
