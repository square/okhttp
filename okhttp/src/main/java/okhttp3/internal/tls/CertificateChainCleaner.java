/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.tls;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.X509TrustManager;

/**
 * Computes the effective certificate chain from the raw array returned by Java's built in TLS APIs.
 * Cleaning a chain returns a list of certificates where the first element is {@code chain[0]}, each
 * certificate is signed by the certificate that follows, and the last certificate is a trusted CA
 * certificate.
 *
 * <p>Use of the chain cleaner is necessary to omit unexpected certificates that aren't relevant to
 * the TLS handshake and to extract the trusted CA certificate for the benefit of certificate
 * pinning.
 */
public abstract class CertificateChainCleaner {
  public abstract List<Certificate> clean(List<Certificate> chain, String hostname)
      throws SSLPeerUnverifiedException;

  public static CertificateChainCleaner get(X509TrustManager trustManager) {
    try {
      Class<?> extensionsClass = Class.forName("android.net.http.X509TrustManagerExtensions");
      Constructor<?> constructor = extensionsClass.getConstructor(X509TrustManager.class);
      Object extensions = constructor.newInstance(trustManager);
      Method checkServerTrusted = extensionsClass.getMethod(
          "checkServerTrusted", X509Certificate[].class, String.class, String.class);
      return new AndroidCertificateChainCleaner(extensions, checkServerTrusted);
    } catch (Exception e) {
      return new BasicCertificateChainCleaner(TrustRootIndex.get(trustManager));
    }
  }

  public static CertificateChainCleaner get(X509Certificate... caCerts) {
    return new BasicCertificateChainCleaner(TrustRootIndex.get(caCerts));
  }

  /**
   * A certificate chain cleaner that uses a set of trusted root certificates to build the trusted
   * chain. This class duplicates the clean chain building performed during the TLS handshake. We
   * prefer other mechanisms where they exist, such as with {@link AndroidCertificateChainCleaner}.
   *
   * <p>This class includes code from <a href="https://conscrypt.org/">Conscrypt's</a> {@code
   * TrustManagerImpl} and {@code TrustedCertificateIndex}.
   */
  static final class BasicCertificateChainCleaner extends CertificateChainCleaner {
    /** The maximum number of signers in a chain. We use 9 for consistency with OpenSSL. */
    private static final int MAX_SIGNERS = 9;

    private final TrustRootIndex trustRootIndex;

    public BasicCertificateChainCleaner(TrustRootIndex trustRootIndex) {
      this.trustRootIndex = trustRootIndex;
    }

    /**
     * Returns a cleaned chain for {@code chain}.
     *
     * <p>This method throws if the complete chain to a trusted CA certificate cannot be
     * constructed. This is unexpected unless the trust root index in this class has a different
     * trust manager than what was used to establish {@code chain}.
     */
    @Override public List<Certificate> clean(List<Certificate> chain, String hostname)
        throws SSLPeerUnverifiedException {
      Deque<Certificate> queue = new ArrayDeque<>(chain);
      List<Certificate> result = new ArrayList<>();
      result.add(queue.removeFirst());
      boolean foundTrustedCertificate = false;

      followIssuerChain:
      for (int c = 0; c < MAX_SIGNERS; c++) {
        X509Certificate toVerify = (X509Certificate) result.get(result.size() - 1);

        // If this cert has been signed by a trusted cert, use that. Add the trusted certificate to
        // the end of the chain unless it's already present. (That would happen if the first
        // certificate in the chain is itself a self-signed and trusted CA certificate.)
        X509Certificate trustedCert = trustRootIndex.findByIssuerAndSignature(toVerify);
        if (trustedCert != null) {
          if (result.size() > 1 || !toVerify.equals(trustedCert)) {
            result.add(trustedCert);
          }
          if (verifySignature(trustedCert, trustedCert)) {
            return result; // The self-signed cert is a root CA. We're done.
          }
          foundTrustedCertificate = true;
          continue;
        }

        // Search for the certificate in the chain that signed this certificate. This is typically
        // the next element in the chain, but it could be any element.
        for (Iterator<Certificate> i = queue.iterator(); i.hasNext(); ) {
          X509Certificate signingCert = (X509Certificate) i.next();
          if (verifySignature(toVerify, signingCert)) {
            i.remove();
            result.add(signingCert);
            continue followIssuerChain;
          }
        }

        // We've reached the end of the chain. If any cert in the chain is trusted, we're done.
        if (foundTrustedCertificate) {
          return result;
        }

        // The last link isn't trusted. Fail.
        throw new SSLPeerUnverifiedException(
            "Failed to find a trusted cert that signed " + toVerify);
      }

      throw new SSLPeerUnverifiedException("Certificate chain too long: " + result);
    }

    /** Returns true if {@code toVerify} was signed by {@code signingCert}'s public key. */
    private boolean verifySignature(X509Certificate toVerify, X509Certificate signingCert) {
      if (!toVerify.getIssuerDN().equals(signingCert.getSubjectDN())) return false;
      try {
        toVerify.verify(signingCert.getPublicKey());
        return true;
      } catch (GeneralSecurityException verifyFailed) {
        return false;
      }
    }
  }

  /**
   * X509TrustManagerExtensions was added to Android in API 17 (Android 4.2, released in late 2012).
   * This is the best way to get a clean chain on Android because it uses the same code as the TLS
   * handshake.
   */
  static final class AndroidCertificateChainCleaner extends CertificateChainCleaner {
    private final Object x509TrustManagerExtensions;
    private final Method checkServerTrusted;

    AndroidCertificateChainCleaner(Object x509TrustManagerExtensions, Method checkServerTrusted) {
      this.x509TrustManagerExtensions = x509TrustManagerExtensions;
      this.checkServerTrusted = checkServerTrusted;
    }

    @SuppressWarnings({"unchecked", "SuspiciousToArrayCall"}) // Reflection on List<Certificate>.
    @Override public List<Certificate> clean(List<Certificate> chain, String hostname)
        throws SSLPeerUnverifiedException {
      try {
        X509Certificate[] certificates = chain.toArray(new X509Certificate[chain.size()]);
        return (List<Certificate>) checkServerTrusted.invoke(
            x509TrustManagerExtensions, certificates, "RSA", hostname);
      } catch (InvocationTargetException e) {
        SSLPeerUnverifiedException exception = new SSLPeerUnverifiedException(e.getMessage());
        exception.initCause(e);
        throw exception;
      } catch (IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
  }
}
