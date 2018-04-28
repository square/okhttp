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
package okhttp3.doh;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static okhttp3.doh.DohProviders.buildCloudflare;
import static okhttp3.doh.DohProviders.buildGoogle;

public class TestDohMain {
  public static void main(String[] args) throws IOException {
    Security.insertProviderAt(new org.conscrypt.OpenSSLProvider(), 1);

    // TODO use cache
    //Cache dnsCache = new Cache(new File("./target/TestDohMain.cache"), 10 * 1024 * 1024);
    OkHttpClient bootstrapClient = new OkHttpClient.Builder()
        //.cache(dnsCache)
        .build();

    try {
      DnsOverHttps dns = buildDns(bootstrapClient);

      System.out.println(dns.lookup("google.com"));
      System.out.println(dns.lookup("www.example.com"));
      //System.out.println(dns.lookup("sdflkhfsdlkjdf.ee"));

      OkHttpClient client = bootstrapClient.newBuilder().dns(dns).cache(null).build();

      Request request = new Request.Builder().url("https://graph.facebook.com/robots.txt").build();
      Response response = client.newCall(request).execute();

      System.out.println(response.code());

      response.close();
    } finally {
      bootstrapClient.connectionPool().evictAll();
      bootstrapClient.dispatcher().executorService().shutdownNow();
      Cache cache = bootstrapClient.cache();
      if (cache != null) {
        cache.close();
      }
    }
  }

  private static DnsOverHttps buildDns(OkHttpClient bootstrapClient) {
    boolean google = true;

    if (google) {
      return buildGoogle(bootstrapClient);
    } else {
      return buildCloudflare(bootstrapClient);
    }
  }
}
