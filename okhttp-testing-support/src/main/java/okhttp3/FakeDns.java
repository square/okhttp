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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public final class FakeDns implements Dns {
  private final Map<String, List<InetAddress>> hostAddresses = new LinkedHashMap<>();
  private final List<String> requestedHosts = new ArrayList<>();
  private int nextAddress = 100;

  /** Sets the results for {@code hostname}. */
  public FakeDns set(String hostname, List<InetAddress> addresses) {
    hostAddresses.put(hostname, addresses);
    return this;
  }

  /** Clears the results for {@code hostname}. */
  public FakeDns clear(String hostname) {
    hostAddresses.remove(hostname);
    return this;
  }

  public InetAddress lookup(String hostname, int index) throws UnknownHostException {
    return hostAddresses.get(hostname).get(index);
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    requestedHosts.add(hostname);

    List<InetAddress> result = hostAddresses.get(hostname);
    if (result != null) return result;

    throw new UnknownHostException();
  }

  public void assertRequests(String... expectedHosts) {
    assertThat(requestedHosts).containsExactly(expectedHosts);
    requestedHosts.clear();
  }

  /** Allocates and returns {@code count} fake addresses like [255.0.0.100, 255.0.0.101]. */
  public List<InetAddress> allocate(int count) {
    try {
      List<InetAddress> result = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        if (nextAddress > 255) {
          throw new AssertionError("too many addresses allocated");
        }
        result.add(InetAddress.getByAddress(
            new byte[] {(byte) 255, (byte) 0, (byte) 0, (byte) nextAddress++}));
      }
      return result;
    } catch (UnknownHostException e) {
      throw new AssertionError();
    }
  }
}
