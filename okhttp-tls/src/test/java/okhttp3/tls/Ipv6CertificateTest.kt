package okhttp3.tls

import java.io.File
import okhttp3.internal.tls.OkHostnameVerifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Ipv6CertificateTest {
  @Test
  fun x() {
    val certificatePem = File("src/test/resources/apiserver.crt").readText()
    val certificate = certificatePem.decodeCertificatePem()

    val verifier = OkHostnameVerifier

    println(certificate.subjectAlternativeNames)

    assertThat(verifier.verify("kubernetes.default", certificate)).isTrue()
    assertThat(verifier.verify("google.com", certificate)).isFalse()
    assertThat(verifier.verify("2001:1b70:82a7:1281:10:61:244:101", certificate)).isTrue()

    assertThat(verifier.verify("2007:db8:ffff:ccd:0:0:abcd:1", certificate)).isFalse()
    assertThat(verifier.verify("2007:db8:ffff:ccd::abcd:1", certificate)).isFalse()

    assertThat(verifier.verify("2001:db8:ffff:ccd:0:0:abcd:1", certificate)).isTrue()
    assertThat(verifier.verify("2001:db8:ffff:ccd::abcd:1", certificate)).isTrue()
  }
}
