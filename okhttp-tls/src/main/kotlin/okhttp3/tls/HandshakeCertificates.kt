/*
 * Copyright (C) 2012 Square, Inc.
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

package okhttp3.tls

import java.security.KeyStoreException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import okhttp3.CertificatePinner
import okhttp3.internal.platform.Platform
import okhttp3.internal.toImmutableList
import okhttp3.tls.internal.TlsUtil.newKeyManager
import okhttp3.tls.internal.TlsUtil.newTrustManager

/**
 * Certificates to identify which peers to trust and also to earn the trust of those peers in kind.
 * Client and server exchange these certificates during the handshake phase of a TLS connection.
 *
 * ### Server Authentication
 *
 * This is the most common form of TLS authentication: clients verify that servers are trusted and
 * that they own the hostnames that they represent. Server authentication is required.
 *
 * To perform server authentication:
 *
 *  * The server's handshake certificates must have a [held certificate][HeldCertificate] (a
 *    certificate and its private key). The certificate's subject alternative names must match the
 *    server's hostname. The server must also have is a (possibly-empty) chain of intermediate
 *    certificates to establish trust from a root certificate to the server's certificate. The root
 *    certificate is not included in this chain.
 *  * The client's handshake certificates must include a set of trusted root certificates. They will
 *    be used to authenticate the server's certificate chain. Typically this is a set of well-known
 *    root certificates that is distributed with the HTTP client or its platform. It may be
 *    augmented by certificates private to an organization or service.
 *
 * ### Client Authentication
 *
 * This is authentication of the client by the server during the TLS handshake. Client
 * authentication is optional.
 *
 * To perform client authentication:
 *
 *  * The client's handshake certificates must have a [held certificate][HeldCertificate] (a
 *    certificate and its private key). The client must also have a (possibly-empty) chain of
 *    intermediate certificates to establish trust from a root certificate to the client's
 *    certificate. The root certificate is not included in this chain.
 *  * The server's handshake certificates must include a set of trusted root certificates. They
 *    will be used to authenticate the client's certificate chain. Typically this is not the same
 *    set of root certificates used in server authentication. Instead it will be a small set of
 *    roots private to an organization or service.
 */
class HandshakeCertificates private constructor(
  @get:JvmName("keyManager") val keyManager: X509KeyManager,
  @get:JvmName("trustManager") val trustManager: X509TrustManager,
) {
  @JvmName("-deprecated_keyManager")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "keyManager"),
    level = DeprecationLevel.ERROR,
  )
  fun keyManager(): X509KeyManager = keyManager

  @JvmName("-deprecated_trustManager")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "trustManager"),
    level = DeprecationLevel.ERROR,
  )
  fun trustManager(): X509TrustManager = trustManager

  fun sslSocketFactory(): SSLSocketFactory = sslContext().socketFactory

  fun sslContext(): SSLContext =
    Platform.get().newSSLContext().apply {
      init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())
    }

  class Builder {
    private var heldCertificate: HeldCertificate? = null
    private var intermediates: Array<X509Certificate>? = null
    private val trustedCertificates = mutableListOf<X509Certificate>()
    private val insecureHosts = mutableListOf<String>()

    /**
     * Configure the certificate chain to use when being authenticated. The first certificate is
     * the held certificate, further certificates are included in the handshake so the peer can
     * build a trusted path to a trusted root certificate.
     *
     * The chain should include all intermediate certificates but does not need the root certificate
     * that we expect to be known by the remote peer. The peer already has that certificate so
     * transmitting it is unnecessary.
     */
    fun heldCertificate(
      heldCertificate: HeldCertificate,
      vararg intermediates: X509Certificate,
    ) = apply {
      this.heldCertificate = heldCertificate
      this.intermediates = arrayOf(*intermediates) // Defensive copy.
    }

    /**
     * Add a trusted root certificate to use when authenticating a peer. Peers must provide
     * a chain of certificates whose root is one of these.
     */
    fun addTrustedCertificate(certificate: X509Certificate) =
      apply {
        this.trustedCertificates += certificate
      }

    /**
     * Add all of the host platform's trusted root certificates. This set varies by platform
     * (Android vs. Java), by platform release (Android 4.4 vs. Android 9), and with user
     * customizations.
     *
     * Most TLS clients that connect to hosts on the public Internet should call this method.
     * Otherwise it is necessary to manually prepare a comprehensive set of trusted roots.
     *
     * If the host platform is compromised or misconfigured this may contain untrustworthy root
     * certificates. Applications that connect to a known set of servers may be able to mitigate
     * this problem with [certificate pinning][CertificatePinner].
     */
    fun addPlatformTrustedCertificates() =
      apply {
        val platformTrustManager = Platform.get().platformTrustManager()
        Collections.addAll(trustedCertificates, *platformTrustManager.acceptedIssuers)
      }

    /**
     * Configures this to not authenticate the HTTPS server on to [hostname]. This makes the user
     * vulnerable to man-in-the-middle attacks and should only be used only in private development
     * environments and only to carry test data.
     *
     * The server’s TLS certificate **does not need to be signed** by a trusted certificate
     * authority. Instead, it will trust any well-formed certificate, even if it is self-signed.
     * This is necessary for testing against localhost or in development environments where a
     * certificate authority is not possible.
     *
     * The server’s TLS certificate still must match the requested hostname. For example, if the
     * certificate is issued to `example.com` and the request is to `localhost`, the connection will
     * fail. Use a custom [HostnameVerifier] to ignore such problems.
     *
     * Other TLS features are still used but provide no security benefits in absence of the above
     * gaps. For example, an insecure TLS connection is capable of negotiating HTTP/2 with ALPN and
     * it also has a regular-looking handshake.
     *
     * **This feature is not supported on Android API levels less than 24.** Prior releases lacked
     * a mechanism to trust some hosts and not others.
     *
     * @param hostname the exact hostname from the URL for insecure connections.
     */
    fun addInsecureHost(hostname: String) =
      apply {
        insecureHosts += hostname
      }

    fun build(): HandshakeCertificates {
      val immutableInsecureHosts = insecureHosts.toImmutableList()

      val heldCertificate = heldCertificate
      if (heldCertificate != null && heldCertificate.keyPair.private.format == null) {
        throw KeyStoreException("unable to support unencodable private key")
      }

      val keyManager = newKeyManager(null, heldCertificate, *(intermediates ?: emptyArray()))
      val trustManager = newTrustManager(null, trustedCertificates, immutableInsecureHosts)
      return HandshakeCertificates(keyManager, trustManager)
    }
  }
}
