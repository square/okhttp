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
package okhttp3.logging;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.platform.Platform;
import okio.Buffer;

import static okhttp3.internal.platform.Platform.INFO;

/**
 * A logger of HTTP calls. Can be applied as an
 * {@linkplain OkHttpClient#eventListenerFactory() event listener factory}. <p> The format of the
 * logs created by this class should not be considered stable and may change slightly between
 * releases. If you need a stable logging format, use your own event listener.
 */
public final class CallLogger {
  private static final Charset UTF8 = Charset.forName("UTF-8");

  public enum Level {
    /** No logs. */
    NONE,
    /**
     * Logs request and response lines.
     *
     * <p>Example:
     * <pre>{@code
     * --> POST /greeting http/1.1 (3-byte body)
     *
     * <-- 200 OK (22ms, 6-byte body)
     * }</pre>
     */
    BASIC,
    /**
     * Logs request and response lines and their respective headers.
     *
     * <p>Example:
     * <pre>{@code
     * --> POST /greeting http/1.1
     * Host: example.com
     * Content-Type: plain/text
     * Content-Length: 3
     * --> END POST
     *
     * <-- 200 OK (22ms)
     * Content-Type: plain/text
     * Content-Length: 6
     * <-- END HTTP
     * }</pre>
     */
    HEADERS,
    /**
     * Logs request and response lines and their respective headers and bodies (if present).
     *
     * <p>Example:
     * <pre>{@code
     * --> POST /greeting http/1.1
     * Host: example.com
     * Content-Type: plain/text
     * Content-Length: 3
     *
     * Hi?
     * --> END POST
     *
     * <-- 200 OK (22ms)
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
        Platform.get().log(INFO, message, null);
      }
    };
  }

  public CallLogger() {
    this(Logger.DEFAULT);
  }

  public CallLogger(Logger logger) {
    this.logger = logger;
  }

  private final Logger logger;

  private volatile Set<String> headersToRedact = Collections.emptySet();

  public void redactHeader(String name) {
    Set<String> newHeadersToRedact = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    newHeadersToRedact.addAll(headersToRedact);
    newHeadersToRedact.add(name);
    headersToRedact = newHeadersToRedact;
  }

  private volatile Level level = Level.NONE;

  /** Change the level at which this {@code CallLogger} logs. */
  public CallLogger setLevel(Level level) {
    if (level == null) throw new NullPointerException("level == null. Use Level.NONE instead.");
    this.level = level;
    return this;
  }

  public Level getLevel() {
    return level;
  }

  public EventListener.Factory eventListenerFactory() {
    return new EventListener.Factory() {
      public EventListener create(Call call) {
        return new EventListener();
      }
    };
  }

  private final class EventListener extends okhttp3.EventListener {
    long startNs;
    Connection connection;
    Response response;

    @Override
    public void callStart(Call call) {
      startNs = System.nanoTime();
    }

    @Override
    public void connectionAcquired(Call call, Connection connection) {
      this.connection = connection;
    }

    @Override
    public void requestHeadersStart(Call call) {
      Level level = CallLogger.this.level;
      if (level == Level.NONE) {
        return;
      }

      boolean logHeaders = level == Level.BODY || level == Level.HEADERS;

      try {
        Request request = call.request();
        RequestBody requestBody = request.body();
        boolean hasRequestBody = requestBody != null;

        String requestStartMessage = "--> "
            + request.method()
            + ' ' + request.url()
            + (connection != null ? " " + connection.protocol() : "");
        if (!logHeaders && hasRequestBody) {
          requestStartMessage += " (" + requestBody.contentLength() + "-byte body)";
        }
        logger.log(requestStartMessage);
      } catch (IOException e) {
        logger.log("Failed logging requestHeadersStart: " + e);
      }
    }

    @Override
    public void requestHeadersEnd(Call call, Request request) {
      Level level = CallLogger.this.level;
      if (level == Level.NONE) {
        return;
      }

      boolean logBody = level == Level.BODY;
      boolean logHeaders = logBody || level == Level.HEADERS;

      try {
        RequestBody requestBody = request.body();
        boolean hasRequestBody = requestBody != null;

        if (logHeaders) {
          if (hasRequestBody) {
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
            if (!"Content-Type".equalsIgnoreCase(name)
                && !"Content-Length".equalsIgnoreCase(name)) {
              logHeader(headers, i);
            }
          }

          if (!logBody || !hasRequestBody) {
            logger.log("--> END " + request.method());
          } else if (bodyHasUnknownEncoding(request.headers())) {
            logger.log("--> END " + request.method() + " (encoded body omitted)");
          } else {
            Buffer buffer = new Buffer();
            requestBody.writeTo(buffer);

            Charset charset = UTF8;
            MediaType contentType = requestBody.contentType();
            if (contentType != null) {
              charset = contentType.charset(UTF8);
            }

            logger.log("");
            if (isPlaintext(buffer)) {
              logger.log(buffer.readString(charset));
              logger.log("--> END " + request.method()
                  + " (" + requestBody.contentLength() + "-byte body)");
            } else {
              logger.log("--> END " + request.method() + " (binary "
                  + requestBody.contentLength() + "-byte body omitted)");
            }
          }
        }
      } catch (IOException e) {
        logger.log("Failed logging requestHeadersEnd" + e);
      }
    }

    @Override
    public void responseHeadersEnd(Call call, Response response) {
      this.response = response;
    }

    @Override
    public void responseBodyEnd(Call call, long byteCount) {
      Level level = CallLogger.this.level;
      if (level == Level.NONE) {
        return;
      }

      long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

      boolean logHeaders = level == Level.BODY || level == Level.HEADERS;

      logger.log("<-- "
              + response.code()
              + (response.message().isEmpty() ? "" : ' ' + response.message())
              + ' ' + response.request().url()
              + " (" + tookMs + "ms" + (!logHeaders ? ", " + byteCount + "-byte body" : "") + ')');

      if (logHeaders) {
        Headers headers = response.headers();
        for (int i = 0, count = headers.size(); i < count; i++) {
          logHeader(headers, i);
        }

        logger.log("<-- END HTTP (" + byteCount + "-byte body)");
      }
    }

    private void logHeader(Headers headers, int i) {
      String value = headersToRedact.contains(headers.name(i)) ? "██" : headers.value(i);
      logger.log(headers.name(i) + ": " + value);
    }

    @Override
    public void callFailed(Call call, IOException ioe) {
      logger.log("<-- HTTP FAILED: " + ioe);
    }
  }

  /**
   * Returns true if the body in question probably contains human readable text. Uses a small sample
   * of code points to detect unicode control characters commonly used in binary file signatures.
   */
  static boolean isPlaintext(Buffer buffer) {
    try {
      Buffer prefix = new Buffer();
      long byteCount = buffer.size() < 64 ? buffer.size() : 64;
      buffer.copyTo(prefix, 0, byteCount);
      for (int i = 0; i < 16; i++) {
        if (prefix.exhausted()) {
          break;
        }
        int codePoint = prefix.readUtf8CodePoint();
        if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
          return false;
        }
      }
      return true;
    } catch (EOFException e) {
      return false; // Truncated UTF-8 sequence.
    }
  }

  private static boolean bodyHasUnknownEncoding(Headers headers) {
    String contentEncoding = headers.get("Content-Encoding");
    return contentEncoding != null
        && !contentEncoding.equalsIgnoreCase("identity")
        && !contentEncoding.equalsIgnoreCase("gzip");
  }
}
