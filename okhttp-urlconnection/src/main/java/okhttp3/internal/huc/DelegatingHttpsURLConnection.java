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
package okhttp3.internal.huc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.security.Permission;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Handshake;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

/**
 * Implement an HTTPS connection by delegating to an HTTP connection for everything but the
 * HTTPS-specific stuff.
 */
abstract class DelegatingHttpsURLConnection extends HttpsURLConnection {
  private final HttpURLConnection delegate;

  DelegatingHttpsURLConnection(HttpURLConnection delegate) {
    super(delegate.getURL());
    this.delegate = delegate;
  }

  protected abstract Handshake handshake();

  @Override public abstract void setHostnameVerifier(HostnameVerifier hostnameVerifier);

  @Override public abstract HostnameVerifier getHostnameVerifier();

  @Override public abstract void setSSLSocketFactory(SSLSocketFactory sslSocketFactory);

  @Override public abstract SSLSocketFactory getSSLSocketFactory();

  @Override public String getCipherSuite() {
    Handshake handshake = handshake();
    return handshake != null ? handshake.cipherSuite().javaName() : null;
  }

  @Override public Certificate[] getLocalCertificates() {
    Handshake handshake = handshake();
    if (handshake == null) return null;
    List<Certificate> result = handshake.localCertificates();
    return !result.isEmpty() ? result.toArray(new Certificate[result.size()]) : null;
  }

  @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
    Handshake handshake = handshake();
    if (handshake == null) return null;
    List<Certificate> result = handshake.peerCertificates();
    return !result.isEmpty() ? result.toArray(new Certificate[result.size()]) : null;
  }

  @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
    Handshake handshake = handshake();
    return handshake != null ? handshake.peerPrincipal() : null;
  }

  @Override public Principal getLocalPrincipal() {
    Handshake handshake = handshake();
    return handshake != null ? handshake.localPrincipal() : null;
  }

  @Override public void connect() throws IOException {
    connected = true;
    delegate.connect();
  }

  @Override public void disconnect() {
    delegate.disconnect();
  }

  @Override public InputStream getErrorStream() {
    return delegate.getErrorStream();
  }

  @Override public String getRequestMethod() {
    return delegate.getRequestMethod();
  }

  @Override public int getResponseCode() throws IOException {
    return delegate.getResponseCode();
  }

  @Override public String getResponseMessage() throws IOException {
    return delegate.getResponseMessage();
  }

  @Override public void setRequestMethod(String method) throws ProtocolException {
    delegate.setRequestMethod(method);
  }

  @Override public boolean usingProxy() {
    return delegate.usingProxy();
  }

  @Override public boolean getInstanceFollowRedirects() {
    return delegate.getInstanceFollowRedirects();
  }

  @Override public void setInstanceFollowRedirects(boolean followRedirects) {
    delegate.setInstanceFollowRedirects(followRedirects);
  }

  @Override public boolean getAllowUserInteraction() {
    return delegate.getAllowUserInteraction();
  }

  @Override public Object getContent() throws IOException {
    return delegate.getContent();
  }

  @SuppressWarnings("unchecked") // Spec does not generify
  @Override public Object getContent(Class[] types) throws IOException {
    return delegate.getContent(types);
  }

  @Override public String getContentEncoding() {
    return delegate.getContentEncoding();
  }

  @Override public int getContentLength() {
    return delegate.getContentLength();
  }

  @IgnoreJRERequirement // Should only be invoked on Java 7+.
  @Override public long getContentLengthLong() {
    return delegate.getContentLengthLong();
  }

  @Override public String getContentType() {
    return delegate.getContentType();
  }

  @Override public long getDate() {
    return delegate.getDate();
  }

  @Override public boolean getDefaultUseCaches() {
    return delegate.getDefaultUseCaches();
  }

  @Override public boolean getDoInput() {
    return delegate.getDoInput();
  }

  @Override public boolean getDoOutput() {
    return delegate.getDoOutput();
  }

  @Override public long getExpiration() {
    return delegate.getExpiration();
  }

  @Override public String getHeaderField(int pos) {
    return delegate.getHeaderField(pos);
  }

  @Override public Map<String, List<String>> getHeaderFields() {
    return delegate.getHeaderFields();
  }

  @Override public Map<String, List<String>> getRequestProperties() {
    return delegate.getRequestProperties();
  }

  @Override public void addRequestProperty(String field, String newValue) {
    delegate.addRequestProperty(field, newValue);
  }

  @Override public String getHeaderField(String key) {
    return delegate.getHeaderField(key);
  }

  @IgnoreJRERequirement // Should only be invoked on Java 7+.
  @Override public long getHeaderFieldLong(String field, long defaultValue) {
    return delegate.getHeaderFieldLong(field, defaultValue);
  }

  @Override public long getHeaderFieldDate(String field, long defaultValue) {
    return delegate.getHeaderFieldDate(field, defaultValue);
  }

  @Override public int getHeaderFieldInt(String field, int defaultValue) {
    return delegate.getHeaderFieldInt(field, defaultValue);
  }

  @Override public String getHeaderFieldKey(int position) {
    return delegate.getHeaderFieldKey(position);
  }

  @Override public long getIfModifiedSince() {
    return delegate.getIfModifiedSince();
  }

  @Override public InputStream getInputStream() throws IOException {
    return delegate.getInputStream();
  }

  @Override public long getLastModified() {
    return delegate.getLastModified();
  }

  @Override public OutputStream getOutputStream() throws IOException {
    return delegate.getOutputStream();
  }

  @Override public Permission getPermission() throws IOException {
    return delegate.getPermission();
  }

  @Override public String getRequestProperty(String field) {
    return delegate.getRequestProperty(field);
  }

  @Override public URL getURL() {
    return delegate.getURL();
  }

  @Override public boolean getUseCaches() {
    return delegate.getUseCaches();
  }

  @Override public void setAllowUserInteraction(boolean newValue) {
    delegate.setAllowUserInteraction(newValue);
  }

  @Override public void setDefaultUseCaches(boolean newValue) {
    delegate.setDefaultUseCaches(newValue);
  }

  @Override public void setDoInput(boolean newValue) {
    delegate.setDoInput(newValue);
  }

  @Override public void setDoOutput(boolean newValue) {
    delegate.setDoOutput(newValue);
  }

  @IgnoreJRERequirement // Should only be invoked on Java 7+.
  @Override public void setFixedLengthStreamingMode(long contentLength) {
    delegate.setFixedLengthStreamingMode(contentLength);
  }

  @Override public void setIfModifiedSince(long newValue) {
    delegate.setIfModifiedSince(newValue);
  }

  @Override public void setRequestProperty(String field, String newValue) {
    delegate.setRequestProperty(field, newValue);
  }

  @Override public void setUseCaches(boolean newValue) {
    delegate.setUseCaches(newValue);
  }

  @Override public void setConnectTimeout(int timeoutMillis) {
    delegate.setConnectTimeout(timeoutMillis);
  }

  @Override public int getConnectTimeout() {
    return delegate.getConnectTimeout();
  }

  @Override public void setReadTimeout(int timeoutMillis) {
    delegate.setReadTimeout(timeoutMillis);
  }

  @Override public int getReadTimeout() {
    return delegate.getReadTimeout();
  }

  @Override public String toString() {
    return delegate.toString();
  }

  @Override public void setFixedLengthStreamingMode(int contentLength) {
    delegate.setFixedLengthStreamingMode(contentLength);
  }

  @Override public void setChunkedStreamingMode(int chunkLength) {
    delegate.setChunkedStreamingMode(chunkLength);
  }
}
