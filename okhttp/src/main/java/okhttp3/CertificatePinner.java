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
package okhttp3;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.internal.Util;
import okhttp3.internal.tls.CertificateAuthorityCouncil;
import okio.ByteString;

import static java.util.Collections.unmodifiableSet;

/**
 * Constrains which certificates are trusted. Pinning certificates defends against attacks on
 * certificate authorities. It also prevents connections through man-in-the-middle certificate
 * authorities either known or unknown to the application's user.
 *
 * <p>This class currently pins a certificate's Subject Public Key Info as described on <a
 * href="http://goo.gl/AIx3e5">Adam Langley's Weblog</a>. Pins are base-64 SHA-1 hashes, consistent
 * with the format Chromium uses for <a href="http://goo.gl/XDh6je">static certificates</a>. See
 * Chromium's <a href="http://goo.gl/4CCnGs">pinsets</a> for hostnames that are pinned in that
 * browser.
 *
 * <h3>Setting up Certificate Pinning</h3>
 *
 * <p>The easiest way to pin a host is turn on pinning with a broken configuration and read the
 * expected configuration when the connection fails. Be sure to do this on a trusted network, and
 * without man-in-the-middle tools like <a href="http://charlesproxy.com">Charles</a> or <a
 * href="http://fiddlertool.com">Fiddler</a>.
 *
 * <p>For example, to pin {@code https://publicobject.com}, start with a broken
 * configuration: <pre>   {@code
 *
 *     String hostname = "publicobject.com";
 *     CertificatePinner certificatePinner = new CertificatePinner.Builder()
 *         .add(hostname, "sha1/AAAAAAAAAAAAAAAAAAAAAAAAAAA=")
 *         .build();
 *     OkHttpClient client = new OkHttpClient();
 *     client.setCertificatePinner(certificatePinner);
 *
 *     Request request = new Request.Builder()
 *         .url("https://" + hostname)
 *         .build();
 *     client.newCall(request).execute();
 * }</pre>
 *
 * As expected, this fails with a certificate pinning exception: <pre>   {@code
 *
 * javax.net.ssl.SSLPeerUnverifiedException: Certificate pinning failure!
 *   Peer certificate chain:
 *     sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw=: CN=publicobject.com, OU=PositiveSSL
 *     sha1/SXxoaOSEzPC6BgGmxAt/EAcsajw=: CN=COMODO RSA Domain Validation Secure Server CA
 *     sha1/blhOM3W9V/bVQhsWAcLYwPU6n24=: CN=COMODO RSA Certification Authority
 *     sha1/T5x9IXmcrQ7YuQxXnxoCmeeQ84c=: CN=AddTrust External CA Root
 *   Pinned certificates for publicobject.com:
 *     sha1/AAAAAAAAAAAAAAAAAAAAAAAAAAA=
 *   at okhttp3.CertificatePinner.check(CertificatePinner.java)
 *   at okhttp3.Connection.upgradeToTls(Connection.java)
 *   at okhttp3.Connection.connect(Connection.java)
 *   at okhttp3.Connection.connectAndSetOwner(Connection.java)
 * }</pre>
 *
 * Follow up by pasting the public key hashes from the exception into the
 * certificate pinner's configuration: <pre>   {@code
 *
 *     CertificatePinner certificatePinner = new CertificatePinner.Builder()
 *       .add("publicobject.com", "sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw=")
 *       .add("publicobject.com", "sha1/SXxoaOSEzPC6BgGmxAt/EAcsajw=")
 *       .add("publicobject.com", "sha1/blhOM3W9V/bVQhsWAcLYwPU6n24=")
 *       .add("publicobject.com", "sha1/T5x9IXmcrQ7YuQxXnxoCmeeQ84c=")
 *       .build();
 * }</pre>
 *
 * Pinning is per-hostname and/or per-wildcard pattern. To pin both {@code publicobject.com} and
 * {@code www.publicobject.com}, you must configure both hostnames.
 *
 * <p>Wildcard pattern rules:
 * <ol>
 *     <li>Asterisk {@code *} is only permitted in the left-most domain name label and must be the
 *         only character in that label (i.e., must match the whole left-most label). For example,
 *         {@code *.example.com} is permitted, while {@code *a.example.com}, {@code a*.example.com},
 *         {@code a*b.example.com}, {@code a.*.example.com} are not permitted.
 *     <li>Asterisk {@code *} cannot match across domain name labels. For example,
 *         {@code *.example.com} matches {@code test.example.com} but does not match
 *         {@code sub.test.example.com}.
 *     <li>Wildcard patterns for single-label domain names are not permitted.
 * </ol>
 *
 * If hostname pinned directly and via wildcard pattern, both direct and wildcard pins will be used.
 * For example: {@code *.example.com} pinned with {@code pin1} and {@code a.example.com} pinned with
 * {@code pin2}, to check {@code a.example.com} both {@code pin1} and {@code pin2} will be used.
 *
 * <h3>Warning: Certificate Pinning is Dangerous!</h3>
 *
 * <p>Pinning certificates limits your server team's abilities to update their TLS certificates. By
 * pinning certificates, you take on additional operational complexity and limit your ability to
 * migrate between certificate authorities. Do not use certificate pinning without the blessing of
 * your server's TLS administrator!
 *
 * <h4>Note about self-signed certificates</h4>
 *
 * <p>{@link CertificatePinner} can not be used to pin self-signed certificate if such certificate
 * is not accepted by {@link javax.net.ssl.TrustManager}.
 *
 * @see <a href="https://www.owasp.org/index.php/Certificate_and_Public_Key_Pinning"> OWASP:
 * Certificate and Public Key Pinning</a>
 */
