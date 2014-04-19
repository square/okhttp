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
package com.squareup.okhttp.curl;

import com.google.common.base.Joiner;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import io.airlift.command.Arguments;
import io.airlift.command.Command;
import io.airlift.command.HelpOption;
import io.airlift.command.Option;
import io.airlift.command.SingleCommand;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okio.Okio;

import static java.util.concurrent.TimeUnit.SECONDS;

@Command(name = Main.NAME, description = "A curl for the next-generation web.")
public class Main extends HelpOption implements Runnable {
  static final String NAME = "okcurl";
  static final int DEFAULT_TIMEOUT = -1;

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

  @Option(name = { "-X", "--request" }, description = "Specify request command to use")
  public String method;

  @Option(name = { "-d", "--data" }, description = "HTTP POST data")
  public String data;

  @Option(name = { "-H", "--header" }, description = "Custom header to pass to server")
  public List<String> headers;

  @Option(name = { "-A", "--user-agent" }, description = "User-Agent to send to server")
  public String userAgent = NAME + "/" + versionString();

  @Option(name = "--connect-timeout", description = "Maximum time allowed for connection (seconds)")
  public int connectTimeout = DEFAULT_TIMEOUT;

  @Option(name = "--read-timeout", description = "Maximum time allowed for reading data (seconds)")
  public int readTimeout = DEFAULT_TIMEOUT;

  @Option(name = { "-L", "--location" }, description = "Follow redirects")
  public boolean followRedirects;

  @Option(name = { "-k", "--insecure" },
      description = "Allow connections to SSL sites without certs")
  public boolean allowInsecure;

  @Option(name = { "-i", "--include" }, description = "Include protocol headers in the output")
  public boolean showHeaders;

  @Option(name = { "-e", "--referer" }, description = "Referer URL")
  public String referer;

  @Option(name = { "-V", "--version" }, description = "Show version number and quit")
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

    client = createClient();
    Request request = createRequest();
    try {
      Response response = client.execute(request);
      if (showHeaders) {
        System.out.println(response.statusLine());
        Headers headers = response.headers();
        for (int i = 0, count = headers.size(); i < count; i++) {
          System.out.println(headers.name(i) + ": " + headers.value(i));
        }
        System.out.println();
      }

      response.body().source().readAll(Okio.sink(System.out));
      response.body().close();
      System.out.flush();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      close();
    }
  }

  private OkHttpClient createClient() {
    OkHttpClient client = new OkHttpClient();
    client.setFollowSslRedirects(followRedirects);
    if (connectTimeout != DEFAULT_TIMEOUT) {
      client.setConnectTimeout(connectTimeout, SECONDS);
    }
    if (readTimeout != DEFAULT_TIMEOUT) {
      client.setReadTimeout(readTimeout, SECONDS);
    }
    if (allowInsecure) {
      client.setSslSocketFactory(createInsecureSslSocketFactory());
      client.setHostnameVerifier(createInsecureHostnameVerifier());
    }
    // If we don't set this reference, there's no way to clean shutdown persistent connections.
    client.setConnectionPool(ConnectionPool.getDefault());
    return client;
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

  private Request.Body getRequestBody() {
    if (data == null) {
      return null;
    }
    String bodyData = data;

    String mimeType = "application/x-form-urlencoded";
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

    return Request.Body.create(MediaType.parse(mimeType), bodyData);
  }

  Request createRequest() {
    Request.Builder request = new Request.Builder();

    request.url(url);
    request.method(getRequestMethod(), getRequestBody());

    if (headers != null) {
      for (String header : headers) {
        String[] parts = header.split(":", -1);
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
    client.getConnectionPool().evictAll(); // Close any persistent connections.
  }

  private static SSLSocketFactory createInsecureSslSocketFactory() {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      TrustManager permissive = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
          return null;
        }
      };
      context.init(null, new TrustManager[] { permissive }, null);
      return context.getSocketFactory();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static HostnameVerifier createInsecureHostnameVerifier() {
    return new HostnameVerifier() {
      @Override public boolean verify(String s, SSLSession sslSession) {
        return true;
      }
    };
  }
}
