/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.dnsoverhttps;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import java.util.Collections;
import java.util.List;
import okhttp3.Cache;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

import static java.util.Arrays.asList;

public class TestDohMain {
  public static void main(String[] args) throws IOException {
    Security.insertProviderAt(new org.conscrypt.OpenSSLProvider(), 1);

    OkHttpClient bootstrapClient = new OkHttpClient.Builder().build();

    List<String> names = asList("google.com", "graph.facebook.com", "sdflkhfsdlkjdf.ee");

    try {
      System.out.println("uncached\n********\n");
      List<DnsOverHttps> dnsProviders =
          DohProviders.providers(bootstrapClient, false, false, false);
      runBatch(dnsProviders, names);

      Cache dnsCache =
          new Cache(new File("./target/TestDohMain.cache." + System.currentTimeMillis()),
              10 * 1024 * 1024);

      System.out.println("Bad targets\n***********\n");

      HttpUrl url = HttpUrl.get("https://dns.cloudflare.com/.not-so-well-known/run-dmc-query");
      List<DnsOverHttps> badProviders = Collections.singletonList(
          new DnsOverHttps.Builder().client(bootstrapClient).url(url).post(true).build());
      runBatch(badProviders, names);

      System.out.println("cached first run\n****************\n");
      names = asList("google.com", "graph.facebook.com");
      bootstrapClient = bootstrapClient.newBuilder().cache(dnsCache).build();
      dnsProviders = DohProviders.providers(bootstrapClient, true, true, true);
      runBatch(dnsProviders, names);

      System.out.println("cached second run\n*****************\n");
      dnsProviders = DohProviders.providers(bootstrapClient, true, true, true);
      runBatch(dnsProviders, names);
    } finally {
      bootstrapClient.connectionPool().evictAll();
      bootstrapClient.dispatcher().executorService().shutdownNow();
      Cache cache = bootstrapClient.cache();
      if (cache != null) {
        cache.close();
      }
    }
  }

  private static void runBatch(List<DnsOverHttps> dnsProviders, List<String> names) {
    long time = System.currentTimeMillis();

    for (DnsOverHttps dns : dnsProviders) {
      System.out.println("Testing " + dns.url());

      for (String host : names) {
        System.out.print(host + ": ");
        System.out.flush();

        try {
          List<InetAddress> results = dns.lookup(host);
          System.out.println(results);
        } catch (UnknownHostException uhe) {
          Throwable e = uhe;

          while (e != null) {
            System.out.println(e.toString());

            e = e.getCause();
          }
        }
      }

      System.out.println();
    }

    time = System.currentTimeMillis() - time;

    System.out.println("Time: " + (((double) time) / 1000) + " seconds\n");
  }
}
