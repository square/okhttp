/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.internal.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.Dns;

import static org.junit.Assert.assertEquals;

public final class FakeDns implements Dns {
  private List<String> requestedHosts = new ArrayList<>();
  private List<InetAddress> addresses = Collections.emptyList();

  /** Sets the addresses to be returned by this fake DNS service. */
  public FakeDns addresses(List<InetAddress> addresses) {
    this.addresses = new ArrayList<>(addresses);
    return this;
  }

  /** Sets the service to throw when a hostname is requested. */
  public FakeDns unknownHost() {
    this.addresses = Collections.emptyList();
    return this;
  }

  public InetAddress address(int index) {
    return addresses.get(index);
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    requestedHosts.add(hostname);
    if (addresses.isEmpty()) throw new UnknownHostException();
    return addresses;
  }

  public void assertRequests(String... expectedHosts) {
    assertEquals(Arrays.asList(expectedHosts), requestedHosts);
    requestedHosts.clear();
  }
}
