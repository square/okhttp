/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.OkResponseCache;
import com.squareup.okhttp.ResponseSource;
import com.squareup.okhttp.internal.Base64;
import com.squareup.okhttp.internal.DiskLruCache;
import com.squareup.okhttp.internal.StrictLineReader;
import com.squareup.okhttp.internal.Util;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.net.SecureCacheResponse;
import java.net.URI;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;

import static com.squareup.okhttp.internal.Util.US_ASCII;
import static com.squareup.okhttp.internal.Util.UTF_8;

/**
 * Cache responses in a directory on the file system. Most clients should use
 * {@code android.net.HttpResponseCache}, the stable, documented front end for
 * this.
 */
public final class HttpResponseCache extends ResponseCache implements OkResponseCache {
  private static final char[] DIGITS =
      { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

  // TODO: add APIs to iterate the cache?
  private static final int VERSION = 201105;
  private static final int ENTRY_METADATA = 0;
  private static final int ENTRY_BODY = 1;
  private static final int ENTRY_COUNT = 2;

  private final DiskLruCache cache;

  /* read and write statistics, all guarded by 'this' */
  private int writeSuccessCount;
  private int writeAbortCount;
  private int networkCount;
  private int hitCount;
  private int requestCount;

  public HttpResponseCache(File directory, long maxSize) throws IOException {
    cache = DiskLruCache.open(directory, VERSION, ENTRY_COUNT, maxSize);
  }

  private String uriToKey(URI uri) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("MD5");
      byte[] md5bytes = messageDigest.digest(uri.toString().getBytes("UTF-8"));
      return bytesToHexString(md5bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  private static String bytesToHexString(byte[] bytes) {
    char[] digits = DIGITS;
    char[] buf = new char[bytes.length * 2];
    int c = 0;
    for (byte b : bytes) {
      buf[c++] = digits[(b >> 4) & 0xf];
      buf[c++] = digits[b & 0xf];
    }
    return new String(buf);
  }

  @Override public CacheResponse get(URI uri, String requestMethod,
      Map<String, List<String>> requestHeaders) {
    String key = uriToKey(uri);
    DiskLruCache.Snapshot snapshot;
    Entry entry;
    try {
      snapshot = cache.get(key);
      if (snapshot == null) {
        return null;
      }
      entry = new Entry(snapshot.getInputStream(ENTRY_METADATA));
    } catch (IOException e) {
      // Give up because the cache cannot be read.
      return null;
    }

    if (!entry.matches(uri, requestMethod, requestHeaders)) {
      snapshot.close();
      return null;
    }

    return entry.isHttps() ? new EntrySecureCacheResponse(entry, snapshot)
        : new EntryCacheResponse(entry, snapshot);
  }

  @Override public CacheRequest put(URI uri, URLConnection urlConnection) throws IOException {
    if (!(urlConnection instanceof HttpURLConnection)) {
      return null;
    }

    HttpURLConnection httpConnection = (HttpURLConnection) urlConnection;
    String requestMethod = httpConnection.getRequestMethod();
    String key = uriToKey(uri);

    if (requestMethod.equals("POST") || requestMethod.equals("PUT") || requestMethod.equals(
        "DELETE")) {
      try {
        cache.remove(key);
      } catch (IOException ignored) {
        // The cache cannot be written.
      }
      return null;
    } else if (!requestMethod.equals("GET")) {
      // Don't cache non-GET responses. We're technically allowed to cache
      // HEAD requests and some POST requests, but the complexity of doing
      // so is high and the benefit is low.
      return null;
    }

    HttpEngine httpEngine = getHttpEngine(httpConnection);
    if (httpEngine == null) {
      // Don't cache unless the HTTP implementation is ours.
      return null;
    }

    ResponseHeaders response = httpEngine.getResponseHeaders();
    if (response.hasVaryAll()) {
      return null;
    }

    RawHeaders varyHeaders =
        httpEngine.getRequestHeaders().getHeaders().getAll(response.getVaryFields());
    Entry entry = new Entry(uri, varyHeaders, httpConnection);
    DiskLruCache.Editor editor = null;
    try {
      editor = cache.edit(key);
      if (editor == null) {
        return null;
      }
      entry.writeTo(editor);
      return new CacheRequestImpl(editor);
    } catch (IOException e) {
      abortQuietly(editor);
      return null;
    }
  }

  /**
   * Handles a conditional request hit by updating the stored cache response
   * with the headers from {@code httpConnection}. The cached response body is
   * not updated. If the stored response has changed since {@code
   * conditionalCacheHit} was returned, this does nothing.
   */
  @Override public void update(CacheResponse conditionalCacheHit, HttpURLConnection httpConnection)
      throws IOException {
    HttpEngine httpEngine = getHttpEngine(httpConnection);
    URI uri = httpEngine.getUri();
    ResponseHeaders response = httpEngine.getResponseHeaders();
    RawHeaders varyHeaders =
        httpEngine.getRequestHeaders().getHeaders().getAll(response.getVaryFields());
    Entry entry = new Entry(uri, varyHeaders, httpConnection);
    DiskLruCache.Snapshot snapshot = (conditionalCacheHit instanceof EntryCacheResponse)
        ? ((EntryCacheResponse) conditionalCacheHit).snapshot
        : ((EntrySecureCacheResponse) conditionalCacheHit).snapshot;
    DiskLruCache.Editor editor = null;
    try {
      editor = snapshot.edit(); // returns null if snapshot is not current
      if (editor != null) {
        entry.writeTo(editor);
        editor.commit();
      }
    } catch (IOException e) {
      abortQuietly(editor);
    }
  }

  private void abortQuietly(DiskLruCache.Editor editor) {
    // Give up because the cache cannot be written.
    try {
      if (editor != null) {
        editor.abort();
      }
    } catch (IOException ignored) {
    }
  }

  private HttpEngine getHttpEngine(URLConnection httpConnection) {
    if (httpConnection instanceof HttpURLConnectionImpl) {
      return ((HttpURLConnectionImpl) httpConnection).getHttpEngine();
    } else if (httpConnection instanceof HttpsURLConnectionImpl) {
      return ((HttpsURLConnectionImpl) httpConnection).getHttpEngine();
    } else {
      return null;
    }
  }

  public DiskLruCache getCache() {
    return cache;
  }

  public synchronized int getWriteAbortCount() {
    return writeAbortCount;
  }

  public synchronized int getWriteSuccessCount() {
    return writeSuccessCount;
  }

  public synchronized void trackResponse(ResponseSource source) {
    requestCount++;

    switch (source) {
      case CACHE:
        hitCount++;
        break;
      case CONDITIONAL_CACHE:
      case NETWORK:
        networkCount++;
        break;
    }
  }

  public synchronized void trackConditionalCacheHit() {
    hitCount++;
  }

  public synchronized int getNetworkCount() {
    return networkCount;
  }

  public synchronized int getHitCount() {
    return hitCount;
  }

  public synchronized int getRequestCount() {
    return requestCount;
  }

  private final class CacheRequestImpl extends CacheRequest {
    private final DiskLruCache.Editor editor;
    private OutputStream cacheOut;
    private boolean done;
    private OutputStream body;

    public CacheRequestImpl(final DiskLruCache.Editor editor) throws IOException {
      this.editor = editor;
      this.cacheOut = editor.newOutputStream(ENTRY_BODY);
      this.body = new FilterOutputStream(cacheOut) {
        @Override public void close() throws IOException {
          synchronized (HttpResponseCache.this) {
            if (done) {
              return;
            }
            done = true;
            writeSuccessCount++;
          }
          super.close();
          editor.commit();
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
          // Since we don't override "write(int oneByte)", we can write directly to "out"
          // and avoid the inefficient implementation from the FilterOutputStream.
          out.write(buffer, offset, length);
        }
      };
    }

    @Override public void abort() {
      synchronized (HttpResponseCache.this) {
        if (done) {
          return;
        }
        done = true;
        writeAbortCount++;
      }
      Util.closeQuietly(cacheOut);
      try {
        editor.abort();
      } catch (IOException ignored) {
      }
    }

    @Override public OutputStream getBody() throws IOException {
      return body;
    }
  }

  private static final class Entry {
    private final String uri;
    private final RawHeaders varyHeaders;
    private final String requestMethod;
    private final RawHeaders responseHeaders;
    private final String cipherSuite;
    private final Certificate[] peerCertificates;
    private final Certificate[] localCertificates;

    /**
     * Reads an entry from an input stream. A typical entry looks like this:
     * <pre>{@code
     *   http://google.com/foo
     *   GET
     *   2
     *   Accept-Language: fr-CA
     *   Accept-Charset: UTF-8
     *   HTTP/1.1 200 OK
     *   3
     *   Content-Type: image/png
     *   Content-Length: 100
     *   Cache-Control: max-age=600
     * }</pre>
     *
     * <p>A typical HTTPS file looks like this:
     * <pre>{@code
     *   https://google.com/foo
     *   GET
     *   2
     *   Accept-Language: fr-CA
     *   Accept-Charset: UTF-8
     *   HTTP/1.1 200 OK
     *   3
     *   Content-Type: image/png
     *   Content-Length: 100
     *   Cache-Control: max-age=600
     *
     *   AES_256_WITH_MD5
     *   2
     *   base64-encoded peerCertificate[0]
     *   base64-encoded peerCertificate[1]
     *   -1
     * }</pre>
     * The file is newline separated. The first two lines are the URL and
     * the request method. Next is the number of HTTP Vary request header
     * lines, followed by those lines.
     *
     * <p>Next is the response status line, followed by the number of HTTP
     * response header lines, followed by those lines.
     *
     * <p>HTTPS responses also contain SSL session information. This begins
     * with a blank line, and then a line containing the cipher suite. Next
     * is the length of the peer certificate chain. These certificates are
     * base64-encoded and appear each on their own line. The next line
     * contains the length of the local certificate chain. These
     * certificates are also base64-encoded and appear each on their own
     * line. A length of -1 is used to encode a null array.
     */
    public Entry(InputStream in) throws IOException {
      try {
        StrictLineReader reader = new StrictLineReader(in, US_ASCII);
        uri = reader.readLine();
        requestMethod = reader.readLine();
        varyHeaders = new RawHeaders();
        int varyRequestHeaderLineCount = reader.readInt();
        for (int i = 0; i < varyRequestHeaderLineCount; i++) {
          varyHeaders.addLine(reader.readLine());
        }

        responseHeaders = new RawHeaders();
        responseHeaders.setStatusLine(reader.readLine());
        int responseHeaderLineCount = reader.readInt();
        for (int i = 0; i < responseHeaderLineCount; i++) {
          responseHeaders.addLine(reader.readLine());
        }

        if (isHttps()) {
          String blank = reader.readLine();
          if (!blank.isEmpty()) {
            throw new IOException("expected \"\" but was \"" + blank + "\"");
          }
          cipherSuite = reader.readLine();
          peerCertificates = readCertArray(reader);
          localCertificates = readCertArray(reader);
        } else {
          cipherSuite = null;
          peerCertificates = null;
          localCertificates = null;
        }
      } finally {
        in.close();
      }
    }

    public Entry(URI uri, RawHeaders varyHeaders, HttpURLConnection httpConnection)
        throws IOException {
      this.uri = uri.toString();
      this.varyHeaders = varyHeaders;
      this.requestMethod = httpConnection.getRequestMethod();
      this.responseHeaders = RawHeaders.fromMultimap(httpConnection.getHeaderFields(), true);

      if (isHttps()) {
        HttpsURLConnection httpsConnection = (HttpsURLConnection) httpConnection;
        cipherSuite = httpsConnection.getCipherSuite();
        Certificate[] peerCertificatesNonFinal = null;
        try {
          peerCertificatesNonFinal = httpsConnection.getServerCertificates();
        } catch (SSLPeerUnverifiedException ignored) {
        }
        peerCertificates = peerCertificatesNonFinal;
        localCertificates = httpsConnection.getLocalCertificates();
      } else {
        cipherSuite = null;
        peerCertificates = null;
        localCertificates = null;
      }
    }

    public void writeTo(DiskLruCache.Editor editor) throws IOException {
      OutputStream out = editor.newOutputStream(ENTRY_METADATA);
      Writer writer = new BufferedWriter(new OutputStreamWriter(out, UTF_8));

      writer.write(uri + '\n');
      writer.write(requestMethod + '\n');
      writer.write(Integer.toString(varyHeaders.length()) + '\n');
      for (int i = 0; i < varyHeaders.length(); i++) {
        writer.write(varyHeaders.getFieldName(i) + ": " + varyHeaders.getValue(i) + '\n');
      }

      writer.write(responseHeaders.getStatusLine() + '\n');
      writer.write(Integer.toString(responseHeaders.length()) + '\n');
      for (int i = 0; i < responseHeaders.length(); i++) {
        writer.write(responseHeaders.getFieldName(i) + ": " + responseHeaders.getValue(i) + '\n');
      }

      if (isHttps()) {
        writer.write('\n');
        writer.write(cipherSuite + '\n');
        writeCertArray(writer, peerCertificates);
        writeCertArray(writer, localCertificates);
      }
      writer.close();
    }

    private boolean isHttps() {
      return uri.startsWith("https://");
    }

    private Certificate[] readCertArray(StrictLineReader reader) throws IOException {
      int length = reader.readInt();
      if (length == -1) {
        return null;
      }
      try {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        Certificate[] result = new Certificate[length];
        for (int i = 0; i < result.length; i++) {
          String line = reader.readLine();
          byte[] bytes = Base64.decode(line.getBytes("US-ASCII"));
          result[i] = certificateFactory.generateCertificate(new ByteArrayInputStream(bytes));
        }
        return result;
      } catch (CertificateException e) {
        throw new IOException(e);
      }
    }

    private void writeCertArray(Writer writer, Certificate[] certificates) throws IOException {
      if (certificates == null) {
        writer.write("-1\n");
        return;
      }
      try {
        writer.write(Integer.toString(certificates.length) + '\n');
        for (Certificate certificate : certificates) {
          byte[] bytes = certificate.getEncoded();
          String line = Base64.encode(bytes);
          writer.write(line + '\n');
        }
      } catch (CertificateEncodingException e) {
        throw new IOException(e);
      }
    }

    public boolean matches(URI uri, String requestMethod,
        Map<String, List<String>> requestHeaders) {
      return this.uri.equals(uri.toString())
          && this.requestMethod.equals(requestMethod)
          && new ResponseHeaders(uri, responseHeaders).varyMatches(varyHeaders.toMultimap(false),
          requestHeaders);
    }
  }

  /**
   * Returns an input stream that reads the body of a snapshot, closing the
   * snapshot when the stream is closed.
   */
  private static InputStream newBodyInputStream(final DiskLruCache.Snapshot snapshot) {
    return new FilterInputStream(snapshot.getInputStream(ENTRY_BODY)) {
      @Override public void close() throws IOException {
        snapshot.close();
        super.close();
      }
    };
  }

  static class EntryCacheResponse extends CacheResponse {
    private final Entry entry;
    private final DiskLruCache.Snapshot snapshot;
    private final InputStream in;

    public EntryCacheResponse(Entry entry, DiskLruCache.Snapshot snapshot) {
      this.entry = entry;
      this.snapshot = snapshot;
      this.in = newBodyInputStream(snapshot);
    }

    @Override public Map<String, List<String>> getHeaders() {
      return entry.responseHeaders.toMultimap(true);
    }

    @Override public InputStream getBody() {
      return in;
    }
  }

  static class EntrySecureCacheResponse extends SecureCacheResponse {
    private final Entry entry;
    private final DiskLruCache.Snapshot snapshot;
    private final InputStream in;

    public EntrySecureCacheResponse(Entry entry, DiskLruCache.Snapshot snapshot) {
      this.entry = entry;
      this.snapshot = snapshot;
      this.in = newBodyInputStream(snapshot);
    }

    @Override public Map<String, List<String>> getHeaders() {
      return entry.responseHeaders.toMultimap(true);
    }

    @Override public InputStream getBody() {
      return in;
    }

    @Override public String getCipherSuite() {
      return entry.cipherSuite;
    }

    @Override public List<Certificate> getServerCertificateChain()
        throws SSLPeerUnverifiedException {
      if (entry.peerCertificates == null || entry.peerCertificates.length == 0) {
        throw new SSLPeerUnverifiedException(null);
      }
      return Arrays.asList(entry.peerCertificates.clone());
    }

    @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
      if (entry.peerCertificates == null || entry.peerCertificates.length == 0) {
        throw new SSLPeerUnverifiedException(null);
      }
      return ((X509Certificate) entry.peerCertificates[0]).getSubjectX500Principal();
    }

    @Override public List<Certificate> getLocalCertificateChain() {
      if (entry.localCertificates == null || entry.localCertificates.length == 0) {
        return null;
      }
      return Arrays.asList(entry.localCertificates.clone());
    }

    @Override public Principal getLocalPrincipal() {
      if (entry.localCertificates == null || entry.localCertificates.length == 0) {
        return null;
      }
      return ((X509Certificate) entry.localCertificates[0]).getSubjectX500Principal();
    }
  }
}
