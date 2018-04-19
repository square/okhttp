package okhttp3.doh;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TestDohMain {
  public static void main(String[] args) throws IOException {
    Cache dnsCache = new Cache(new File("./target/TestDohMain.cache"), 10 * 1024 * 1024);
    OkHttpClient bootstrapClient = new OkHttpClient.Builder().cache(dnsCache).build();

    //Map<String, List<InetAddress>> bootstrapAddresses = Collections.singletonMap("dns.google.com",
    //    Arrays.asList(InetAddress.getByName("216.58.204.78"),
    //        InetAddress.getByName("2a00:1450:4009:814:0:0:0:200e")));
    //String urlPrefix = "https://dns.google.com/experimental?ct&dns=";

    Map<String, List<InetAddress>> bootstrapAddresses =
        Collections.singletonMap("cloudflare-dns.com",
            Arrays.asList(InetAddress.getByName("104.16.111.25"),
                InetAddress.getByName("104.16.112.25"),
                InetAddress.getByName("2400:cb00:2048:1:0:0:6810:7019"),
                InetAddress.getByName("2400:cb00:2048:1:0:0:6810:6f19")));
    String urlPrefix =
        "https://cloudflare-dns.com/dns-query?ct=application/dns-udpwireformat&dns=$query";

    DnsOverHttps dns = new DnsOverHttps(bootstrapClient, urlPrefix, bootstrapAddresses);

    OkHttpClient client = bootstrapClient.newBuilder().dns(dns).cache(null).build();

    Request request = new Request.Builder().url("https://graph.facebook.com/robots.txt").build();
    Response response = client.newCall(request).execute();

    System.out.println(response.code());
  }
}
