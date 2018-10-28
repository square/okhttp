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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.platform.Platform.INFO;

/**
 * An OkHttp EventListener, which logs call events. Can be applied as an {@linkplain
 * OkHttpClient#eventListenerFactory() event listener factory}.
 *
 * <p>The format of the logs created by this class should not be considered stable and may change
 * slightly between releases. If you need a stable logging format, use your own event listener.
 */
public final class LoggingEventListener extends EventListener {
  private final Logger logger;
  private long startNs;

  private LoggingEventListener(Logger logger) {
    this.logger = logger;
  }

  @Override
  public void callStart(Call call) {
    startNs = System.nanoTime();

    logWithTime("callStart: " + call.request());
  }

  @Override
  public void dnsStart(Call call, String domainName) {
    logWithTime("dnsStart: " + domainName);
  }

  @Override
  public void dnsEnd(Call call, String domainName, List<InetAddress> inetAddressList) {
    logWithTime("dnsEnd: " + inetAddressList);
  }

  @Override
  public void connectStart(Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
    logWithTime("connectStart: " + inetSocketAddress + " " + proxy);
  }

  @Override
  public void secureConnectStart(Call call) {
    logWithTime("secureConnectStart");
  }

  @Override
  public void secureConnectEnd(Call call, @Nullable Handshake handshake) {
    logWithTime("secureConnectEnd");
  }

  @Override
  public void connectEnd(
      Call call, InetSocketAddress inetSocketAddress, Proxy proxy, @Nullable Protocol protocol) {
    logWithTime("connectEnd: " + protocol);
  }

  @Override
  public void connectFailed(
      Call call,
      InetSocketAddress inetSocketAddress,
      Proxy proxy,
      @Nullable Protocol protocol,
      IOException ioe) {
    logWithTime("connectFailed: " + protocol + " " + ioe);
  }

  @Override
  public void connectionAcquired(Call call, Connection connection) {
    logWithTime("connectionAcquired: " + connection);
  }

  @Override
  public void connectionReleased(Call call, Connection connection) {
    logWithTime("connectionReleased");
  }

  @Override
  public void requestHeadersStart(Call call) {
    logWithTime("requestHeadersStart");
  }

  @Override
  public void requestHeadersEnd(Call call, Request request) {
    logWithTime("requestHeadersEnd");
  }

  @Override
  public void requestBodyStart(Call call) {
    logWithTime("requestBodyStart");
  }

  @Override
  public void requestBodyEnd(Call call, long byteCount) {
    logWithTime("requestBodyEnd: byteCount=" + byteCount);
  }

  @Override
  public void responseHeadersStart(Call call) {
    logWithTime("responseHeadersStart");
  }

  @Override
  public void responseHeadersEnd(Call call, Response response) {
    logWithTime("responseHeadersEnd: " + response);
  }

  @Override
  public void responseBodyStart(Call call) {
    logWithTime("responseBodyStart");
  }

  @Override
  public void responseBodyEnd(Call call, long byteCount) {
    logWithTime("responseBodyEnd: byteCount=" + byteCount);
  }

  @Override
  public void callEnd(Call call) {
    logWithTime("callEnd");
  }

  @Override
  public void callFailed(Call call, IOException ioe) {
    logWithTime("callFailed: " + ioe);
  }

  private void logWithTime(String message) {
    long timeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
    logger.log("[t=" + timeMs + "] " + message);
  }

  public interface Logger {
    /** A {@link Logger} that outputs appropriately for the current platform. */
    Logger DEFAULT =
        new Logger() {
          @Override
          public void log(String message) {
            Platform.get().log(INFO, message, null);
          }
        };

    /** A {@link Logger} that outputs to the standard output stream. */
    Logger STDOUT = new Logger() {
      @Override
      public void log(String message) {
          System.out.println(message);
      }
    };

    void log(String message);
  }

  public static class Factory implements EventListener.Factory {
    public static final Factory STDOUT = new Factory(Logger.STDOUT);

    private final Logger logger;

    public Factory() {
      this(Logger.DEFAULT);
    }

    public Factory(Logger logger) {
      this.logger = logger;
    }

    @Override
    public EventListener create(Call call) {
      return new LoggingEventListener(logger);
    }
  }
}
