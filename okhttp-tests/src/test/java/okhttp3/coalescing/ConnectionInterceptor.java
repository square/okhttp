package okhttp3.coalescing;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Connection;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ConnectionInterceptor implements Interceptor {
  private ConnectionTarget connectionTarget;

  public ConnectionInterceptor(ConnectionTarget connectionTarget) {
    this.connectionTarget = connectionTarget;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    Request request = chain.request();

    request = remapRequest(chain, request);

    Response response = chain.proceed(request);

    return response;
  }

  private Request remapRequest(Chain chain, Request request) throws IOException {
    HttpUrl destinationUrl = request.url();

    OkHttpClient client = chain.client();
    List<Connection> connections = client.connectionPool().liveConnections();

    String urlHost = destinationUrl.host();

    List<InetAddress> urlDns = null;

    String targetHost = connectionTarget.getTarget(urlHost);
    if (targetHost != null) {
      HttpUrl targetUrl = destinationUrl.newBuilder().host(targetHost).build();
      return request.newBuilder().url(targetUrl).header("Host", urlHost).build();
    } else if (connectionTarget.isAutoCoalescing()) {
      for (Connection con : connections) {
        List<String> supportedHosts = con.getSupportedHosts();

        String connectionHost = con.route().socketAddress().getHostName();
        List<InetAddress> connectionDns = null;

        System.out.println(connectionHost + " " + supportedHosts);

        for (String host: supportedHosts) {
          if (ConnectionTarget.matches(host, urlHost)) {
            if (connectionDns == null) {
              connectionDns = dnsLookup(client, connectionHost);
              System.out.println(connectionHost + " " + connectionDns);
            }

            if (urlDns == null) {
              urlDns = dnsLookup(client, urlHost);
              System.out.println(urlHost + " " + urlDns);
            }

            if (matchesDns(connectionDns, urlDns)) {
              HttpUrl targetUrl = destinationUrl.newBuilder().host(connectionHost).build();
              return request.newBuilder().url(targetUrl).header("Host", urlHost).build();
            }
          }
        }
      }
    }

    return request;
  }

  private boolean matchesDns(List<InetAddress> connectionDns, List<InetAddress> urlDns) {
    for (InetAddress c: connectionDns) {
      for (InetAddress u: urlDns) {
        if (c.getHostAddress().equals(u.getHostAddress())) {
          return true;
        }
      }
    }

    return false;
  }

  private List<InetAddress> dnsLookup(OkHttpClient client, String urlHost) throws IOException {
    // TODO caching
    try {
      return client.dns().lookup(urlHost);
    } catch (UnknownHostException e) {
      throw new IOException(e);
    }
  }
}
