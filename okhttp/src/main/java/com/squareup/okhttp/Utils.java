// Copyright 2013 Square, Inc.
package com.squareup.okhttp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class Utils {
  private Utils() {
    // No instances.
  }

  static void copyStream(InputStream in, OutputStream out, byte[] buffer) throws IOException {
    int count;
    while ((count = in.read(buffer)) != -1) {
      out.write(buffer, 0, count);
    }
  }

  static void isNotNull(Object obj, String message) {
    if (obj == null) {
      throw new IllegalStateException(message);
    }
  }

  static void isNull(Object obj, String message) {
    if (obj != null) {
      throw new IllegalStateException(message);
    }
  }

  static void isNotEmpty(String thing, String message) {
    isNotNull(thing, message);
    if ("".equals(thing.trim())) {
      throw new IllegalStateException(message);
    }
  }

  static void isNotZero(int value, String message) {
    if (value != 0) {
      throw new IllegalStateException(message);
    }
  }
}
