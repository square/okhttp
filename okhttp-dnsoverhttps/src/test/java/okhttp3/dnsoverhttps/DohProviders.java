package okhttp3.dnsoverhttps;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Temporary registry of known DNS over HTTPS providers.
 *
 * https://github.com/curl/curl/wiki/DNS-over-HTTPS
 */
public class DohProviders {
  static DnsOverHttps buildGoogle(OkHttpClient bootstrapClient, boolean post) {
    HttpUrl url = parseUrl("https://dns.google.com/experimental?ct");

    BootstrapDns bootstrapDns = new BootstrapDns("dns.google.com", getByIp("216.58.204.78"),
        getByIp("2a00:1450:4009:814:0:0:0:200e"));

    return new DnsOverHttps(bootstrapClient, url, bootstrapDns, true, post ? "POST" : "GET");
  }

  static DnsOverHttps buildCloudflare(OkHttpClient bootstrapClient) {
    HttpUrl url = parseUrl("https://cloudflare-dns.com/dns-query?ct=application/dns-udpwireformat");

    BootstrapDns bootstrapDns = new BootstrapDns("cloudflare-dns.com", getByIp("104.16.111.25"),
        getByIp("104.16.112.25"),
        getByIp("2400:cb00:2048:1:0:0:6810:7019"),
        getByIp("2400:cb00:2048:1:0:0:6810:6f19"));

    return new DnsOverHttps(bootstrapClient, url, bootstrapDns, false, "GET");
  }

  static DnsOverHttps buildCloudflarePost(OkHttpClient bootstrapClient) {
    HttpUrl url = parseUrl("https://dns.cloudflare.com/.well-known/dns-query");

    return new DnsOverHttps(bootstrapClient, url, null, false, "POST");
  }

  static DnsOverHttps buildCleanBrowsing(OkHttpClient bootstrapClient) {
    return new DnsOverHttps(bootstrapClient,
        parseUrl("https://doh.cleanbrowsing.org/doh/family-filter"), null, false, "GET");
  }

  static DnsOverHttps buildChantra(OkHttpClient bootstrapClient) {
    return new DnsOverHttps(bootstrapClient, parseUrl("https://dns.dnsoverhttps.net/dns-query"),
        null, false, "GET");
  }

  static DnsOverHttps buildCryptoSx(OkHttpClient bootstrapClient) {
    return new DnsOverHttps(bootstrapClient, parseUrl("https://doh.crypto.sx/dns-query"), null,
        false, "GET");
  }

  static DnsOverHttps buildSecureDns(OkHttpClient bootstrapClient) {
    return new DnsOverHttps(bootstrapClient, parseUrl("https://doh.securedns.eu/dns-query"), null,
        false, "GET");
  }

  public static List<DnsOverHttps> providers(OkHttpClient client) {
    return Arrays.asList(buildGoogle(client, false), buildGoogle(client, true),
        buildCloudflare(client), buildCloudflarePost(client), buildCleanBrowsing(client),
        buildChantra(client), buildCryptoSx(client), buildSecureDns(client));
  }

  private static HttpUrl parseUrl(String s) {
    HttpUrl url = HttpUrl.parse(s);

    if (url == null) {
      throw new NullPointerException("unable to parse url");
    }

    return url;
  }

  private static InetAddress getByIp(String host) {
    try {
      return InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      // unlikely
      throw new RuntimeException(e);
    }
  }
}
