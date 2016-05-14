/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.curl;

import com.google.common.base.Joiner;
import io.airlift.airline.Arguments;
import io.airlift.airline.Command;
import io.airlift.airline.HelpOption;
import io.airlift.airline.Option;
import io.airlift.airline.SingleCommand;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.Util;
import okhttp3.internal.framed.Http2;
import okhttp3.internal.http.StatusLine;
import okhttp3.internal.tls.DefaultSecurity;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;

import static java.util.concurrent.TimeUnit.SECONDS;

@Command(name = Main.NAME, description = "A curl for the next-generation web.")
public class Main extends HelpOption implements Runnable {
  static final String NAME = "okcurl";
  static final int DEFAULT_TIMEOUT = -1;
  private static Logger frameLogger;

  static Main fromArgs(String... args) {
    return SingleCommand.singleCommand(Main.class).parse(args);
  }

  public static void main(String... args) {
    fromArgs(args).run();
  }

  private static String versionString() {
    try {
      Properties prop = new Properties();
      InputStream in = Main.class.getResourceAsStream("/okcurl-version.properties");
      prop.load(in);
      in.close();
      return prop.getProperty("version");
    } catch (IOException e) {
      throw new AssertionError("Could not load okcurl-version.properties.");
    }
  }

  private static String protocols() {
    return Joiner.on(", ").join(Protocol.values());
  }

  @Option(name = {"-X", "--request"}, description = "Specify request command to use")
  public String method;

  @Option(name = {"-d", "--data"}, description = "HTTP POST data")
  public String data;

  @Option(name = {"-H", "--header"}, description = "Custom header to pass to server")
  public List<String> headers;

  @Option(name = {"-A", "--user-agent"}, description = "User-Agent to send to server")
  public String userAgent = NAME + "/" + versionString();

  @Option(name = "--connect-timeout", description = "Maximum time allowed for connection (seconds)")
  public int connectTimeout = DEFAULT_TIMEOUT;

  @Option(name = "--read-timeout", description = "Maximum time allowed for reading data (seconds)")
  public int readTimeout = DEFAULT_TIMEOUT;

  @Option(name = {"-L", "--location"}, description = "Follow redirects")
  public boolean followRedirects;

  @Option(name = {"-k", "--insecure"},
      description = "Allow connections to SSL sites without certs")
  public boolean allowInsecure;

  @Option(name = {"-i", "--include"}, description = "Include protocol headers in the output")
  public boolean showHeaders;

  @Option(name = "--frames", description = "Log HTTP/2 frames to STDERR")
  public boolean showHttp2Frames;

  @Option(name = {"-e", "--referer"}, description = "Referer URL")
  public String referer;

  @Option(name = {"-V", "--version"}, description = "Show version number and quit")
  public boolean version;

  @Arguments(title = "url", description = "Remote resource URL")
  public String url;

  private OkHttpClient client;

  @Override public void run() {
    if (showHelpIfRequested()) {
      return;
    }
    if (version) {
      System.out.println(NAME + " " + versionString());
      System.out.println("Protocols: " + protocols());
      return;
    }

    if (showHttp2Frames) {
      enableHttp2FrameLogging();
    }

    client = createClient();
    Request request = createRequest();
    try {
      Response response = client.newCall(request).execute();
      if (showHeaders) {
        System.out.println(StatusLine.get(response));
        Headers headers = response.headers();
        for (int i = 0, size = headers.size(); i < size; i++) {
          System.out.println(headers.name(i) + ": " + headers.value(i));
        }
        System.out.println();
      }

      // Stream the response to the System.out as it is returned from the server.
      Sink out = Okio.sink(System.out);
      BufferedSource source = response.body().source();
      while (!source.exhausted()) {
        out.write(source.buffer(), source.buffer().size());
        out.flush();
      }

      response.body().close();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      close();
    }
  }

  private OkHttpClient createClient() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    builder.followSslRedirects(followRedirects);
    if (connectTimeout != DEFAULT_TIMEOUT) {
      builder.connectTimeout(connectTimeout, SECONDS);
    }
    if (readTimeout != DEFAULT_TIMEOUT) {
      builder.readTimeout(readTimeout, SECONDS);
    }
    if (allowInsecure) {
      X509TrustManager trustManager = createInsecureTrustManager();
      builder.sslSocketFactory(DefaultSecurity.systemDefaultSslSocketFactory(trustManager),
          trustManager);
      builder.hostnameVerifier(createInsecureHostnameVerifier());
    }
    return builder.build();
  }

  private String getRequestMethod() {
    if (method != null) {
      return method;
    }
    if (data != null) {
      return "POST";
    }
    return "GET";
  }

  private RequestBody getRequestBody() {
    if (data == null) {
      return null;
    }
    String bodyData = data;

    String mimeType = "application/x-www-form-urlencoded";
    if (headers != null) {
      for (String header : headers) {
        String[] parts = header.split(":", -1);
        if ("Content-Type".equalsIgnoreCase(parts[0])) {
          mimeType = parts[1].trim();
          headers.remove(header);
          break;
        }
      }
    }

    return RequestBody.create(MediaType.parse(mimeType), bodyData);
  }

  Request createRequest() {
    Request.Builder request = new Request.Builder();

    request.url(url);
    request.method(getRequestMethod(), getRequestBody());

    if (headers != null) {
      for (String header : headers) {
        String[] parts = header.split(":", 2);
        request.header(parts[0], parts[1]);
      }
    }
    if (referer != null) {
      request.header("Referer", referer);
    }
    request.header("User-Agent", userAgent);

    return request.build();
  }

  private void close() {
    client.connectionPool().evictAll(); // Close any persistent connections.
  }

  private static X509TrustManager createInsecureTrustManager() {
    return new X509TrustManager() {
      @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
      }

      @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
      }

      @Override public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
  }

  private static HostnameVerifier createInsecureHostnameVerifier() {
    return new HostnameVerifier() {
      @Override public boolean verify(String s, SSLSession sslSession) {
        return true;
      }
    };
  }

  private static void enableHttp2FrameLogging() {
    frameLogger = Logger.getLogger(Http2.class.getName() + "$FrameLogger");
    frameLogger.setLevel(Level.FINE);
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(Level.FINE);
    handler.setFormatter(new SimpleFormatter() {
      @Override public String format(LogRecord record) {
        return Util.format("%s%n", record.getMessage());
      }
    });
    frameLogger.addHandler(handler);
  }
}
