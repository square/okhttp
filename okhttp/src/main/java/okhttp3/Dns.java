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
import java.util.Arrays;
import java.util.List;

/**
 * A domain name service that resolves IP addresses for host names. Most applications will use the
 * {@linkplain #SYSTEM system DNS service}, which is the default. Some applications may provide
 * their own implementation to use a different DNS server, to prefer IPv6 addresses, to prefer IPv4
 * addresses, or to force a specific known IP address.
 *
 * <p>Implementations of this interface must be safe for concurrent use.
 */
public interface Dns {
  /**
   * A DNS that uses {@link InetAddress#getAllByName} to ask the underlying operating system to
   * lookup IP addresses. Most custom {@link Dns} implementations should delegate to this instance.
   */
  Dns SYSTEM = new Dns() {
    @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
      if (hostname == null) throw new UnknownHostException("hostname == null");
      return Arrays.asList(InetAddress.getAllByName(hostname));
    }
  };

  /**
   * Returns the IP addresses of {@code hostname}, in the order they will be attempted by OkHttp. If
   * a connection to an address fails, OkHttp will retry the connection with the next address until
   * either a connection is made, the set of IP addresses is exhausted, or a limit is exceeded.
   */
  List<InetAddress> lookup(String hostname) throws UnknownHostException;
}
