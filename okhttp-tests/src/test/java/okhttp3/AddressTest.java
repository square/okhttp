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

import java.util.List;
import javax.net.SocketFactory;
import okhttp3.internal.Util;
import okhttp3.internal.http.RecordingProxySelector;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class AddressTest {
  private Dns dns = Dns.SYSTEM;
  private SocketFactory socketFactory = SocketFactory.getDefault();
  private Authenticator authenticator = Authenticator.NONE;
  private List<Protocol> protocols = Util.immutableList(Protocol.HTTP_1_1);
  private List<ConnectionSpec> connectionSpecs = Util.immutableList(ConnectionSpec.MODERN_TLS);
  private RecordingProxySelector proxySelector = new RecordingProxySelector();

  @Test public void equalsAndHashcode() throws Exception {
    Address a = new Address("square.com", 80, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, proxySelector);
    Address b = new Address("square.com", 80, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, proxySelector);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test public void differentProxySelectorsAreDifferent() throws Exception {
    Address a = new Address("square.com", 80, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, new RecordingProxySelector());
    Address b = new Address("square.com", 80, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, new RecordingProxySelector());
    assertFalse(a.equals(b));
  }
}
