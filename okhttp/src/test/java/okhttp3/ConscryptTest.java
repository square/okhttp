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
package okhttp3;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import okhttp3.internal.platform.ConscryptPlatform;
import org.conscrypt.OpenSSLProvider;
import org.junit.After;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConscryptTest {
  private OkHttpClient client = new OkHttpClient();

  @BeforeClass
  public static void setup() {
    assumeConscrypt();

    OpenSSLProvider provider = new OpenSSLProvider();
    Security.insertProviderAt(provider, 1);
  }

  @After
  public void tearDown() {
    TestUtil.ensureAllConnectionsReleased(client);
  }

  private static void assumeConscrypt() {
    Assume.assumeTrue("conscrypt".equals(System.getProperty("okhttp.platform")));
  }

  private static void assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com");
    } catch (UnknownHostException uhe) {
      Assume.assumeNoException(uhe);
    }
  }

  @Test
  public void testMozilla() throws IOException {
    assumeNetwork();

    Request request = new Request.Builder().url("https://mozilla.org/robots.txt").build();

    Response response = client.newCall(request).execute();

    assertThat(response.protocol()).isEqualTo(Protocol.HTTP_2);
    assertThat(response.handshake().tlsVersion()).isEqualTo(TlsVersion.TLS_1_3);
  }

  @Test
  public void testGoogle() throws IOException {
    assumeNetwork();

    Request request = new Request.Builder().url("https://google.com/robots.txt").build();

    Response response = client.newCall(request).execute();

    assertThat(response.protocol()).isEqualTo(Protocol.HTTP_2);
    assertThat(response.handshake().tlsVersion()).isEqualTo(TlsVersion.TLS_1_3);
  }

  @Test
  public void testBuildIfSupported() {
    ConscryptPlatform actual = ConscryptPlatform.buildIfSupported();
    assertThat(actual).isNotNull();
  }

  @Test
  public void testVersion() {
    assertTrue(ConscryptPlatform.atLeastVersion(1, 4, 9));
    assertTrue(ConscryptPlatform.atLeastVersion(2));
    assertTrue(ConscryptPlatform.atLeastVersion(2, 1));
    assertTrue(ConscryptPlatform.atLeastVersion(2, 1, 0));
    assertFalse(ConscryptPlatform.atLeastVersion(2, 1, 1));
    assertFalse(ConscryptPlatform.atLeastVersion(2, 2));
    assertFalse(ConscryptPlatform.atLeastVersion(9));
  }
}
