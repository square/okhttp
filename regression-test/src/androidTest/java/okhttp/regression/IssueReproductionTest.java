/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp.regression;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Simple test adaptable to show a failure in older versions of OkHttp
 * or Android SDKs.
 */
@RunWith(AndroidJUnit4.class)
public class IssueReproductionTest {
  @Test public void getFailsWithoutAdditionalCert() throws IOException {
    OkHttpClient client = new OkHttpClient();

    sendRequest(client, "https://google.com/robots.txt");
  }

  private void sendRequest(OkHttpClient client, String url) throws IOException {
    Request request = new Request.Builder()
            .url(url)
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertTrue(response.code() == 200 || response.code() == 404);
      assertEquals(Protocol.HTTP_2, response.protocol());

      for (Certificate c: response.handshake().peerCertificates()) {
        X509Certificate x = (X509Certificate) c;
        System.out.println(x.getSubjectDN());
      }
    }
  }
}
