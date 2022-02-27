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
package okhttp3.internal.tls

import okhttp3.internal.platform.Platform
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/**
 * Computes the effective certificate chain from the raw array returned by Java's built in TLS APIs.
 * Cleaning a chain returns a list of certificates where the first element is `chain[0]`, each
 * certificate is signed by the certificate that follows, and the last certificate is a trusted CA
 * certificate.
 *
 * Use of the chain cleaner is necessary to omit unexpected certificates that aren't relevant to
 * the TLS handshake and to extract the trusted CA certificate for the benefit of certificate
 * pinning.
 */
abstract class CertificateChainCleaner {

  @Throws(SSLPeerUnverifiedException::class)
  abstract fun clean(chain: List<Certificate>, hostname: String): List<Certificate>

  companion object {
    fun get(trustManager: X509TrustManager): CertificateChainCleaner {
      return Platform.get().buildCertificateChainCleaner(trustManager)
    }

    fun get(vararg caCerts: X509Certificate): CertificateChainCleaner {
      return BasicCertificateChainCleaner(BasicTrustRootIndex(*caCerts))
    }
  }
}
