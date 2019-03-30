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
package okhttp3;

import java.security.Principal;
import java.security.cert.Certificate;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.security.cert.X509Certificate;

public final class FakeSSLSession implements SSLSession {
  private final Certificate[] certificates;

  public FakeSSLSession(Certificate... certificates) throws Exception {
    this.certificates = certificates;
  }

  @Override
  public int getApplicationBufferSize() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getCipherSuite() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getCreationTime() {
    throw new UnsupportedOperationException();
  }

  @Override
  public byte[] getId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getLastAccessedTime() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Certificate[] getLocalCertificates() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Principal getLocalPrincipal() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getPacketBufferSize() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
    if (certificates.length == 0) {
      throw new SSLPeerUnverifiedException("peer not authenticated");
    } else {
      return certificates;
    }
  }

  @Override
  public X509Certificate[] getPeerCertificateChain() throws SSLPeerUnverifiedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getPeerHost() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getPeerPort() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getProtocol() {
    throw new UnsupportedOperationException();
  }

  @Override
  public SSLSessionContext getSessionContext() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putValue(String s, Object obj) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeValue(String s) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object getValue(String s) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String[] getValueNames() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void invalidate() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isValid() {
    throw new UnsupportedOperationException();
  }
}