public final class CertificatePinner {
  public static final CertificatePinner DEFAULT = new Builder().build();

  private final Map<String, Set<ByteString>> hostnameToPins;
  private final CertificateAuthorityCouncil certificateAuthorityCouncil;

  private CertificatePinner(Builder builder) {
    this.hostnameToPins = Util.immutableMap(builder.hostnameToPins);
    this.certificateAuthorityCouncil = builder.certificateAuthorityCouncil;
  }

  /**
   * Confirms that at least one of the certificates pinned for {@code hostname} is in {@code
   * peerCertificates}. Does nothing if there are no certificates pinned for {@code hostname}.
   * OkHttp calls this after a successful TLS handshake, but before the connection is used.
   *
   * @throws SSLPeerUnverifiedException if {@code peerCertificates} don't match the certificates
   * pinned for {@code hostname}.
   */
  public void check(String hostname, List<Certificate> peerCertificates)
      throws SSLPeerUnverifiedException {
    if (certificateAuthorityCouncil != null) {
      peerCertificates = certificateAuthorityCouncil.normalizeCertificateChain(peerCertificates);
    }

    Set<ByteString> pins = findMatchingPins(hostname);

    if (pins == null) return;

    for (int i = 0, size = peerCertificates.size(); i < size; i++) {
      X509Certificate x509Certificate = (X509Certificate) peerCertificates.get(i);
      if (pins.contains(sha1(x509Certificate))) return; // Success!
    }

    // If we couldn't find a matching pin, format a nice exception.
    StringBuilder message = new StringBuilder()
        .append("Certificate pinning failure!")
        .append("\n  Peer certificate chain:");
    for (int i = 0, size = peerCertificates.size(); i < size; i++) {
      X509Certificate x509Certificate = (X509Certificate) peerCertificates.get(i);
      message.append("\n    ").append(pin(x509Certificate))
          .append(": ").append(x509Certificate.getSubjectDN().getName());
    }
    message.append("\n  Pinned certificates for ").append(hostname).append(":");
    for (ByteString pin : pins) {
      message.append("\n    sha1/").append(pin.base64());
    }
    throw new SSLPeerUnverifiedException(message.toString());
  }

