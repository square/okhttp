/*
 * Copyright (C) 2018 Square, Inc.
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
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.internal.http2.Header;

public final class TestUtil {
  public static final InetSocketAddress UNREACHABLE_ADDRESS
      = new InetSocketAddress("198.51.100.1", 8080);

  /**
   * A network that resolves only one IP address per host. Use this when testing route selection
   * fallbacks to prevent the host machine's various IP addresses from interfering.
   */
  private static final Dns SINGLE_INET_ADDRESS_DNS = new Dns() {
    @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
      List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
      return Collections.singletonList(addresses.get(0));
    }
  };

  private TestUtil() {
  }

  private static final ConnectionPool connectionPool = new ConnectionPool();
  private static final Dispatcher dispatcher = new Dispatcher();

  /**
   * Returns an OkHttpClient for all tests to use as a starting point.
   *
   * <p>The shared instance allows all tests to share a single connection pool, which prevents idle
   * connections from consuming unnecessary resources while connections wait to be evicted.
   *
   * <p>This client is also configured to be slightly more deterministic, returning a single IP
   * address for all hosts, regardless of the actual number of IP addresses reported by DNS.
   */
  public static OkHttpClient defaultClient() {
    return new OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .dns(SINGLE_INET_ADDRESS_DNS) // Prevent unexpected fallback addresses.
        .build();
  }

  public static List<Header> headerEntries(String... elements) {
    List<Header> result = new ArrayList<>(elements.length / 2);
    for (int i = 0; i < elements.length; i += 2) {
      result.add(new Header(elements[i], elements[i + 1]));
    }
    return result;
  }

  public static String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }

  /**
   * See FinalizationTester for discussion on how to best trigger GC in tests.
   * https://android.googlesource.com/platform/libcore/+/master/support/src/test/java/libcore/
   * java/lang/ref/FinalizationTester.java
   */
  public static void awaitGarbageCollection() throws Exception {
    Runtime.getRuntime().gc();
    Thread.sleep(100);
    System.runFinalization();
  }
}
