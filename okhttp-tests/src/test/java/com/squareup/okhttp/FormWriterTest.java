// Copyright 2013 Square, Inc.
package com.squareup.okhttp;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import org.junit.Test;

import static com.squareup.okhttp.TestUtils.UTF_8;
import static org.junit.Assert.assertEquals;

public class FormWriterTest {
  @Test public void urlEncoding() throws Exception {
    FormEncoding fe = new FormEncoding.Builder() //
        .add("a&b", "c=d") //
        .add("space, the", "final frontier") //
        .build();

    assertEquals(Collections.singletonMap("Content-Type", "application/x-www-form-urlencoded"),
        fe.getHeaders());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    fe.writeBodyTo(out);
    String actual = new String(out.toByteArray(), UTF_8);
    assertEquals("a%26b=c%3Dd&space%2C+the=final+frontier", actual);
  }

  @Test public void encodedPairs() throws Exception {
    FormEncoding fe1 = new FormEncoding.Builder() //
        .add("sim", "ple") //
        .build();

    ByteArrayOutputStream out1 = new ByteArrayOutputStream();
    fe1.writeBodyTo(out1);
    String actual1 = new String(out1.toByteArray(), UTF_8);
    assertEquals("sim=ple", actual1);

    FormEncoding fe2 = new FormEncoding.Builder() //
        .add("sim", "ple") //
        .add("hey", "there") //
        .add("help", "me") //
        .build();

    ByteArrayOutputStream out2 = new ByteArrayOutputStream();
    fe2.writeBodyTo(out2);
    String actual2 = new String(out2.toByteArray(), UTF_8);
    assertEquals("sim=ple&hey=there&help=me", actual2);
  }
}
