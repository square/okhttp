package okhttp3.doh;

import java.net.InetAddress;
import java.net.UnknownHostException;
import okhttp3.OkHttpClient;

public class DohProviders {
  static DnsOverHttps buildGoogle(OkHttpClient bootstrapClient) {
    String urlPrefix = "https://dns.google.com/experimental?ct&dns=";

    BootstrapDns bootstrapDns = new BootstrapDns("dns.google.com", getByIp("216.58.204.78"),
        getByIp("2a00:1450:4009:814:0:0:0:200e"));

    return new DnsOverHttps(bootstrapClient, urlPrefix, bootstrapDns, true);
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
    String urlPrefix = "https://cloudflare-dns.com/dns-query?ct=application/dns-udpwireformat&dns=";

    BootstrapDns bootstrapDns = new BootstrapDns("cloudflare-dns.com", getByIp("104.16.111.25"),
        getByIp("104.16.112.25"),
        getByIp("2400:cb00:2048:1:0:0:6810:7019"),
        getByIp("2400:cb00:2048:1:0:0:6810:6f19"));

    return new DnsOverHttps(bootstrapClient, urlPrefix, bootstrapDns, false);
  }
}
