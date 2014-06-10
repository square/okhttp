/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp;

import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class FormEncodingBuilderTest {
  @Test public void urlEncoding() throws Exception {
    RequestBody formEncoding = new FormEncodingBuilder()
        .add("a&b", "c=d")
        .add("space, the", "final frontier")
        .build();

    assertEquals("application/x-www-form-urlencoded", formEncoding.contentType().toString());

    String expected = "a%26b=c%3Dd&space%2C+the=final+frontier";
    assertEquals(expected.length(), formEncoding.contentLength());

    Buffer out = new Buffer();
    formEncoding.writeTo(out);
    assertEquals(expected, out.readUtf8());
  }

  @Test public void encodedPair() throws Exception {
    RequestBody formEncoding = new FormEncodingBuilder()
        .add("sim", "ple")
        .build();

    String expected = "sim=ple";
    assertEquals(expected.length(), formEncoding.contentLength());

    Buffer buffer = new Buffer();
    formEncoding.writeTo(buffer);
    assertEquals(expected, buffer.readUtf8());
  }

  @Test public void encodeMultiplePairs() throws Exception {
    RequestBody formEncoding = new FormEncodingBuilder()
        .add("sim", "ple")
        .add("hey", "there")
        .add("help", "me")
        .build();

    String expected = "sim=ple&hey=there&help=me";
    assertEquals(expected.length(), formEncoding.contentLength());

    Buffer buffer = new Buffer();
    formEncoding.writeTo(buffer);
    assertEquals(expected, buffer.readUtf8());
  }
}
