/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.tls.CertificateChainCleaner
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Constrains which certificates are trusted. Pinning certificates defends against attacks on
 * certificate authorities. It also prevents connections through man-in-the-middle certificate
 * authorities either known or unknown to the application's user.
 * This class currently pins a certificate's Subject Public Key Info as described on
 * [Adam Langley's Weblog][langley]. Pins are either base64 SHA-256 hashes as in
 * [HTTP Public Key Pinning (HPKP)][rfc_7469] or SHA-1 base64 hashes as in Chromium's
 * [static certificates][static_certificates].
 *
 * ## Setting up Certificate Pinning
 *
 * The easiest way to pin a host is turn on pinning with a broken configuration and read the
 * expected configuration when the connection fails. Be sure to do this on a trusted network, and
 * without man-in-the-middle tools like [Charles][charles] or [Fiddler][fiddler].
 *
 * For example, to pin `https://publicobject.com`, start with a broken configuration:
 *
 * ```
 * String hostname = "publicobject.com";
 * CertificatePinner certificatePinner = new CertificatePinner.Builder()
 *     .add(hostname, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
 *     .build();
 * OkHttpClient client = OkHttpClient.Builder()
 *     .certificatePinner(certificatePinner)
 *     .build();
 *
 * Request request = new Request.Builder()
 *     .url("https://" + hostname)
 *     .build();
 * client.newCall(request).execute();
 * ```
 *
 * As expected, this fails with a certificate pinning exception:
 *
 * ```
 * javax.net.ssl.SSLPeerUnverifiedException: Certificate pinning failure!
 * Peer certificate chain:
 *     sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=: CN=publicobject.com, OU=PositiveSSL
 *     sha256/klO23nT2ehFDXCfx3eHTDRESMz3asj1muO+4aIdjiuY=: CN=COMODO RSA Secure Server CA
 *     sha256/grX4Ta9HpZx6tSHkmCrvpApTQGo67CYDnvprLg5yRME=: CN=COMODO RSA Certification Authority
 *     sha256/lCppFqbkrlJ3EcVFAkeip0+44VaoJUymbnOaEUk7tEU=: CN=AddTrust External CA Root
 * Pinned certificates for publicobject.com:
 *     sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
 *   at okhttp3.CertificatePinner.check(CertificatePinner.java)
 *   at okhttp3.Connection.upgradeToTls(Connection.java)
 *   at okhttp3.Connection.connect(Connection.java)
 *   at okhttp3.Connection.connectAndSetOwner(Connection.java)
 * ```
 *
 * Follow up by pasting the public key hashes from the exception into the
 * certificate pinner's configuration:
 *
 * ```
 * CertificatePinner certificatePinner = new CertificatePinner.Builder()
 *     .add("publicobject.com", "sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
 *     .add("publicobject.com", "sha256/klO23nT2ehFDXCfx3eHTDRESMz3asj1muO+4aIdjiuY=")
 *     .add("publicobject.com", "sha256/grX4Ta9HpZx6tSHkmCrvpApTQGo67CYDnvprLg5yRME=")
 *     .add("publicobject.com", "sha256/lCppFqbkrlJ3EcVFAkeip0+44VaoJUymbnOaEUk7tEU=")
 *     .build();
 * ```
 *
 * Pinning is per-hostname and/or per-wildcard pattern. To pin both `publicobject.com` and
 * `www.publicobject.com`, you must configure both hostnames.
 *
 * Wildcard pattern rules:
 *
 *  1. Asterisk `*` is only permitted in the left-most domain name label and must be the only
 *     character in that label (i.e., must match the whole left-most label). For example,
 *     `*.example.com` is permitted, while `*a.example.com`, `a*.example.com`, `a*b.example.com`,
 *     `a.*.example.com` are not permitted.
 *  2. Asterisk `*` cannot match across domain name labels. For example, `*.example.com` matches
 *     `test.example.com` but does not match `sub.test.example.com`.
 *  3. Wildcard patterns for single-label domain names are not permitted.
 *
 * If hostname pinned directly and via wildcard pattern, both direct and wildcard pins will be used.
 * For example: `*.example.com` pinned with `pin1` and `a.example.com` pinned with `pin2`, to check
 * `a.example.com` both `pin1` and `pin2` will be used.
 *
 * ## Warning: Certificate Pinning is Dangerous!
 *
 * Pinning certificates limits your server team's abilities to update their TLS certificates. By
 * pinning certificates, you take on additional operational complexity and limit your ability to
 * migrate between certificate authorities. Do not use certificate pinning without the blessing of
 * your server's TLS administrator!
 *
 * ### Note about self-signed certificates
 *
 * [CertificatePinner] can not be used to pin self-signed certificate if such certificate is not
 * accepted by [javax.net.ssl.TrustManager].
 *
 * See also [OWASP: Certificate and Public Key Pinning][owasp].
 *
 * [charles]: http://charlesproxy.com
 * [fiddler]: http://fiddlertool.com
 * [langley]: http://goo.gl/AIx3e5
 * [owasp]: https://www.owasp.org/index.php/Certificate_and_Public_Key_Pinning
 * [rfc_7469]: http://tools.ietf.org/html/rfc7469
 * [static_certificates]: http://goo.gl/XDh6je
 */
@Suppress("NAME_SHADOWING")
class CertificatePinner internal constructor(
  private val pins: Set<Pin>,
  internal val certificateChainCleaner: CertificateChainCleaner?
) {
  /**
   * Confirms that at least one of the certificates pinned for `hostname` is in `peerCertificates`.
   * Does nothing if there are no certificates pinned for `hostname`. OkHttp calls this after a
   * successful TLS handshake, but before the connection is used.
   *
   * @throws SSLPeerUnverifiedException if `peerCertificates` don't match the certificates pinned
   *     for `hostname`.
   */
  @Throws(SSLPeerUnverifiedException::class)
  fun check(hostname: String, peerCertificates: List<Certificate>) {
    return check(hostname) {
      (certificateChainCleaner?.clean(peerCertificates, hostname) ?: peerCertificates)
          .map { it as X509Certificate }
    }
  }

  internal fun check(hostname: String, cleanedPeerCertificatesFn: () -> List<X509Certificate>) {
    val pins = findMatchingPins(hostname)
    if (pins.isEmpty()) return

    val peerCertificates = cleanedPeerCertificatesFn()

    for (peerCertificate in peerCertificates) {
      // Lazily compute the hashes for each certificate.
      var sha1: ByteString? = null
      var sha256: ByteString? = null

      for (pin in pins) {
        when (pin.hashAlgorithm) {
          "sha256/" -> {
            if (sha256 == null) sha256 = peerCertificate.toSha256ByteString()
            if (pin.hash == sha256) return // Success!
          }
          "sha1/" -> {
            if (sha1 == null) sha1 = peerCertificate.toSha1ByteString()
            if (pin.hash == sha1) return // Success!
          }
          else -> throw AssertionError("unsupported hashAlgorithm: ${pin.hashAlgorithm}")
        }
      }
    }

    // If we couldn't find a matching pin, format a nice exception.
    val message = buildString {
      append("Certificate pinning failure!")
      append("\n  Peer certificate chain:")
      for (element in peerCertificates) {
        val x509Certificate = element as X509Certificate
        append("\n    ")
        append(pin(x509Certificate))
        append(": ")
        append(x509Certificate.subjectDN.name)
      }
      append("\n  Pinned certificates for ")
      append(hostname)
      append(":")
      for (pin in pins) {
        append("\n    ")
        append(pin)
      }
    }
    throw SSLPeerUnverifiedException(message)
  }

  @Deprecated(
      "replaced with {@link #check(String, List)}.",
      ReplaceWith("check(hostname, peerCertificates.toList())")
  )
  @Throws(SSLPeerUnverifiedException::class)
  fun check(hostname: String, vararg peerCertificates: Certificate) {
    check(hostname, peerCertificates.toList())
  }

  /**
   * Returns list of matching certificates' pins for the hostname. Returns an empty list if the
   * hostname does not have pinned certificates.
   */
  internal fun findMatchingPins(hostname: String): List<Pin> {
    var result: List<Pin> = emptyList()
    for (pin in pins) {
      if (pin.matches(hostname)) {
        if (result.isEmpty()) result = mutableListOf()
        (result as MutableList<Pin>).add(pin)
      }
    }
    return result
  }

  /** Returns a certificate pinner that uses `certificateChainCleaner`. */
  internal fun withCertificateChainCleaner(
    certificateChainCleaner: CertificateChainCleaner?
  ): CertificatePinner {
    return if (this.certificateChainCleaner == certificateChainCleaner) {
      this
    } else {
      CertificatePinner(pins, certificateChainCleaner)
    }
  }

  override fun equals(other: Any?): Boolean {
    return other is CertificatePinner &&
        other.pins == pins &&
        other.certificateChainCleaner == certificateChainCleaner
  }

  override fun hashCode(): Int {
    var result = 37
    result = 41 * result + pins.hashCode()
    result = 41 * result + certificateChainCleaner.hashCode()
    return result
  }

  internal data class Pin(
    /** A hostname like `example.com` or a pattern like `*.example.com`. */
    val pattern: String,
    /** The canonical hostname, i.e. `EXAMPLE.com` becomes `example.com`. */
    private val canonicalHostname: String,
    /** Either `sha1/` or `sha256/`. */
    val hashAlgorithm: String,
    /** The hash of the pinned certificate using [hashAlgorithm]. */
    val hash: ByteString
  ) {
    fun matches(hostname: String): Boolean {
      if (pattern.startsWith(WILDCARD)) {
        val firstDot = hostname.indexOf('.')
        return hostname.length - firstDot - 1 == canonicalHostname.length &&
            hostname.startsWith(canonicalHostname, startIndex = firstDot + 1)
      }
      return hostname == canonicalHostname
    }

    override fun toString(): String = hashAlgorithm + hash.base64()
  }

  /** Builds a configured certificate pinner. */
  class Builder {
    private val pins = mutableListOf<Pin>()

    /**
     * Pins certificates for `pattern`.
     *
     * @param pattern lower-case host name or wildcard pattern such as `*.example.com`.
     * @param pins SHA-256 or SHA-1 hashes. Each pin is a hash of a certificate's Subject Public Key
     * Info, base64-encoded and prefixed with either `sha256/` or `sha1/`.
     */
    fun add(pattern: String, vararg pins: String) = apply {
      for (pin in pins) {
        this.pins.add(newPin(pattern, pin))
      }
    }

    fun build(): CertificatePinner = CertificatePinner(pins.toSet(), null)
  }

  companion object {
    internal const val WILDCARD = "*."

    @JvmField
    val DEFAULT = Builder().build()

    /**
     * Returns the SHA-256 of `certificate`'s public key.
     *
     * In OkHttp 3.1.2 and earlier, this returned a SHA-1 hash of the public key. Both types are
     * supported, but SHA-256 is preferred.
     */
    @JvmStatic
    fun pin(certificate: Certificate): String {
      require(certificate is X509Certificate) { "Certificate pinning requires X509 certificates" }
      return "sha256/${certificate.toSha256ByteString().base64()}"
    }

    internal fun X509Certificate.toSha1ByteString(): ByteString =
        publicKey.encoded.toByteString().sha1()

    internal fun X509Certificate.toSha256ByteString(): ByteString =
        publicKey.encoded.toByteString().sha256()

    internal fun newPin(pattern: String, pin: String): Pin {
      val canonicalHostname = when {
        pattern.startsWith(WILDCARD) -> {
          "http://${pattern.substring(WILDCARD.length)}".toHttpUrl().host
        }
        else -> {
          "http://$pattern".toHttpUrl().host
        }
      }

      return when {
        pin.startsWith("sha1/") -> {
          val hash = pin.substring("sha1/".length).decodeBase64()!!
          Pin(pattern, canonicalHostname, "sha1/", hash)
        }
        pin.startsWith("sha256/") -> {
          val hash = pin.substring("sha256/".length).decodeBase64()!!
          Pin(pattern, canonicalHostname, "sha256/", hash)
        }
        else -> throw IllegalArgumentException("pins must start with 'sha256/' or 'sha1/': $pin")
      }
    }
  }
}
