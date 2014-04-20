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

package com.squareup.okhttp;

import com.squareup.okhttp.internal.DiskLruCache;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.HttpMethod;
import com.squareup.okhttp.internal.http.HttpURLConnectionImpl;
import com.squareup.okhttp.internal.http.HttpsURLConnectionImpl;
import com.squareup.okhttp.internal.http.JavaApiConverter;
import com.squareup.okhttp.internal.http.OkHeaders;
import com.squareup.okhttp.internal.http.StatusLine;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URLConnection;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import okio.Source;
import okio.Timeout;

import static com.squareup.okhttp.internal.Util.UTF_8;

/**
 * Caches HTTP and HTTPS responses to the filesystem so they may be reused,
 * saving time and bandwidth.
 *
 * <p>This cache extends {@link ResponseCache} but is only intended for use
 * with OkHttp and is not a general-purpose implementation: The
 * {@link ResponseCache} API requires that the subclass handles cache-control
 * logic as well as storage. In OkHttp the {@link HttpResponseCache} only
 * handles cursory cache-control logic.
 *
 * <p>To maintain support for previous releases the {@link HttpResponseCache}
 * will disregard any {@link #put(java.net.URI, java.net.URLConnection)}
 * calls with a URLConnection that is not from OkHttp. It will, however,
 * return cached data for any calls to {@link #get(java.net.URI, String,
 * java.util.Map)}.
 *
 * <h3>Cache Optimization</h3>
 * To measure cache effectiveness, this class tracks three statistics:
 * <ul>
 *     <li><strong>{@linkplain #getRequestCount() Request Count:}</strong> the
 *         number of HTTP requests issued since this cache was created.
 *     <li><strong>{@linkplain #getNetworkCount() Network Count:}</strong> the
 *         number of those requests that required network use.
 *     <li><strong>{@linkplain #getHitCount() Hit Count:}</strong> the number of
 *         those requests whose responses were served by the cache.
 * </ul>
 * Sometimes a request will result in a conditional cache hit. If the cache
 * contains a stale copy of the response, the client will issue a conditional
 * {@code GET}. The server will then send either the updated response if it has
 * changed, or a short 'not modified' response if the client's copy is still
 * valid. Such responses increment both the network count and hit count.
 *
 * <p>The best way to improve the cache hit rate is by configuring the web
 * server to return cacheable responses. Although this client honors all <a
 * href="http://www.ietf.org/rfc/rfc2616.txt">HTTP/1.1 (RFC 2068)</a> cache
 * headers, it doesn't cache partial responses.
 *
 * <h3>Force a Network Response</h3>
 * In some situations, such as after a user clicks a 'refresh' button, it may be
 * necessary to skip the cache, and fetch data directly from the server. To force
 * a full refresh, add the {@code no-cache} directive: <pre>   {@code
 *         connection.addRequestProperty("Cache-Control", "no-cache");
 * }</pre>
 * If it is only necessary to force a cached response to be validated by the
 * server, use the more efficient {@code max-age=0} instead: <pre>   {@code
 *         connection.addRequestProperty("Cache-Control", "max-age=0");
 * }</pre>
 *
 * <h3>Force a Cache Response</h3>
 * Sometimes you'll want to show resources if they are available immediately,
 * but not otherwise. This can be used so your application can show
 * <i>something</i> while waiting for the latest data to be downloaded. To
 * restrict a request to locally-cached resources, add the {@code
 * only-if-cached} directive: <pre>   {@code
 *     try {
 *         connection.addRequestProperty("Cache-Control", "only-if-cached");
 *         InputStream cached = connection.getInputStream();
 *         // the resource was cached! show it
 *     } catch (FileNotFoundException e) {
 *         // the resource was not cached
 *     }
 * }</pre>
 * This technique works even better in situations where a stale response is
 * better than no response. To permit stale cached responses, use the {@code
 * max-stale} directive with the maximum staleness in seconds: <pre>   {@code
 *         int maxStale = 60 * 60 * 24 * 28; // tolerate 4-weeks stale
 *         connection.addRequestProperty("Cache-Control", "max-stale=" + maxStale);
 * }</pre>
 */
public final class HttpResponseCache extends ResponseCache implements OkResponseCache {
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

  @Override public CacheResponse get(
      URI uri, String requestMethod, Map<String, List<String>> requestHeaders)
      throws IOException {

    Request request = JavaApiConverter.createOkRequest(uri, requestMethod, requestHeaders);
    Response response = get(request);
    if (response == null) {
      return null;
    }
    return JavaApiConverter.createJavaCacheResponse(response);
  }

  private static String urlToKey(Request requst) {
    return Util.hash(requst.urlString());
  }

