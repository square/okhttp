package okhttp3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import okhttp3.internal.SingleInetAddressDns;
import okhttp3.internal.framed.Header;

public final class TestUtil {
  private TestUtil() {
  }

  private static final ConnectionPool connectionPool = new ConnectionPool();

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
        .dns(new SingleInetAddressDns()) // Prevent unexpected fallback addresses.
        .build();
  }

  public static List<Header> headerEntries(String... elements) {
    List<Header> result = new ArrayList<>(elements.length / 2);
    for (int i = 0; i < elements.length; i += 2) {
      result.add(new Header(elements[i], elements[i + 1]));
    }
    return result;
  }

  public static <T> Set<T> setOf(T... elements) {
    return setOf(Arrays.asList(elements));
  }

  public static <T> Set<T> setOf(Collection<T> elements) {
    return new LinkedHashSet<>(elements);
  }

  public static String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }
}
