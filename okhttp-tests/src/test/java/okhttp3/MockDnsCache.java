package okhttp3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javafx.util.Duration;
import sun.net.util.IPAddressUtil;

final class MockDnsCache {
  // TODO is synchronization required for access/update?
  private static Map<String, ?> mockPositiveCache;
  private static Map<String, StubImpl> stubs = new HashMap<>();

  static {
    try {
      final Field addressCacheField = InetAddress.class.getDeclaredField("addressCache");
      addressCacheField.setAccessible(true);
      final Object addressCache = addressCacheField.get(InetAddress.class);

      final Method cacheInit = InetAddress.class.getDeclaredMethod("cacheInitIfNeeded");
      cacheInit.setAccessible(true);
      cacheInit.invoke(addressCache);

      final Field cacheField = addressCache.getClass().getDeclaredField("cache");
      cacheField.setAccessible(true);

      Map<String, ?> positiveCache = (Map<String, ?>) cacheField.get(addressCache);
      mockPositiveCache = new MockLinkedHashMap(positiveCache, stubs);
      cacheField.set(addressCache, mockPositiveCache);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static Stub when(String host) {
    final StubImpl stub = new StubImpl(host);
    stubs.put(host, stub);
    return stub;
  }

  private static Object createCacheEntry(String host, String[] ips) {
    try {
      final Class<?> clazz = Class.forName("java.net.InetAddress$CacheEntry");
      final Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
      ctor.setAccessible(true);

      final InetAddress[] addresses = Arrays.stream(ips)
          .map(IPAddressUtil::textToNumericFormatV4)
          .map(bytes -> getByAddress(host, bytes))
          .collect(Collectors.toList())
          .toArray(new InetAddress[ips.length]);

      final Duration expirationSeconds = Duration.seconds(10);
      final long expirationTime = System.currentTimeMillis() + (long) expirationSeconds.toMillis();
      return ctor.newInstance(addresses, expirationTime);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static void clear() {
    mockPositiveCache.clear();
  }

  private static InetAddress getByAddress(String host, byte[] bytes) {
    try {
      return InetAddress.getByAddress(host, bytes);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  interface Stub {
    void thenAnswer(Supplier<?> answer);
    void thenReturn(String... ips);
  }

  private static final class StubImpl implements Stub {
    private final String host;
    private Supplier<?> answer;

    public StubImpl(String host) {
      this.host = host;
    }

    @Override public void thenAnswer(Supplier<?> answer) {
      this.answer = answer;
    }

    @Override public void thenReturn(String... ips) {
      answer = () -> createCacheEntry(host, ips);
    }
  }

    public static final class MockLinkedHashMap extends LinkedHashMap<String, Object> {
      private final Map<String, ?> delegate;
      private final Map<String, StubImpl> stubs;

      private MockLinkedHashMap(Map<String, ?> delegate, Map<String, StubImpl> stubs) {
        this.delegate = delegate;
        this.stubs = stubs;
      }

      @Override public Object get(Object key) {
        final StubImpl stub = stubs.get(key);
        if (stub != null)
          return stub.answer.get();

        return delegate.get(key);
      }
    }

}
