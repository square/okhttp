// Copyright 2013 Square, Inc.
package okhttp3.apache;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Util;
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
 * @deprecated OkHttp will be dropping its ability to be used with {@link HttpClient} in an upcoming
 * release. Applications that need this should either downgrade to the Apache implementation or
 * upgrade to OkHttp's Request/Response API.
 */
public final class OkApacheClient implements HttpClient {
  private static Request transformRequest(HttpRequest request) {
    Request.Builder builder = new Request.Builder();

    RequestLine requestLine = request.getRequestLine();
    String method = requestLine.getMethod();
    builder.url(requestLine.getUri());

    String contentType = null;
    for (Header header : request.getAllHeaders()) {
      String name = header.getName();
      if ("Content-Type".equalsIgnoreCase(name)) {
        contentType = header.getValue();
      } else {
        builder.header(name, header.getValue());
      }
    }

    RequestBody body = null;
    if (request instanceof HttpEntityEnclosingRequest) {
      HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
      if (entity != null) {
        // Wrap the entity in a custom Body which takes care of the content, length, and type.
        body = new HttpEntityBody(entity, contentType);

        Header encoding = entity.getContentEncoding();
        if (encoding != null) {
          builder.header(encoding.getName(), encoding.getValue());
        }
      } else {
        body = Util.EMPTY_REQUEST;
      }
    }
    builder.method(method, body);

    return builder.build();
  }

  private static HttpResponse transformResponse(Response response) {
    int code = response.code();
    String message = response.message();
    BasicHttpResponse httpResponse = new BasicHttpResponse(HTTP_1_1, code, message);

    ResponseBody body = response.body();
    InputStreamEntity entity = new InputStreamEntity(body.byteStream(), body.contentLength());
    httpResponse.setEntity(entity);

    Headers headers = response.headers();
    for (int i = 0, size = headers.size(); i < size; i++) {
      String name = headers.name(i);
      String value = headers.value(i);
      httpResponse.addHeader(name, value);
      if ("Content-Type".equalsIgnoreCase(name)) {
        entity.setContentType(value);
      } else if ("Content-Encoding".equalsIgnoreCase(name)) {
        entity.setContentEncoding(value);
      }
    }

    return httpResponse;
  }

  private final HttpParams params = new AbstractHttpParams() {
    @Override public Object getParameter(String name) {
      if (name.equals(ConnRouteParams.DEFAULT_PROXY)) {
        Proxy proxy = client.proxy();
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
        client = client.newBuilder()
            .proxy(proxy)
            .build();
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

  private OkHttpClient client;

  public OkApacheClient() {
    this(new OkHttpClient());
  }

  public OkApacheClient(OkHttpClient client) {
    this.client = client;
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
    Request okRequest = transformRequest(request);
    Response okResponse = client.newCall(okRequest).execute();
    return transformResponse(okResponse);
  }

  @Override public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> handler)
      throws IOException {
    return execute(null, request, handler, null);
  }

  @Override public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> handler,
      HttpContext context) throws IOException {
    return execute(null, request, handler, context);
  }

  @Override public <T> T execute(HttpHost host, HttpRequest request,
      ResponseHandler<? extends T> handler) throws IOException {
    return execute(host, request, handler, null);
  }

  @Override public <T> T execute(HttpHost host, HttpRequest request,
      ResponseHandler<? extends T> handler, HttpContext context) throws IOException {
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
