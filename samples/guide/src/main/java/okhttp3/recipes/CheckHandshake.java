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
package okhttp3.recipes;

import okhttp3.CertificatePinner;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Set;

public final class CheckHandshake {
  /** Rejects otherwise-trusted certificates. */
  private static final Interceptor CHECK_HANDSHAKE_INTERCEPTOR = new Interceptor() {
    Set<String> blacklist = Collections.singleton("sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw=");

    @Override public Response intercept(Chain chain) throws IOException {
      for (Certificate certificate : chain.connection().getHandshake().peerCertificates()) {
        String pin = CertificatePinner.pin(certificate);
        if (blacklist.contains(pin)) {
          throw new IOException("Blacklisted peer certificate: " + pin);
        }
      }
      return chain.proceed(chain.request());
    }
  };

  private final OkHttpClient client = new OkHttpClient();

  public CheckHandshake() {
    client.networkInterceptors().add(CHECK_HANDSHAKE_INTERCEPTOR);
  }

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("https://publicobject.com/helloworld.txt")
        .build();

    Response response = client.newCall(request).execute();
    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

    System.out.println(response.body().string());
  }

  public static void main(String... args) throws Exception {
    new CheckHandshake().run();
  }
}
