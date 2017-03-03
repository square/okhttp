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
package okhttp3;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public final class FakeDns implements Dns {
  private List<String> requestedHosts = new ArrayList<>();
  private List<InetAddress> defaultAddresses = Collections.emptyList();
  private Map<String, List<InetAddress>> hostAddresses = new LinkedHashMap<>();

  /** Sets the defaultAddresses to be returned by this fake DNS service. */
  public FakeDns addresses(List<InetAddress> addresses) {
    this.defaultAddresses = new ArrayList<>(addresses);
    return this;
  }

  /** Sets the service to throw when a hostname is requested. */
  public FakeDns unknownHost() {
    this.defaultAddresses = Collections.emptyList();
    return this;
  }

  /**
   * Defines specific results for a host.
   */
  public FakeDns addHost(String host, List<InetAddress> addresses) {
    hostAddresses.put(host, addresses);
    return this;
  }

  public InetAddress address(int index) {
    return defaultAddresses.get(index);
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    requestedHosts.add(hostname);

    if (hostAddresses.containsKey(hostname)) {
      return hostAddresses.get(hostname);
    }

    if (defaultAddresses.isEmpty()) throw new UnknownHostException();
    return defaultAddresses;
  }

  public void assertRequests(String... expectedHosts) {
    assertEquals(Arrays.asList(expectedHosts), requestedHosts);
    requestedHosts.clear();
  }
}
