/*
 * Copyright (C) 2017 Square, Inc.
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
package okhttp3.internal.publicsuffix;

import java.io.IOException;
import java.io.InputStream;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSource;
import okio.GzipSource;
import okio.Okio;
import org.junit.Test;

import static okhttp3.internal.publicsuffix.PublicSuffixDatabase.PUBLIC_SUFFIX_RESOURCE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PublicSuffixDatabaseTest {
  private final PublicSuffixDatabase publicSuffixDatabase = new PublicSuffixDatabase();

  @Test public void longestMatchWins() {
    Buffer buffer = new Buffer()
        .writeUtf8("com\n")
        .writeUtf8("my.square.com\n")
        .writeUtf8("square.com\n");
    publicSuffixDatabase.setListBytes(buffer.readByteArray(), new byte[]{});

    assertEquals("example.com", publicSuffixDatabase.getEffectiveTldPlusOne("example.com"));
    assertEquals("example.com", publicSuffixDatabase.getEffectiveTldPlusOne("foo.example.com"));
    assertEquals("bar.square.com",
        publicSuffixDatabase.getEffectiveTldPlusOne("foo.bar.square.com"));
    assertEquals("foo.my.square.com",
        publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.square.com"));
  }

  @Test public void wildcardMatch() {
    Buffer buffer = new Buffer()
        .writeUtf8("*.square.com\n")
        .writeUtf8("com\n")
        .writeUtf8("example.com\n");
    publicSuffixDatabase.setListBytes(buffer.readByteArray(), new byte[]{});

    assertNull(publicSuffixDatabase.getEffectiveTldPlusOne("my.square.com"));
    assertEquals("foo.my.square.com",
        publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.square.com"));
    assertEquals("foo.my.square.com",
        publicSuffixDatabase.getEffectiveTldPlusOne("bar.foo.my.square.com"));
  }

  @Test public void boundarySearches() {
    Buffer buffer = new Buffer()
        .writeUtf8("bbb\n")
        .writeUtf8("ddd\n")
        .writeUtf8("fff\n");
    publicSuffixDatabase.setListBytes(buffer.readByteArray(), new byte[]{});

    assertNull(publicSuffixDatabase.getEffectiveTldPlusOne("aaa"));
    assertNull(publicSuffixDatabase.getEffectiveTldPlusOne("ggg"));
    assertNull(publicSuffixDatabase.getEffectiveTldPlusOne("ccc"));
    assertNull(publicSuffixDatabase.getEffectiveTldPlusOne("eee"));
  }

  @Test public void exceptionRule() {
    Buffer exception = new Buffer()
        .writeUtf8("my.square.jp\n");
    Buffer buffer = new Buffer()
        .writeUtf8("*.jp\n")
        .writeUtf8("*.square.jp\n")
        .writeUtf8("example.com\n")
        .writeUtf8("square.com\n");
    publicSuffixDatabase.setListBytes(buffer.readByteArray(), exception.readByteArray());

    assertEquals("my.square.jp", publicSuffixDatabase.getEffectiveTldPlusOne("my.square.jp"));
    assertEquals("my.square.jp", publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.square.jp"));
    assertNull(publicSuffixDatabase.getEffectiveTldPlusOne("my1.square.jp"));
  }

  @Test public void noEffectiveTldPlusOne() {
    Buffer exception = new Buffer()
        .writeUtf8("my.square.jp\n");
    Buffer buffer = new Buffer()
        .writeUtf8("*.jp\n")
        .writeUtf8("*.square.jp\n")
        .writeUtf8("example.com\n")
        .writeUtf8("square.com\n");
    publicSuffixDatabase.setListBytes(buffer.readByteArray(), exception.readByteArray());

    assertNull(publicSuffixDatabase.getEffectiveTldPlusOne("example.com"));
    assertNull(publicSuffixDatabase.getEffectiveTldPlusOne("foo.square.jp"));
  }

  @Test public void allPublicSuffixes() throws IOException {
    InputStream resource = PublicSuffixDatabaseTest.class
        .getResourceAsStream(PUBLIC_SUFFIX_RESOURCE);
    BufferedSource source = Okio.buffer(new GzipSource(Okio.source(resource)));
    int length = source.readInt();
    Buffer buffer = new Buffer();
    buffer.write(source, length);
    resource.close();

    while (!buffer.exhausted()) {
      String publicSuffix = buffer.readUtf8LineStrict();
      if (publicSuffix.contains("*")) {
        // A wildcard rule, let's replace the wildcard with a value.
        publicSuffix = publicSuffix.replaceAll("\\*", "square");
      }
      assertNull(publicSuffixDatabase.getEffectiveTldPlusOne(publicSuffix));

      String test = "foobar." + publicSuffix;
      assertEquals(test, publicSuffixDatabase.getEffectiveTldPlusOne(test));
    }
  }

  @Test public void publicSuffixExceptions() throws IOException {
    InputStream resource = PublicSuffixDatabaseTest.class
        .getResourceAsStream(PUBLIC_SUFFIX_RESOURCE);
    BufferedSource source = Okio.buffer(new GzipSource(Okio.source(resource)));
    int length = source.readInt();
    source.skip(length);

    length = source.readInt();
    Buffer buffer = new Buffer();
    buffer.write(source, length);
    resource.close();

    while (!buffer.exhausted()) {
      String exception = buffer.readUtf8LineStrict();
      assertEquals(exception, publicSuffixDatabase.getEffectiveTldPlusOne(exception));

      String test = "foobar." + exception;
      assertEquals(exception, publicSuffixDatabase.getEffectiveTldPlusOne(test));
    }
  }

  @Test public void threadIsInterruptedOnFirstRead() {
    Thread.currentThread().interrupt();
    try {
      String result = publicSuffixDatabase.getEffectiveTldPlusOne("squareup.com");
      assertEquals("squareup.com", result);
    } finally {
      assertTrue(Thread.interrupted());
    }
  }

  /**
   * These tests are provided by <a href="https://publicsuffix.org/list/">publicsuffix.org</a>.
   */
  @Test public void publicSuffixDotOrgTestCases() {
    // Any copyright is dedicated to the Public Domain.
    // https://creativecommons.org/publicdomain/zero/1.0/

    // null input.
    checkPublicSuffix(null, null);
    // Mixed case.
    checkPublicSuffix("COM", null);
    checkPublicSuffix("example.COM", "example.com");
    checkPublicSuffix("WwW.example.COM", "example.com");
    // Leading dot.
    checkPublicSuffix(".com", null);
    checkPublicSuffix(".example", null);
    checkPublicSuffix(".example.com", null);
    checkPublicSuffix(".example.example", null);
    // Unlisted TLD.
    checkPublicSuffix("example", null);
    checkPublicSuffix("example.example", "example.example");
    checkPublicSuffix("b.example.example", "example.example");
    checkPublicSuffix("a.b.example.example", "example.example");
    // Listed, but non-Internet, TLD.
    //checkPublicSuffix("local", null);
    //checkPublicSuffix("example.local", null);
    //checkPublicSuffix("b.example.local", null);
    //checkPublicSuffix("a.b.example.local", null);
    // TLD with only 1 rule.
    checkPublicSuffix("biz", null);
    checkPublicSuffix("domain.biz", "domain.biz");
    checkPublicSuffix("b.domain.biz", "domain.biz");
    checkPublicSuffix("a.b.domain.biz", "domain.biz");
    // TLD with some 2-level rules.
    checkPublicSuffix("com", null);
    checkPublicSuffix("example.com", "example.com");
    checkPublicSuffix("b.example.com", "example.com");
    checkPublicSuffix("a.b.example.com", "example.com");
    checkPublicSuffix("uk.com", null);
    checkPublicSuffix("example.uk.com", "example.uk.com");
    checkPublicSuffix("b.example.uk.com", "example.uk.com");
    checkPublicSuffix("a.b.example.uk.com", "example.uk.com");
    checkPublicSuffix("test.ac", "test.ac");
    // TLD with only 1 (wildcard) rule.
    checkPublicSuffix("mm", null);
    checkPublicSuffix("c.mm", null);
    checkPublicSuffix("b.c.mm", "b.c.mm");
    checkPublicSuffix("a.b.c.mm", "b.c.mm");
    // More complex TLD.
    checkPublicSuffix("jp", null);
    checkPublicSuffix("test.jp", "test.jp");
    checkPublicSuffix("www.test.jp", "test.jp");
    checkPublicSuffix("ac.jp", null);
    checkPublicSuffix("test.ac.jp", "test.ac.jp");
    checkPublicSuffix("www.test.ac.jp", "test.ac.jp");
    checkPublicSuffix("kyoto.jp", null);
    checkPublicSuffix("test.kyoto.jp", "test.kyoto.jp");
    checkPublicSuffix("ide.kyoto.jp", null);
    checkPublicSuffix("b.ide.kyoto.jp", "b.ide.kyoto.jp");
    checkPublicSuffix("a.b.ide.kyoto.jp", "b.ide.kyoto.jp");
    checkPublicSuffix("c.kobe.jp", null);
    checkPublicSuffix("b.c.kobe.jp", "b.c.kobe.jp");
    checkPublicSuffix("a.b.c.kobe.jp", "b.c.kobe.jp");
    checkPublicSuffix("city.kobe.jp", "city.kobe.jp");
    checkPublicSuffix("www.city.kobe.jp", "city.kobe.jp");
    // TLD with a wildcard rule and exceptions.
    checkPublicSuffix("ck", null);
    checkPublicSuffix("test.ck", null);
    checkPublicSuffix("b.test.ck", "b.test.ck");
    checkPublicSuffix("a.b.test.ck", "b.test.ck");
    checkPublicSuffix("www.ck", "www.ck");
    checkPublicSuffix("www.www.ck", "www.ck");
    // US K12.
    checkPublicSuffix("us", null);
    checkPublicSuffix("test.us", "test.us");
    checkPublicSuffix("www.test.us", "test.us");
    checkPublicSuffix("ak.us", null);
    checkPublicSuffix("test.ak.us", "test.ak.us");
    checkPublicSuffix("www.test.ak.us", "test.ak.us");
    checkPublicSuffix("k12.ak.us", null);
    checkPublicSuffix("test.k12.ak.us", "test.k12.ak.us");
    checkPublicSuffix("www.test.k12.ak.us", "test.k12.ak.us");
    // IDN labels.
    checkPublicSuffix("食狮.com.cn", "食狮.com.cn");
    checkPublicSuffix("食狮.公司.cn", "食狮.公司.cn");
    checkPublicSuffix("www.食狮.公司.cn", "食狮.公司.cn");
    checkPublicSuffix("shishi.公司.cn", "shishi.公司.cn");
    checkPublicSuffix("公司.cn", null);
    checkPublicSuffix("食狮.中国", "食狮.中国");
    checkPublicSuffix("www.食狮.中国", "食狮.中国");
    checkPublicSuffix("shishi.中国", "shishi.中国");
    checkPublicSuffix("中国", null);
    // Same as above, but punycoded.
    checkPublicSuffix("xn--85x722f.com.cn", "xn--85x722f.com.cn");
    checkPublicSuffix("xn--85x722f.xn--55qx5d.cn", "xn--85x722f.xn--55qx5d.cn");
    checkPublicSuffix("www.xn--85x722f.xn--55qx5d.cn", "xn--85x722f.xn--55qx5d.cn");
    checkPublicSuffix("shishi.xn--55qx5d.cn", "shishi.xn--55qx5d.cn");
    checkPublicSuffix("xn--55qx5d.cn", null);
    checkPublicSuffix("xn--85x722f.xn--fiqs8s", "xn--85x722f.xn--fiqs8s");
    checkPublicSuffix("www.xn--85x722f.xn--fiqs8s", "xn--85x722f.xn--fiqs8s");
    checkPublicSuffix("shishi.xn--fiqs8s", "shishi.xn--fiqs8s");
    checkPublicSuffix("xn--fiqs8s", null);
  }

  private void checkPublicSuffix(String domain, String registrablePart) {
    if (domain == null) {
      try {
        publicSuffixDatabase.getEffectiveTldPlusOne(null);
        fail();
      } catch (NullPointerException expected) {
      }
      return;
    }

    String canonicalDomain = Util.canonicalizeHost(domain);
    if (canonicalDomain == null) return;

    String result = publicSuffixDatabase.getEffectiveTldPlusOne(canonicalDomain);
    if (registrablePart == null) {
      assertNull(result);
    } else {
      assertEquals(Util.canonicalizeHost(registrablePart), result);
    }
  }
}
