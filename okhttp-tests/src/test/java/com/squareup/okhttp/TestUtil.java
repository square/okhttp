package com.squareup.okhttp;

import com.squareup.okhttp.internal.spdy.Header;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class TestUtil {
  private TestUtil() {
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
}
