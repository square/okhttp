/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.squareup.okhttp.internal.tls;

import java.security.Principal;
import java.security.cert.Certificate;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.security.cert.X509Certificate;

final class FakeSSLSession implements SSLSession {
  private final Certificate[] certificates;

  public FakeSSLSession(Certificate... certificates) throws Exception {
    this.certificates = certificates;
  }

  public int getApplicationBufferSize() {
    throw new UnsupportedOperationException();
  }

  public String getCipherSuite() {
    throw new UnsupportedOperationException();
  }

  public long getCreationTime() {
    throw new UnsupportedOperationException();
  }

  public byte[] getId() {
    throw new UnsupportedOperationException();
  }

  public long getLastAccessedTime() {
    throw new UnsupportedOperationException();
  }

  public Certificate[] getLocalCertificates() {
    throw new UnsupportedOperationException();
  }

  public Principal getLocalPrincipal() {
    throw new UnsupportedOperationException();
  }

  public int getPacketBufferSize() {
    throw new UnsupportedOperationException();
  }

  public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
    if (certificates.length == 0) {
      throw new SSLPeerUnverifiedException("peer not authenticated");
    } else {
      return certificates;
    }
  }

  public X509Certificate[] getPeerCertificateChain() throws SSLPeerUnverifiedException {
    throw new UnsupportedOperationException();
  }

  public String getPeerHost() {
    throw new UnsupportedOperationException();
  }

  public int getPeerPort() {
    throw new UnsupportedOperationException();
  }

  public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
    throw new UnsupportedOperationException();
  }

  public String getProtocol() {
    throw new UnsupportedOperationException();
  }

  public SSLSessionContext getSessionContext() {
    throw new UnsupportedOperationException();
  }

  public void putValue(String s, Object obj) {
    throw new UnsupportedOperationException();
  }

  public void removeValue(String s) {
    throw new UnsupportedOperationException();
  }

  public Object getValue(String s) {
    throw new UnsupportedOperationException();
  }

  public String[] getValueNames() {
    throw new UnsupportedOperationException();
  }

  public void invalidate() {
    throw new UnsupportedOperationException();
  }

  public boolean isValid() {
    throw new UnsupportedOperationException();
  }
}
