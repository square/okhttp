// Copyright 2013 Square, Inc.
package com.squareup.okhttp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PartTest {
  @Test(expected = IllegalStateException.class)
  public void noBodyThrowsException() {
    new Part.Builder().build();
  }

  @Test public void settingHeaderTwiceThrowsException() {
    try {
      new Part.Builder().contentEncoding("foo").contentEncoding("bar");
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      new Part.Builder().contentLanguage("foo").contentLanguage("bar");
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      new Part.Builder().contentLength(42).contentLength(108);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      new Part.Builder().contentType("foo").contentType("bar");
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void settingBodyTwiceThrowsException() {
    try {
      new Part.Builder().body("foo").body("bar");
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      byte[] bytes = { 'a', 'b' };
      new Part.Builder().body(bytes).body(bytes);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      InputStream is = new ByteArrayInputStream(new byte[0]);
      new Part.Builder().body(is).body(is);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      File file = new File("/foo/bar");
      new Part.Builder().body(file).body(file);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void blankHeadersThrowsException() {
    try {
      new Part.Builder().contentType("");
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      new Part.Builder().contentLength(0);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      new Part.Builder().contentLength(-1);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      new Part.Builder().contentLanguage("");
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      new Part.Builder().contentEncoding("");
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void bodyBytesSetsContentLength() {
    Part.Builder b = new Part.Builder();
    assertEquals(0, b.headerLength);
    b.body("1234567");
    assertEquals(7, b.headerLength);
  }

  @Test public void bodyBytesOverridesLength() {
    Part.Builder b = new Part.Builder();
    assertEquals(0, b.headerLength);
    b.contentLength(108);
    assertEquals(108, b.headerLength);
    b.body("1234567");
    assertEquals(7, b.headerLength);
  }

  @Test public void completePart() throws Exception {
    Part p = new Part.Builder() //
        .contentDisposition("form-data; filename=\"foo.txt\"") //
        .contentType("application/json") //
        .contentLength(13) //
        .contentLanguage("English") //
        .contentEncoding("UTF-8") //
        .body("{'foo':'bar'}") //
        .build();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    p.writeBodyTo(baos);
    String actual = new String(baos.toByteArray(), TestUtils.UTF_8);
    assertEquals("{'foo':'bar'}", actual);

    Map<String, String> expectedHeaders = new LinkedHashMap<String, String>();
    expectedHeaders.put("Content-Disposition", "form-data; filename=\"foo.txt\"");
    expectedHeaders.put("Content-Type", "application/json");
    expectedHeaders.put("Content-Length", "13");
    expectedHeaders.put("Content-Language", "English");
    expectedHeaders.put("Content-Transfer-Encoding", "UTF-8");
    assertEquals(expectedHeaders, p.getHeaders());
  }

  @Test public void multipartBodySetsType() throws Exception {
    Multipart m = new Multipart.Builder().addPart(new TestPart("hi")).build();

    try {
      new Part.Builder().body(m).contentType("break me!");
      fail();
    } catch (IllegalStateException expected) {
    }

    Part p = new Part.Builder().body(m).build();
    assertEquals(Collections.singletonMap("Content-Type", m.getHeaders().get("Content-Type")),
        p.getHeaders());
  }
}
