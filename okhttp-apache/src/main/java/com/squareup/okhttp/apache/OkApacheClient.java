// Copyright 2013 Square, Inc.
package com.squareup.okhttp.apache;

import com.squareup.okhttp.OkHttpClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.RequestLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.params.ConnRouteParams;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.AbstractHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

import static java.net.Proxy.Type.HTTP;
import static org.apache.http.HttpVersion.HTTP_1_1;

/**
 * Implements Apache's {@link HttpClient} API using {@link OkHttpClient}.
 * <p>
 * <strong>Warning:</strong> Many core features of Apache HTTP client are not implemented by this
 * API. This includes the keep-alive strategy, cookie store, credentials provider, route planner
 * and others.
 */
public class OkApacheClient implements HttpClient {
  protected final OkHttpClient client;

  private final HttpParams params = new AbstractHttpParams() {
    @Override public Object getParameter(String name) {
      if (name.equals(ConnRouteParams.DEFAULT_PROXY)) {
        Proxy proxy = client.getProxy();
        if (proxy == null) {
          return null;
        }
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        return new HttpHost(address.getHostName(), address.getPort());
      }
      throw new IllegalArgumentException(name);
    }

    @Override public HttpParams setParameter(String name, Object value) {
      if (name.equals(ConnRouteParams.DEFAULT_PROXY)) {
        HttpHost host = (HttpHost) value;
        Proxy proxy = null;
        if (host != null) {
          proxy = new Proxy(HTTP, new InetSocketAddress(host.getHostName(), host.getPort()));
        }
        client.setProxy(proxy);
        return this;
      }
      throw new IllegalArgumentException(name);
    }

    @Override public HttpParams copy() {
      throw new UnsupportedOperationException();
    }

    @Override public boolean removeParameter(String name) {
      throw new UnsupportedOperationException();
    }
  };

  public OkApacheClient() {
    this(new OkHttpClient());
  }

  public OkApacheClient(OkHttpClient client) {
    this.client = client;
  }

  /**
   * Returns a new HttpURLConnection customized for this application. Subclasses should override
   * this to customize the connection.
   */
  protected HttpURLConnection openConnection(URL url) {
    return client.open(url);
  }

  @Override public HttpParams getParams() {
    return params;
  }

  @Override public ClientConnectionManager getConnectionManager() {
    throw new UnsupportedOperationException();
  }

  @Override public HttpResponse execute(HttpUriRequest request) throws IOException {
    return execute(null, request, (HttpContext) null);
  }

  @Override public HttpResponse execute(HttpUriRequest request, HttpContext context)
      throws IOException {
    return execute(null, request, context);
  }

  @Override public HttpResponse execute(HttpHost host, HttpRequest request) throws IOException {
    return execute(host, request, (HttpContext) null);
  }

  @Override public HttpResponse execute(HttpHost host, HttpRequest request, HttpContext context)
      throws IOException {
    // Prepare the request headers.
    RequestLine requestLine = request.getRequestLine();
    URL url = new URL(requestLine.getUri());
    HttpURLConnection connection = openConnection(url);
    connection.setRequestMethod(requestLine.getMethod());
    for (Header header : request.getAllHeaders()) {
      connection.addRequestProperty(header.getName(), header.getValue());
    }

    // Stream the request body.
    if (request instanceof HttpEntityEnclosingRequest) {
      HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
      if (entity != null) {
        connection.setDoOutput(true);
        Header type = entity.getContentType();
        if (type != null) {
          connection.addRequestProperty(type.getName(), type.getValue());
        }
        Header encoding = entity.getContentEncoding();
        if (encoding != null) {
          connection.addRequestProperty(encoding.getName(), encoding.getValue());
        }
        if (entity.isChunked() || entity.getContentLength() < 0) {
          connection.setChunkedStreamingMode(0);
        } else if (entity.getContentLength() <= 8192) {
          // Buffer short, fixed-length request bodies. This costs memory, but permits the request
          // to be transparently retried if there is a connection failure.
          connection.addRequestProperty("Content-Length", Long.toString(entity.getContentLength()));
        } else {
          connection.setFixedLengthStreamingMode((int) entity.getContentLength());
        }
        entity.writeTo(connection.getOutputStream());
      }
    }

    // Read the response headers.
    int responseCode = connection.getResponseCode();
    String message = connection.getResponseMessage();
    BasicHttpResponse response = new BasicHttpResponse(HTTP_1_1, responseCode, message);
    for (int i = 0; true; i++) {
      String name = connection.getHeaderFieldKey(i);
      if (name == null) {
        break;
      }
      response.addHeader(name, connection.getHeaderField(i));
    }

    // Get the response body ready to stream.
    InputStream responseBody =
        responseCode < HttpURLConnection.HTTP_BAD_REQUEST ? connection.getInputStream()
            : connection.getErrorStream();
    response.setEntity(new InputStreamEntity(responseBody, connection.getContentLength()));

    return response;
  }

  @Override public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> handler)
      throws IOException {
    return execute(null, request, handler, null);
  }

  @Override public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> handler,
      HttpContext context) throws IOException {
    return execute(null, request, handler, context);
  }

  @Override
  public <T> T execute(HttpHost host, HttpRequest request, ResponseHandler<? extends T> handler)
      throws IOException {
    return execute(host, request, handler, null);
  }

  @Override
  public <T> T execute(HttpHost host, HttpRequest request, ResponseHandler<? extends T> handler,
      HttpContext context) throws IOException {
    HttpResponse response = execute(host, request, context);
    try {
      return handler.handleResponse(response);
    } finally {
      consumeContentQuietly(response);
    }
  }

  private static void consumeContentQuietly(HttpResponse response) {
    try {
      response.getEntity().consumeContent();
    } catch (Throwable ignored) {
    }
  }
}
