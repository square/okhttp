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
package okhttp3.dnsoverhttps;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.CacheControl;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.platform.Platform;
import okhttp3.internal.publicsuffix.PublicSuffixDatabase;
import okio.ByteString;

/**
 * DNS over HTTPS implementation.
 *
 * Implementation of https://tools.ietf.org/html/draft-ietf-doh-dns-over-https-11
 *
 * <blockquote>A DNS API client encodes a single DNS query into an HTTP request
 * using either the HTTP GET or POST method and the other requirements
 * of this section.  The DNS API server defines the URI used by the
 * request through the use of a URI Template.</blockquote>
 *
 * <h3>Warning: This is a non-final API.</h3>
 *
 * <p><strong>As of OkHttp 3.11, this feature is an unstable preview: the API is subject to change,
 * and the implementation is incomplete. We expect that OkHttp 3.12 or 3.13 will finalize this API.
 * Until then, expect API and behavior changes when you update your OkHttp dependency.</strong>
 */
public class DnsOverHttps implements Dns {
  public static final MediaType DNS_MESSAGE = MediaType.parse("application/dns-message");
  public static final MediaType UDPWIREFORMAT = MediaType.parse("application/dns-udpwireformat");
  public static final int MAX_RESPONSE_SIZE = 64 * 1024;
  private final OkHttpClient client;
  private final HttpUrl url;
  private final boolean includeIPv6;
  private final boolean post;
  private final MediaType contentType;
  private final boolean resolvePrivateAddresses;
  private final boolean resolvePublicAddresses;

  DnsOverHttps(Builder builder) {
    if (builder.client == null) {
      throw new NullPointerException("client not set");
    }
    if (builder.url == null) {
      throw new NullPointerException("url not set");
    }

    this.url = builder.url;
    this.includeIPv6 = builder.includeIPv6;
    this.post = builder.post;
    this.contentType = builder.contentType;
    this.resolvePrivateAddresses = builder.resolvePrivateAddresses;
    this.resolvePublicAddresses = builder.resolvePublicAddresses;
    this.client = builder.client.newBuilder().dns(buildBootstrapClient(builder)).build();
  }

  private static Dns buildBootstrapClient(Builder builder) {
    List<InetAddress> hosts = builder.bootstrapDnsHosts;

    if (hosts != null) {
      return new BootstrapDns(builder.url.host(), hosts);
    } else {
      return builder.systemDns;
    }
  }

  public HttpUrl url() {
    return url;
  }

  public boolean post() {
    return post;
  }

  public boolean includeIPv6() {
    return includeIPv6;
  }

  public MediaType contentType() {
    return contentType;
  }

  public OkHttpClient client() {
    return client;
  }

  public boolean resolvePrivateAddresses() {
    return resolvePrivateAddresses;
  }

  public boolean resolvePublicAddresses() {
    return resolvePublicAddresses;
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    UnknownHostException firstUhe = null;

    if (!resolvePrivateAddresses || !resolvePublicAddresses) {
      boolean privateHost = isPrivateHost(hostname);

      if (privateHost && !resolvePrivateAddresses) {
        throw new UnknownHostException("private hosts not resolved");
      }

      if (!privateHost && !resolvePublicAddresses) {
        throw new UnknownHostException("public hosts not resolved");
      }
    }

    return lookupHttps(hostname);
  }

  private List<InetAddress> lookupHttps(String hostname) throws UnknownHostException {
    try {
      ByteString query = DnsRecordCodec.encodeQuery(hostname, includeIPv6);

      Request request = buildRequest(query);
      Response response = executeRequest(request);

      return readResponse(hostname, response);
    } catch (UnknownHostException uhe) {
      throw uhe;
    } catch (Exception e) {
      UnknownHostException unknownHostException = new UnknownHostException(hostname);
      unknownHostException.initCause(e);
      throw unknownHostException;
    }
  }

  private Response executeRequest(Request request) throws IOException {
    // cached request
    if (!post && client.cache() != null) {
      CacheControl cacheControl =
          new CacheControl.Builder().maxStale(Integer.MAX_VALUE, TimeUnit.SECONDS).build();
      Request cacheRequest = request.newBuilder().cacheControl(cacheControl).build();

      Response response = client.newCall(cacheRequest).execute();

      if (response.isSuccessful()) {
        return response;
      }
    }

    return client.newCall(request).execute();
  }

  private List<InetAddress> readResponse(String hostname, Response response) throws Exception {
    if (response.cacheResponse() == null && response.protocol() != Protocol.HTTP_2) {
      Platform.get().log(Platform.WARN, "Incorrect protocol: " + response.protocol(), null);
    }

    try {
      if (!response.isSuccessful()) {
        throw new IOException("response: " + response.code() + " " + response.message());
      }

      ResponseBody body = response.body();

      if (body.contentLength() > MAX_RESPONSE_SIZE) {
        throw new IOException("response size exceeds limit ("
            + MAX_RESPONSE_SIZE
            + " bytes): "
            + body.contentLength()
            + " bytes");
      }

      ByteString responseBytes = body.source().readByteString();

      return DnsRecordCodec.decodeAnswers(hostname, responseBytes);
    } finally {
      response.close();
    }
  }

  private Request buildRequest(ByteString query) {
    Request.Builder requestBuilder = new Request.Builder().header("Accept", contentType.toString());

    if (post) {
      requestBuilder = requestBuilder.url(url).post(RequestBody.create(contentType, query));
    } else {
      String encoded = query.base64Url().replace("=", "");
      HttpUrl requestUrl = url.newBuilder().addQueryParameter("dns", encoded).build();

      requestBuilder = requestBuilder.url(requestUrl);
    }

    return requestBuilder.build();
  }

  static boolean isPrivateHost(String host) {
    return PublicSuffixDatabase.get().getEffectiveTldPlusOne(host) == null;
  }

  public static final class Builder {
    @Nullable OkHttpClient client = null;
    @Nullable HttpUrl url = null;
    boolean includeIPv6 = true;
    boolean post = false;
    MediaType contentType = DNS_MESSAGE;
    Dns systemDns = Dns.SYSTEM;
    @Nullable List<InetAddress> bootstrapDnsHosts = null;
    boolean resolvePrivateAddresses = false;
    boolean resolvePublicAddresses = true;

    public DnsOverHttps build() {
      return new DnsOverHttps(this);
    }

    public Builder client(OkHttpClient client) {
      this.client = client;
      return this;
    }

    public Builder url(HttpUrl url) {
      this.url = url;
      return this;
    }

    public Builder includeIPv6(boolean includeIPv6) {
      this.includeIPv6 = includeIPv6;
      return this;
    }

    public Builder post(boolean post) {
      this.post = post;
      return this;
    }

    public Builder contentType(MediaType contentType) {
      this.contentType = contentType;
      return this;
    }

    public Builder resolvePrivateAddresses(boolean resolvePrivateAddresses) {
      this.resolvePrivateAddresses = resolvePrivateAddresses;
      return this;
    }

    public Builder resolvePublicAddresses(boolean resolvePublicAddresses) {
      this.resolvePublicAddresses = resolvePublicAddresses;
      return this;
    }

    public Builder bootstrapDnsHosts(@Nullable List<InetAddress> bootstrapDnsHosts) {
      this.bootstrapDnsHosts = bootstrapDnsHosts;
      return this;
    }

    public Builder bootstrapDnsHosts(InetAddress... bootstrapDnsHosts) {
      return bootstrapDnsHosts(Arrays.asList(bootstrapDnsHosts));
    }

    public Builder systemDns(Dns systemDns) {
      this.systemDns = systemDns;
      return this;
    }
  }
}
