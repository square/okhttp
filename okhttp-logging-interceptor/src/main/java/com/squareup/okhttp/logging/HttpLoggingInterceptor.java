/*
 * Copyright (C) 2015 Square, Inc.
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
package com.squareup.okhttp.logging;

import com.squareup.okhttp.Connection;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.http.HttpEngine;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.concurrent.TimeUnit;
import okio.Buffer;
import okio.BufferedSource;

/**
 * An OkHttp interceptor which logs request and response information. Can be applied as an
 * {@linkplain OkHttpClient#interceptors() application interceptor} or as a
 * {@linkplain OkHttpClient#networkInterceptors() network interceptor}.
 * <p>
 * The format of the logs created by this class should not be considered stable and may change
 * slightly between releases. If you need a stable logging format, use your own interceptor.
 */
public final class HttpLoggingInterceptor implements Interceptor {
  private static final Charset UTF8 = Charset.forName("UTF-8");

  public enum Level {
    /** No logs. */
    NONE,
    /**
     * Logs request and response lines.
     * <p>
     * Example:
     * <pre>{@code
     * --> POST /greeting HTTP/1.1 (3-byte body)
     *
     * <-- HTTP/1.1 200 OK (22ms, 6-byte body)
     * }</pre>
     */
    BASIC,
    /**
     * Logs request and response lines and their respective headers.
     * <p>
     * Example:
     * <pre>{@code
     * --> POST /greeting HTTP/1.1
     * Host: example.com
     * Content-Type: plain/text
     * Content-Length: 3
     * --> END POST
     *
     * <-- HTTP/1.1 200 OK (22ms)
     * Content-Type: plain/text
     * Content-Length: 6
     * <-- END HTTP
     * }</pre>
     */
    HEADERS,
    /**
     * Logs request and response lines and their respective headers and bodies (if present).
     * <p>
     * Example:
     * <pre>{@code
     * --> POST /greeting HTTP/1.1
     * Host: example.com
     * Content-Type: plain/text
     * Content-Length: 3
     *
     * Hi?
     * --> END GET
     *
     * <-- HTTP/1.1 200 OK (22ms)
     * Content-Type: plain/text
     * Content-Length: 6
     *
     * Hello!
     * <-- END HTTP
     * }</pre>
     */
    BODY
  }

  public interface Logger {
    void log(String message);

    /** A {@link Logger} defaults output appropriate for the current platform. */
    Logger DEFAULT = new Logger() {
      @Override public void log(String message) {
        Platform.get().log(message);
      }
    };
  }

  public HttpLoggingInterceptor() {
    this(Logger.DEFAULT);
  }

  public HttpLoggingInterceptor(Logger logger) {
    this.logger = logger;
  }

  private final Logger logger;

  private volatile Level level = Level.NONE;

  /** Change the level at which this interceptor logs. */
  public HttpLoggingInterceptor setLevel(Level level) {
    if (level == null) throw new NullPointerException("level == null. Use Level.NONE instead.");
    this.level = level;
    return this;
  }

  public Level getLevel() {
    return level;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    Level level = this.level;

    Request request = chain.request();
    if (level == Level.NONE) {
      return chain.proceed(request);
    }

    boolean logBody = level == Level.BODY;
    boolean logHeaders = logBody || level == Level.HEADERS;

    RequestBody requestBody = request.body();
    boolean hasRequestBody = requestBody != null;

    Connection connection = chain.connection();
    Protocol protocol = connection != null ? connection.getProtocol() : Protocol.HTTP_1_1;
    String requestStartMessage =
        "--> " + request.method() + ' ' + request.url() + ' ' + protocol(protocol);
    if (!logHeaders && hasRequestBody) {
      requestStartMessage += " (" + requestBody.contentLength() + "-byte body)";
    }
    logger.log(requestStartMessage);

    if (logHeaders) {
      if (hasRequestBody) {
        // Request body headers are only present when installed as a network interceptor. Force
        // them to be included (when available) so there values are known.
        if (requestBody.contentType() != null) {
          logger.log("Content-Type: " + requestBody.contentType());
        }
        if (requestBody.contentLength() != -1) {
          logger.log("Content-Length: " + requestBody.contentLength());
        }
      }

      Headers headers = request.headers();
      for (int i = 0, count = headers.size(); i < count; i++) {
        String name = headers.name(i);
        // Skip headers from the request body as they are explicitly logged above.
        if (!"Content-Type".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)) {
          logger.log(name + ": " + headers.value(i));
        }
      }

      if (!logBody || !hasRequestBody) {
        logger.log("--> END " + request.method());
      } else if (bodyEncoded(request.headers())) {
        logger.log("--> END " + request.method() + " (encoded body omitted)");
      } else {
        Buffer buffer = new Buffer();
        requestBody.writeTo(buffer);

        Charset charset = UTF8;
        MediaType contentType = requestBody.contentType();
        if (contentType != null) {
          contentType.charset(UTF8);
        }

        logger.log("");
        logger.log(buffer.readString(charset));

        logger.log("--> END " + request.method()
            + " (" + requestBody.contentLength() + "-byte body)");
      }
    }

    long startNs = System.nanoTime();
    Response response = chain.proceed(request);
    long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

    ResponseBody responseBody = response.body();
    logger.log("<-- " + response.code() + ' ' + response.message() + ' '
        + response.request().url() + " (" + tookMs + "ms" + (!logHeaders ? ", "
        + responseBody.contentLength() + "-byte body" : "") + ')');

    if (logHeaders) {
      Headers headers = response.headers();
      for (int i = 0, count = headers.size(); i < count; i++) {
        logger.log(headers.name(i) + ": " + headers.value(i));
      }

      if (!logBody || !HttpEngine.hasBody(response)) {
        logger.log("<-- END HTTP");
      } else if (bodyEncoded(response.headers())) {
        logger.log("<-- END HTTP (encoded body omitted)");
      } else {
        BufferedSource source = responseBody.source();
        source.request(Long.MAX_VALUE); // Buffer the entire body.
        Buffer buffer = source.buffer();

        Charset charset = UTF8;
        MediaType contentType = responseBody.contentType();
        if (contentType != null)
          try {
            charset = contentType.charset(UTF8);
          } catch (UnsupportedCharsetException e) {
            // unsupported charset, assume UTF8 nevertheless
          }

        if (responseBody.contentLength() != 0) {
          logger.log("");
          logger.log(buffer.clone().readString(charset));
        }

        logger.log("<-- END HTTP (" + buffer.size() + "-byte body)");
      }
    }

    return response;
  }

  private boolean bodyEncoded(Headers headers) {
    String contentEncoding = headers.get("Content-Encoding");
    return contentEncoding != null && !contentEncoding.equalsIgnoreCase("identity");
  }

  private static String protocol(Protocol protocol) {
    return protocol == Protocol.HTTP_1_0 ? "HTTP/1.0" : "HTTP/1.1";
  }
}
