// Copyright 2013 Square, Inc.
package com.squareup.okhttp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Map;

import static com.squareup.okhttp.TestUtils.UTF_8;

public class TestPart implements Part {
  private final byte[] content;

  public TestPart(String content) {
    this.content = content.getBytes(UTF_8);
  }

  @Override public Map<String, String> getHeaders() {
    return Collections.emptyMap();
  }

  @Override public void writeBodyTo(OutputStream out) throws IOException {
    out.write(content);
  }
}