  /** @deprecated replaced with {@link #check(String, List)}. */
  public void check(String hostname, Certificate... peerCertificates)
      throws SSLPeerUnverifiedException {
    check(hostname, Arrays.asList(peerCertificates));
  }

  /**
   * Returns list of matching certificates' pins for the hostname or {@code null} if hostname does
   * not have pinned certificates.
   */
  Set<ByteString> findMatchingPins(String hostname) {
    Set<ByteString> directPins = hostnameToPins.get(hostname);
    Set<ByteString> wildcardPins = null;

    int indexOfFirstDot = hostname.indexOf('.');
    int indexOfLastDot = hostname.lastIndexOf('.');

    // Skip hostnames with one dot symbol for wildcard pattern search
    //   example.com   will  be skipped
    //   a.example.com won't be skipped
    if (indexOfFirstDot != indexOfLastDot) {
      // a.example.com -> search for wildcard pattern *.example.com
      wildcardPins = hostnameToPins.get("*." + hostname.substring(indexOfFirstDot + 1));
    }

    if (directPins == null && wildcardPins == null) return null;

    if (directPins != null && wildcardPins != null) {
      Set<ByteString> pins = new LinkedHashSet<>();
      pins.addAll(directPins);
      pins.addAll(wildcardPins);
      return pins;
    }

    if (directPins != null) return directPins;

    return wildcardPins;
  }

  Builder newBuilder() {
    return new Builder(this);
  }

  /**
   * Returns the SHA-1 of {@code certificate}'s public key. This uses the mechanism Moxie
   * Marlinspike describes in <a href="https://github.com/moxie0/AndroidPinning">Android
   * Pinning</a>.
   */
  public static String pin(Certificate certificate) {
    if (!(certificate instanceof X509Certificate)) {
      throw new IllegalArgumentException("Certificate pinning requires X509 certificates");
    }
    return "sha1/" + sha1((X509Certificate) certificate).base64();
  }

  private static ByteString sha1(X509Certificate x509Certificate) {
    return Util.sha1(ByteString.of(x509Certificate.getPublicKey().getEncoded()));
  }

  /** Builds a configured certificate pinner. */
  public static final class Builder {
    private final Map<String, Set<ByteString>> hostnameToPins = new LinkedHashMap<>();
    private CertificateAuthorityCouncil certificateAuthorityCouncil;

    public Builder() {
    }

    Builder(CertificatePinner certificatePinner) {
      this.hostnameToPins.putAll(certificatePinner.hostnameToPins);
      this.certificateAuthorityCouncil = certificatePinner.certificateAuthorityCouncil;
    }

    Builder certificateAuthorityCouncil(CertificateAuthorityCouncil certificateAuthorityCouncil) {
      this.certificateAuthorityCouncil = certificateAuthorityCouncil;
      return this;
    }

    /**
     * Pins certificates for {@code hostname}.
     *
     * @param hostname lower-case host name or wildcard pattern such as {@code *.example.com}.
     * @param pins SHA-1 hashes. Each pin is a SHA-1 hash of a certificate's Subject Public Key
     * Info, base64-encoded and prefixed with {@code sha1/}.
     */
    public Builder add(String hostname, String... pins) {
      if (hostname == null) throw new IllegalArgumentException("hostname == null");

      Set<ByteString> hostPins = new LinkedHashSet<>();
      Set<ByteString> previousPins = hostnameToPins.put(hostname, unmodifiableSet(hostPins));
      if (previousPins != null) {
        hostPins.addAll(previousPins);
      }

      for (String pin : pins) {
        if (!pin.startsWith("sha1/")) {
          throw new IllegalArgumentException("pins must start with 'sha1/': " + pin);
        }
        ByteString decodedPin = ByteString.decodeBase64(pin.substring("sha1/".length()));
        if (decodedPin == null) {
          throw new IllegalArgumentException("pins must be base64: " + pin);
        }
        hostPins.add(decodedPin);
      }

      return this;
    }

    public CertificatePinner build() {
      return new CertificatePinner(this);
    }
  }
}
