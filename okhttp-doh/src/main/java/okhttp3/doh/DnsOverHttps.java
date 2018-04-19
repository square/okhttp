package okhttp3.doh;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.ByteString;

// TODO implement rules and caching according to spec
public class DnsOverHttps implements Dns {
  private final OkHttpClient client;
  private final String urlPrefix;
  private final Map<String, List<InetAddress>> bootstrapAddresses;

  public DnsOverHttps(OkHttpClient client, String urlPrefix,
      Map<String, List<InetAddress>> bootstrapAddresses) {
    this.client = client;
    this.urlPrefix = urlPrefix;
    this.bootstrapAddresses = bootstrapAddresses;
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    List<InetAddress> results = bootstrapAddresses.get(hostname);

    if (results == null) {
      try {
        String query = DnsRecordCodec.encodeQuery(hostname);

        Request request = buildRequest(query);
        Response response = client.newCall(request).execute();

        try {
          if (response.code() != 200) {
            throw new IOException("response: " + response.code() + " " + response.message());
          }

          ByteString responseBytes = response.body().source().readByteString();
          results = DnsRecordCodec.decodeAnswers(responseBytes);
        } finally {
          response.close();
        }
      } catch (Exception e) {
        UnknownHostException unknownHostException = new UnknownHostException(hostname);
        unknownHostException.initCause(e);
        throw unknownHostException;
      }
    }

    if (results.isEmpty()) {
      throw new UnknownHostException(hostname);
    }

    return results;
  }

  private Request buildRequest(String query) {
    return new Request.Builder().url("https://dns.google.com/experimental?ct&dns=" + query).build();
  }
}