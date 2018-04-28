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
import java.util.List;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.platform.Platform;
import okio.ByteString;

// TODO implement rules and caching according to spec
public class DnsOverHttps implements Dns {
  private final OkHttpClient client;
  private final String urlPrefix;
  private final BootstrapDns bootstrapAddresses;
  private final boolean includeIPv6;

  public DnsOverHttps(OkHttpClient client, String urlPrefix,
      BootstrapDns bootstrapAddresses, boolean includeIPv6) {
    this.client = client.newBuilder().dns(bootstrapAddresses).build();
    this.urlPrefix = urlPrefix;
    this.bootstrapAddresses = bootstrapAddresses;
    this.includeIPv6 = includeIPv6;
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    List<InetAddress> results = bootstrapAddresses.get(hostname);

    if (results == null) {
      try {
        //System.out.println("Host: " + hostname);

        String query = DnsRecordCodec.encodeQuery(hostname, includeIPv6);

        Request request = buildRequest(query);
        Response response = client.newCall(request).execute();

        if (response.protocol() != Protocol.HTTP_2) {
          Platform.get().log(Platform.WARN, "Incorrect protocol: " + response.protocol(), null);
        }

        // TODO warn on http/1.1?

        try {
          if (!response.isSuccessful()) {
            throw new IOException("response: " + response.code() + " " + response.message());
          }

          ByteString responseBytes = response.body().source().readByteString();

          //System.out.println("Response: " + responseBytes.hex());

          results = DnsRecordCodec.decodeAnswers(hostname, responseBytes);
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
    // TODO implement caching

    HttpUrl url = HttpUrl.parse(urlPrefix + query);

    //System.out.println("URL: " + url);

    Request.Builder builder = new Request.Builder().url(url);

    if (url.host().equals("cloudflare-dns.com")) {
      builder.header("Accept", "application/dns-udpwireformat");
      builder.header("Content-Type", "application/dns-udpwireformat");
    } else {
      builder.header("Accept", "application/dns-message");
    }

    return builder.build();
  }
}