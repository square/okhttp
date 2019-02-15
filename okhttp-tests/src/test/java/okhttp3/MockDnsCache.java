package okhttp3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javafx.util.Duration;
import sun.net.util.IPAddressUtil;

final class MockDnsCache {
  // TODO is synchronization required for access/update?
  //private static LinkedHashMap mockPositiveCache;
  //private static Map<String, StubImpl> stubs = new HashMap<>();

  private static Map<String, Object> positiveCache;

  static {
    try {
      ////final LinkedHashMap test2 = new LinkedHashMap();
      //final Field testField = MockDnsCache.class.getDeclaredField("test");
      //System.out.println(testField.getDeclaringClass());
      ////System.out.println(testField.getDeclaringClass());
      ////System.out.println(test2.getClass());
      ////final boolean assignableFrom = testField.getDeclaringClass().isAssignableFrom(test2.getClass());
      ////
      ////System.out.println(assignableFrom);

      final Field addressCacheField = InetAddress.class.getDeclaredField("addressCache");
      addressCacheField.setAccessible(true);
      final Object addressCache = addressCacheField.get(InetAddress.class);

      final Method cacheInit = InetAddress.class.getDeclaredMethod("cacheInitIfNeeded");
      cacheInit.setAccessible(true);
      cacheInit.invoke(addressCache);

      final Field cacheField = addressCache.getClass().getDeclaredField("cache");
      cacheField.setAccessible(true);
      positiveCache = (Map<String, Object>) cacheField.get(addressCache);

      //when(positiveCache.get("a")).thenAnswer(x -> {
      //  throw new RuntimeException("faked");
      //});

      //mockPositiveCache = new MockMap(positiveCache, stubs);
      //mockPositiveCache = new LinkedHashMap();
      //
      //cacheField.set(mockPositiveCache, addressCache);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  //static Stub when(String host) {
  //  final StubImpl stub = new StubImpl(host);
  //  stubs.put(host, stub);
  //  return stub;
  //}

  static void when(String host, String... ips) {
    final Object dnsEntry = createCacheEntry(host, ips);
    positiveCache.put(host, dnsEntry);
  }

  private static Object createCacheEntry(String host, String[] ips) {
    try {
      final Class<?> clazz = Class.forName("java.net.InetAddress$CacheEntry");
      final Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
      ctor.setAccessible(true);

      final InetAddress[] addresses = Arrays.asList(ips).stream()
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
    //mockPositiveCache.clear();
    positiveCache.clear();
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

    public static final class MockMap extends LinkedHashMap {
      private final Map<String, Object> delegate;
      private final Map<String, StubImpl> stubs;

      private MockMap(Map<String, Object> delegate, Map<String, StubImpl> stubs) {
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

  //static class ValueStub implements Stub {
  //  final Object value;
  //
  //  ValueStub(Object value) {
  //    this.value = value;
  //  }
  //}

}
