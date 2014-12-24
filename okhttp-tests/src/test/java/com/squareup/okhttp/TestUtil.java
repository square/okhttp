package com.squareup.okhttp;

import com.squareup.okhttp.internal.spdy.Header;
import java.util.ArrayList;
import java.util.List;

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
}
