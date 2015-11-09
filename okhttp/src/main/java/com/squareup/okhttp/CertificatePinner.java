package com.squareup.okhttp;

import java.security.cert.Certificate;
import java.util.List;

import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Applies a certain type of certificate pinning.
 */
public interface CertificatePinner {
      void check(String hostname, List<Certificate> peerCertificates)
          throws SSLPeerUnverifiedException;
}