  @Override public Response get(Request request) {
    String key = urlToKey(request);
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

    Response response = entry.response(request, snapshot);

    if (!entry.matches(request, response)) {
      Util.closeQuietly(response.body());
      return null;
    }

    return response;
  }

  @Override public CacheRequest put(URI uri, URLConnection urlConnection) throws IOException {
    if (!isCacheableConnection(urlConnection)) {
      return null;
    }
    return put(JavaApiConverter.createOkResponse(uri, urlConnection));
  }

  private static boolean isCacheableConnection(URLConnection httpConnection) {
    return (httpConnection instanceof HttpURLConnectionImpl)
        || (httpConnection instanceof HttpsURLConnectionImpl);
  }

  @Override public CacheRequest put(Response response) throws IOException {
    String requestMethod = response.request().method();

    if (HttpMethod.invalidatesCache(response.request().method())) {
      try {
        remove(response.request());
      } catch (IOException ignored) {
        // The cache cannot be written.
      }
      return null;
    }
    if (!requestMethod.equals("GET")) {
      // Don't cache non-GET responses. We're technically allowed to cache
      // HEAD requests and some POST requests, but the complexity of doing
      // so is high and the benefit is low.
      return null;
    }

    if (OkHeaders.hasVaryAll(response)) {
      return null;
    }

    Entry entry = new Entry(response);
    DiskLruCache.Editor editor = null;
    try {
      editor = cache.edit(urlToKey(response.request()));
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

  @Override public void remove(Request request) throws IOException {
    cache.remove(urlToKey(request));
  }

  @Override public void update(Response cached, Response network) {
    Entry entry = new Entry(network);
    DiskLruCache.Snapshot snapshot = ((CacheResponseBody) cached.body()).snapshot;
    DiskLruCache.Editor editor = null;
    try {
      editor = snapshot.edit(); // Returns null if snapshot is not current.
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

  /**
   * Closes the cache and deletes all of its stored values. This will delete
   * all files in the cache directory including files that weren't created by
   * the cache.
   */
  public void delete() throws IOException {
    cache.delete();
  }

  public synchronized int getWriteAbortCount() {
    return writeAbortCount;
  }

  public synchronized int getWriteSuccessCount() {
    return writeSuccessCount;
  }

  public long getSize() {
    return cache.size();
  }

  public long getMaxSize() {
    return cache.getMaxSize();
  }

  public void flush() throws IOException {
    cache.flush();
  }

  public void close() throws IOException {
    cache.close();
  }

  public File getDirectory() {
    return cache.getDirectory();
  }

  public boolean isClosed() {
    return cache.isClosed();
  }

  @Override public synchronized void trackResponse(ResponseSource source) {
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

  @Override public synchronized void trackConditionalCacheHit() {
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

        @Override public void write(byte[] buffer, int offset, int length) throws IOException {
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
    private final String url;
    private final Headers varyHeaders;
    private final String requestMethod;
    private final Protocol protocol;
    private final int code;
    private final String message;
    private final Headers responseHeaders;
    private final Handshake handshake;

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
        BufferedSource source = Okio.buffer(Okio.source(in));
        url = source.readUtf8LineStrict();
        requestMethod = source.readUtf8LineStrict();
        Headers.Builder varyHeadersBuilder = new Headers.Builder();
        int varyRequestHeaderLineCount = readInt(source);
        for (int i = 0; i < varyRequestHeaderLineCount; i++) {
          varyHeadersBuilder.addLine(source.readUtf8LineStrict());
        }
        varyHeaders = varyHeadersBuilder.build();

        StatusLine statusLine = StatusLine.parse(source.readUtf8LineStrict());
        protocol = statusLine.protocol;
        code = statusLine.code;
        message = statusLine.message;
        Headers.Builder responseHeadersBuilder = new Headers.Builder();
        int responseHeaderLineCount = readInt(source);
        for (int i = 0; i < responseHeaderLineCount; i++) {
          responseHeadersBuilder.addLine(source.readUtf8LineStrict());
        }
        responseHeaders = responseHeadersBuilder.build();

        if (isHttps()) {
          String blank = source.readUtf8LineStrict();
          if (blank.length() > 0) {
            throw new IOException("expected \"\" but was \"" + blank + "\"");
          }
          String cipherSuite = source.readUtf8LineStrict();
          List<Certificate> peerCertificates = readCertificateList(source);
          List<Certificate> localCertificates = readCertificateList(source);
          handshake = Handshake.get(cipherSuite, peerCertificates, localCertificates);
        } else {
          handshake = null;
        }
      } finally {
        in.close();
      }
    }

    public Entry(Response response) {
      this.url = response.request().urlString();
      this.varyHeaders = OkHeaders.varyHeaders(response);
      this.requestMethod = response.request().method();
      this.protocol = response.protocol();
      this.code = response.code();
      this.message = response.message();
      this.responseHeaders = response.headers();
      this.handshake = response.handshake();
    }

    public void writeTo(DiskLruCache.Editor editor) throws IOException {
      OutputStream out = editor.newOutputStream(ENTRY_METADATA);
      Writer writer = new BufferedWriter(new OutputStreamWriter(out, UTF_8));

      writer.write(url);
      writer.write('\n');
      writer.write(requestMethod);
      writer.write('\n');
      writer.write(Integer.toString(varyHeaders.size()));
      writer.write('\n');
      for (int i = 0; i < varyHeaders.size(); i++) {
        writer.write(varyHeaders.name(i));
        writer.write(": ");
        writer.write(varyHeaders.value(i));
        writer.write('\n');
      }

      writer.write(new StatusLine(protocol, code, message).toString());
      writer.write('\n');
      writer.write(Integer.toString(responseHeaders.size()));
      writer.write('\n');
      for (int i = 0; i < responseHeaders.size(); i++) {
        writer.write(responseHeaders.name(i));
        writer.write(": ");
        writer.write(responseHeaders.value(i));
        writer.write('\n');
      }

      if (isHttps()) {
        writer.write('\n');
        writer.write(handshake.cipherSuite());
        writer.write('\n');
        writeCertArray(writer, handshake.peerCertificates());
        writeCertArray(writer, handshake.localCertificates());
      }
      writer.close();
    }

    private boolean isHttps() {
      return url.startsWith("https://");
    }

    private List<Certificate> readCertificateList(BufferedSource source) throws IOException {
      int length = readInt(source);
      if (length == -1) return Collections.emptyList(); // OkHttp v1.2 used -1 to indicate null.

      try {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        List<Certificate> result = new ArrayList<Certificate>(length);
        for (int i = 0; i < length; i++) {
          String line = source.readUtf8LineStrict();
          byte[] bytes = ByteString.decodeBase64(line).toByteArray();
          result.add(certificateFactory.generateCertificate(new ByteArrayInputStream(bytes)));
        }
        return result;
      } catch (CertificateException e) {
        throw new IOException(e.getMessage());
      }
    }

    private void writeCertArray(Writer writer, List<Certificate> certificates) throws IOException {
      try {
        writer.write(Integer.toString(certificates.size()));
        writer.write('\n');
        for (int i = 0, size = certificates.size(); i < size; i++) {
          byte[] bytes = certificates.get(i).getEncoded();
          String line = ByteString.of(bytes).base64();
          writer.write(line);
          writer.write('\n');
        }
      } catch (CertificateEncodingException e) {
        throw new IOException(e.getMessage());
      }
    }

    public boolean matches(Request request, Response response) {
      return url.equals(request.urlString())
          && requestMethod.equals(request.method())
          && OkHeaders.varyMatches(response, varyHeaders, request);
    }

    public Response response(Request request, DiskLruCache.Snapshot snapshot) {
      String contentType = responseHeaders.get("Content-Type");
      String contentLength = responseHeaders.get("Content-Length");
      return new Response.Builder()
          .request(request)
          .protocol(protocol)
          .code(code)
          .message(message)
          .headers(responseHeaders)
          .body(new CacheResponseBody(snapshot, contentType, contentLength))
          .handshake(handshake)
          .build();
    }
  }

  private static int readInt(BufferedSource source) throws IOException {
    String line = source.readUtf8LineStrict();
    try {
      return Integer.parseInt(line);
    } catch (NumberFormatException e) {
      throw new IOException("Expected an integer but was \"" + line + "\"");
    }
  }

  private static class CacheResponseBody extends Response.Body {
    private final DiskLruCache.Snapshot snapshot;
    private final BufferedSource bodySource;
    private final String contentType;
    private final String contentLength;

    public CacheResponseBody(final DiskLruCache.Snapshot snapshot,
        String contentType, String contentLength) {
      this.snapshot = snapshot;
      this.contentType = contentType;
      this.contentLength = contentLength;

      // This source closes the snapshot when it is closed.
      bodySource = Okio.buffer(new Source() {
        Source in = Okio.source(snapshot.getInputStream(ENTRY_BODY));
        @Override public long read(Buffer sink, long byteCount) throws IOException {
          return in.read(sink, byteCount);
        }
        @Override public Timeout timeout() {
          return in.timeout();
        }
        @Override public void close() throws IOException {
          snapshot.close();
          in.close();
        }
      });
    }

    @Override public MediaType contentType() {
      return contentType != null ? MediaType.parse(contentType) : null;
    }

    @Override public long contentLength() {
      try {
        return contentLength != null ? Long.parseLong(contentLength) : -1;
      } catch (NumberFormatException e) {
        return -1;
      }
    }

    @Override public BufferedSource source() {
      return bodySource;
    }
  }
}
