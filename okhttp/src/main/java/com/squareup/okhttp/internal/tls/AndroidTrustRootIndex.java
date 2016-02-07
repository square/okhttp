/*
 * Copyright (C) 2016 Square, Inc.
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
package com.squareup.okhttp.internal.tls;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

/**
 * A index of trusted root certificates that exploits knowledge of Android implementation details.
 * This class is potentially much faster to initialize than {@link RealTrustRootIndex} because
 * it doesn't need to load and index trusted CA certificates.
 */
public final class AndroidTrustRootIndex implements TrustRootIndex {
  private final X509TrustManager trustManager;
  private final Method findByIssuerAndSignatureMethod;

  public AndroidTrustRootIndex(
      X509TrustManager trustManager, Method findByIssuerAndSignatureMethod) {
    this.findByIssuerAndSignatureMethod = findByIssuerAndSignatureMethod;
    this.trustManager = trustManager;
  }

  @Override public X509Certificate findByIssuerAndSignature(X509Certificate cert) {
    try {
      TrustAnchor trustAnchor = (TrustAnchor) findByIssuerAndSignatureMethod.invoke(
          trustManager, cert);
      return trustAnchor.getTrustedCert();
    } catch (IllegalAccessException e) {
      throw new AssertionError();
    } catch (InvocationTargetException e) {
      return null;
    }
  }

  public static TrustRootIndex get(X509TrustManager trustManager) {
    // From org.conscrypt.TrustManagerImpl, we want the method with this signature:
    // private TrustAnchor findTrustAnchorByIssuerAndSignature(X509Certificate lastCert);
    try {
      Method method = trustManager.getClass().getDeclaredMethod(
          "findTrustAnchorByIssuerAndSignature", X509Certificate.class);
      method.setAccessible(true);
      return new AndroidTrustRootIndex(trustManager, method);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }
}
