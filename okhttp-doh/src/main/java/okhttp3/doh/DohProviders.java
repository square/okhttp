package okhttp3.doh;

import java.net.InetAddress;
import java.net.UnknownHostException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class DohProviders {
  static DnsOverHttps buildGoogle(OkHttpClient bootstrapClient) {
    HttpUrl url = parseUrl("https://dns.google.com/experimental?ct");

    BootstrapDns bootstrapDns = new BootstrapDns("dns.google.com", getByIp("216.58.204.78"),
        getByIp("2a00:1450:4009:814:0:0:0:200e"));

    return new DnsOverHttps(bootstrapClient, url, bootstrapDns, true, "GET");
  }

  private static InetAddress getByIp(String host) {
    try {
      return InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      // unlikely
      throw new RuntimeException(e);
    }
  }

  static DnsOverHttps buildCloudflare(OkHttpClient bootstrapClient) {
    HttpUrl url = parseUrl("https://cloudflare-dns.com/dns-query?ct=application/dns-udpwireformat");

    BootstrapDns bootstrapDns = new BootstrapDns("cloudflare-dns.com", getByIp("104.16.111.25"),
        getByIp("104.16.112.25"),
        getByIp("2400:cb00:2048:1:0:0:6810:7019"),
        getByIp("2400:cb00:2048:1:0:0:6810:6f19"));

    return new DnsOverHttps(bootstrapClient, url, bootstrapDns, false, "GET");
  }

  private static HttpUrl parseUrl(String s) {
    HttpUrl url = HttpUrl.parse(s);

    if (url == null) {
      throw new NullPointerException("unable to parse url");
    }

    return url;
  }
}
