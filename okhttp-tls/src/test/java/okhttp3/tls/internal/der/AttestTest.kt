package okhttp3.tls.internal.der

import okhttp3.tls.decodeCertificatePem
import okio.ByteString.Companion.toByteString
import org.junit.Test

class AttestTest {
  @Test
  fun parse() {
    // copy pem from okhttp.android.test.keystore.AttestTest
    val s = """
      -----BEGIN CERTIFICATE-----
      -----END CERTIFICATE-----
    """.trimIndent()

    val cert = s.decodeCertificatePem()

    println(cert)

    val cert2 = CertificateAdapters.certificate.fromDer(cert.encoded.toByteString())

    println(cert2.attestation)
  }
}
