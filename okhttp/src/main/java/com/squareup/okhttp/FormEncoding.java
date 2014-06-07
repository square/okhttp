// Copyright 2013 Square, Inc.
package com.squareup.okhttp;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Map;

/**
 * <a href="http://www.w3.org/MarkUp/html-spec/html-spec_8.html#SEC8.2.1">HTML 2.0</a>-compliant
 * form data.
 */
public final class FormEncoding implements Part {
  private static final Map<String, String> HEADERS =
      Collections.singletonMap("Content-Type", "application/x-www-form-urlencoded");

  /** Fluent API to build {@link FormEncoding} instances. */
  public static class Builder {
    private final StringBuilder content = new StringBuilder();

    /** Add new key-value pair. */
    public Builder add(String name, String value) {
      if (content.length() > 0) {
        content.append('&');
      }
      try {
        content.append(URLEncoder.encode(name, "UTF-8"))
            .append('=')
            .append(URLEncoder.encode(value, "UTF-8"));
      } catch (UnsupportedEncodingException e) {
        throw new AssertionError(e);
      }
      return this;
    }

    /** Create {@link FormEncoding} instance. */
    public FormEncoding build() {
      if (content.length() == 0) {
        throw new IllegalStateException("Form encoded body must have at least one part.");
      }
      return new FormEncoding(content.toString());
    }
  }

  private final byte[] data;

  private FormEncoding(String data) {
    try {
      this.data = data.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalArgumentException("Unable to convert input to UTF-8: " + data, e);
    }
  }

  @Override public Map<String, String> getHeaders() {
    return HEADERS;
  }

  @Override public void writeBodyTo(OutputStream stream) throws IOException {
    stream.write(data);
  }
}
