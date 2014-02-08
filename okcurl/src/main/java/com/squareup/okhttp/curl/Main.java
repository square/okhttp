package com.squareup.okhttp.curl;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.Failure;
import com.squareup.okhttp.Headers;
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
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static java.util.concurrent.TimeUnit.SECONDS;

@Command(name = Main.NAME, description = "A curl for the next-generation web.")
public class Main extends HelpOption implements Runnable, Response.Receiver {
  static final String NAME = "okcurl";
  static final int DEFAULT_TIMEOUT = -1;

  public static void main(String... args) {
    SingleCommand.singleCommand(Main.class).parse(args).run();
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
    return Joiner.on(", ").join(Lists.transform(Arrays.asList(Protocol.values()),
        new Function<Protocol, String>() {
          @Override public String apply(Protocol protocol) {
            return protocol.name.utf8();
          }
        }));
  }

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

    client = getConfiguredClient();
    Request request = getConfiguredRequest();
    client.enqueue(request, this);

    // Immediately begin triggering an executor shutdown so that after execution of the above
    // request the threads do not stick around until timeout.
    client.getDispatcher().getExecutorService().shutdown();
  }

  private OkHttpClient getConfiguredClient() {
    OkHttpClient client = new OkHttpClient();
    client.setFollowProtocolRedirects(followRedirects);
    if (connectTimeout != DEFAULT_TIMEOUT) {
      client.setConnectTimeout(connectTimeout, SECONDS);
    }
    if (readTimeout != DEFAULT_TIMEOUT) {
      client.setReadTimeout(readTimeout, SECONDS);
    }
    if (allowInsecure) {
      client.setSslSocketFactory(createInsecureSslSocketFactory());
    }
    // If we don't set this reference, there's no way to clean shutdown persistent connections.
    client.setConnectionPool(ConnectionPool.getDefault());
    return client;
  }

  private Request getConfiguredRequest() {
    Request.Builder request = new Request.Builder();
    request.url(url);
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

  @Override public void onFailure(Failure failure) {
    failure.exception().printStackTrace();
    close();
  }

  @Override public boolean onResponse(Response response) throws IOException {
    if (showHeaders) {
      System.out.println(response.statusLine());
      Headers headers = response.headers();
      for (int i = 0, count = headers.size(); i < count; i++) {
        System.out.println(headers.name(i) + ": " + headers.value(i));
      }
      System.out.println();
    }

    Response.Body body = response.body();
    byte[] buffer = new byte[1024];
    while (body.ready()) {
      int c = body.byteStream().read(buffer);
      if (c == -1) {
        close();
        return true;
      }

      System.out.write(buffer, 0, c);
    }
    close();
    return false;
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
}
