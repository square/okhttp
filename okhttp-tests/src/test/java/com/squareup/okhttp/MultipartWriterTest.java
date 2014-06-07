// Copyright 2013 Square, Inc.
package com.squareup.okhttp;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import org.junit.Test;

import static com.squareup.okhttp.TestUtils.UTF_8;
import static org.junit.Assert.assertEquals;


public class MultipartWriterTest {
  @Test(expected = IllegalStateException.class)
  public void onePartRequired() throws Exception {
    new Multipart.Builder().build();
  }

  @Test public void singlePart() throws Exception {
    String expected = "" //
        + "--123\r\n" //
        + "\r\n" //
        + "Hello, World!\r\n" //
        + "--123--";

    Multipart m = new Multipart.Builder("123")
        .addPart(new TestPart("Hello, World!"))
        .build();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    m.writeBodyTo(out);
    String actual = new String(out.toByteArray(), UTF_8);
    assertEquals(expected, actual);
    assertEquals(Collections.singletonMap("Content-Type", "multipart/mixed; boundary=123"),
        m.getHeaders());
  }

  @Test public void threeParts() throws Exception {
    String expected = ""
        + "--123\r\n"
        + "\r\n"
        + "Quick\r\n"
        + "--123\r\n"
        + "\r\n"
        + "Brown\r\n"
        + "--123\r\n"
        + "\r\n"
        + "Fox\r\n"
        + "--123--";

    Multipart m = new Multipart.Builder("123")
        .addPart(new TestPart("Quick"))
        .addPart(new TestPart("Brown"))
        .addPart(new TestPart("Fox"))
        .build();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    m.writeBodyTo(out);
    String actual = new String(out.toByteArray(), UTF_8);
    assertEquals(expected, actual);
    assertEquals(Collections.singletonMap("Content-Type", "multipart/mixed; boundary=123"),
        m.getHeaders());
  }
}
