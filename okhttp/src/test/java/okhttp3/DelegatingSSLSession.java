/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.security.Principal;
import java.security.cert.Certificate;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.security.cert.X509Certificate;

/** An {@link SSLSession} that delegates all calls. */
public abstract class DelegatingSSLSession implements SSLSession {
  protected final SSLSession delegate;

  public DelegatingSSLSession(SSLSession delegate) {
    this.delegate = delegate;
  }

  @Override public byte[] getId() {
    return delegate.getId();
  }

  @Override public SSLSessionContext getSessionContext() {
    return delegate.getSessionContext();
  }

  @Override public long getCreationTime() {
    return delegate.getCreationTime();
  }

  @Override public long getLastAccessedTime() {
    return delegate.getLastAccessedTime();
  }

  @Override public void invalidate() {
    delegate.invalidate();
  }

  @Override public boolean isValid() {
    return delegate.isValid();
  }

  @Override public void putValue(String s, Object o) {
    delegate.putValue(s, o);
  }

  @Override public Object getValue(String s) {
    return delegate.getValue(s);
  }

  @Override public void removeValue(String s) {
    delegate.removeValue(s);
  }

  @Override public String[] getValueNames() {
    return delegate.getValueNames();
  }

  @Override public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
    return delegate.getPeerCertificates();
  }

  @Override public Certificate[] getLocalCertificates() {
    return delegate.getLocalCertificates();
  }

  @Override public X509Certificate[] getPeerCertificateChain() throws SSLPeerUnverifiedException {
    return delegate.getPeerCertificateChain();
  }

  @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
    return delegate.getPeerPrincipal();
  }

  @Override public Principal getLocalPrincipal() {
    return delegate.getLocalPrincipal();
  }

  @Override public String getCipherSuite() {
    return delegate.getCipherSuite();
  }

  @Override public String getProtocol() {
    return delegate.getProtocol();
  }

  @Override public String getPeerHost() {
    return delegate.getPeerHost();
  }

  @Override public int getPeerPort() {
    return delegate.getPeerPort();
  }

  @Override public int getPacketBufferSize() {
    return delegate.getPacketBufferSize();
  }

  @Override public int getApplicationBufferSize() {
    return delegate.getApplicationBufferSize();
  }
}
