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
import javax.annotation.Nullable;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.platform.Platform;
import okio.ByteString;

/**
 * DNS over HTTPS implementation.
 *
 * Implementation of https://tools.ietf.org/html/draft-ietf-doh-dns-over-https-07
 *
 * TODO implement rules and caching according to spec
 */
public class DnsOverHttps implements Dns {
  private final OkHttpClient client;
  private final HttpUrl url;
  private final boolean includeIPv6;

  public DnsOverHttps(OkHttpClient client, HttpUrl url,
      @Nullable Dns bootstrapDns, boolean includeIPv6, String method) {
    this.client = bootstrapDns != null ? client.newBuilder().dns(bootstrapDns).build() : client;
    this.url = url;
    this.includeIPv6 = includeIPv6;
    if (!method.equals("GET")) {
      throw new UnsupportedOperationException("Only GET Supported");
    }
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
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

        List<InetAddress> results = DnsRecordCodec.decodeAnswers(hostname, responseBytes);

        return results;
      } finally {
        response.close();
      }
    } catch (Exception e) {
      UnknownHostException unknownHostException = new UnknownHostException(hostname);
      unknownHostException.initCause(e);
      throw unknownHostException;
    }
  }

  private Request buildRequest(String query) {
    // TODO implement caching

    HttpUrl requestUrl = url.newBuilder().addQueryParameter("dns", query).build();

    //System.out.println("URL: " + requestUrl);

    Request.Builder builder = new Request.Builder().url(requestUrl);

    if (url.host().equals("cloudflare-dns.com")) {
      builder.header("Accept", "application/dns-udpwireformat");
      builder.header("Content-Type", "application/dns-udpwireformat");
    } else {
      builder.header("Accept", "application/dns-message");
    }

    return builder.build();
  }
}