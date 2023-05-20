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
package okhttp3.internal.publicsuffix

import kotlin.test.assertEquals
import kotlin.test.assertSame
import okhttp3.internal.toCanonicalHost
import okio.Buffer
import okio.FileSystem
import okio.GzipSource
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test

class PublicSuffixDatabaseTest {
  private val publicSuffixDatabase = PublicSuffixDatabase()
  @Test fun longestMatchWins() {
    val buffer = Buffer()
      .writeUtf8("com\n")
      .writeUtf8("my.square.com\n")
      .writeUtf8("square.com\n")
    publicSuffixDatabase.setListBytes(buffer.readByteArray(), byteArrayOf())
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("example.com"))
      .isEqualTo("example.com")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.example.com"))
      .isEqualTo("example.com")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.bar.square.com"))
      .isEqualTo("bar.square.com")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.square.com"))
      .isEqualTo("foo.my.square.com")
  }

  @Test fun wildcardMatch() {
    val buffer = Buffer()
      .writeUtf8("*.square.com\n")
      .writeUtf8("com\n")
      .writeUtf8("example.com\n")
    publicSuffixDatabase.setListBytes(buffer.readByteArray(), byteArrayOf())
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("my.square.com")).isNull()
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.square.com"))
      .isEqualTo("foo.my.square.com")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("bar.foo.my.square.com"))
      .isEqualTo("foo.my.square.com")
  }

  @Test fun boundarySearches() {
    val buffer = Buffer()
      .writeUtf8("bbb\n")
      .writeUtf8("ddd\n")
      .writeUtf8("fff\n")
    publicSuffixDatabase.setListBytes(buffer.readByteArray(), byteArrayOf())
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("aaa")).isNull()
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("ggg")).isNull()
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("ccc")).isNull()
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("eee")).isNull()
  }

  @Test fun exceptionRule() {
    val exception = Buffer()
      .writeUtf8("my.square.jp\n")
    val buffer = Buffer()
      .writeUtf8("*.jp\n")
      .writeUtf8("*.square.jp\n")
      .writeUtf8("example.com\n")
      .writeUtf8("square.com\n")
    publicSuffixDatabase.setListBytes(buffer.readByteArray(), exception.readByteArray())
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("my.square.jp"))
      .isEqualTo("my.square.jp")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.my.square.jp"))
      .isEqualTo("my.square.jp")
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("my1.square.jp")).isNull()
  }

  @Test fun noEffectiveTldPlusOne() {
    val exception = Buffer()
      .writeUtf8("my.square.jp\n")
    val buffer = Buffer()
      .writeUtf8("*.jp\n")
      .writeUtf8("*.square.jp\n")
      .writeUtf8("example.com\n")
      .writeUtf8("square.com\n")
    publicSuffixDatabase.setListBytes(buffer.readByteArray(), exception.readByteArray())
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("example.com")).isNull()
    assertThat(publicSuffixDatabase.getEffectiveTldPlusOne("foo.square.jp")).isNull()
  }

  @Test fun allPublicSuffixes() {
    val buffer = Buffer()
    FileSystem.RESOURCES.source(PublicSuffixDatabase.PUBLIC_SUFFIX_RESOURCE).use { resource ->
        GzipSource(resource).buffer().use { source ->
          val length = source.readInt()
          buffer.write(source, length.toLong())
        }
      }
    while (!buffer.exhausted()) {
      var publicSuffix = buffer.readUtf8LineStrict()
      if (publicSuffix.contains("*")) {
        // A wildcard rule, let's replace the wildcard with a value.
        publicSuffix = publicSuffix.replace("\\*".toRegex(), "square")
      }
      assertThat(publicSuffixDatabase.getEffectiveTldPlusOne(publicSuffix)).isNull()
      val test = "foobar.$publicSuffix"
      assertThat(publicSuffixDatabase.getEffectiveTldPlusOne(test)).isEqualTo(test)
    }
  }

  @Test fun publicSuffixExceptions() {
    val buffer = Buffer()
    FileSystem.RESOURCES.source(PublicSuffixDatabase.PUBLIC_SUFFIX_RESOURCE).use { resource ->
        GzipSource(resource).buffer().use { source ->
          var length = source.readInt()
          source.skip(length.toLong())
          length = source.readInt()
          buffer.write(source, length.toLong())
        }
      }
    while (!buffer.exhausted()) {
      val exception = buffer.readUtf8LineStrict()
      assertThat(publicSuffixDatabase.getEffectiveTldPlusOne(exception)).isEqualTo(
        exception
      )
      val test = "foobar.$exception"
      assertThat(publicSuffixDatabase.getEffectiveTldPlusOne(test)).isEqualTo(exception)
    }
  }

  @Test fun threadIsInterruptedOnFirstRead() {
    Thread.currentThread().interrupt()
    try {
      val result = publicSuffixDatabase.getEffectiveTldPlusOne("squareup.com")
      assertThat(result).isEqualTo("squareup.com")
    } finally {
      assertThat(Thread.interrupted()).isTrue
    }
  }

  @Test fun secondReadFailsSameAsFirst() {
    val badPublicSuffixDatabase = PublicSuffixDatabase(
      path = "/xxx.gz".toPath()
    )
    lateinit var firstFailure: Exception
    try {
      badPublicSuffixDatabase.getEffectiveTldPlusOne("squareup.com")
      fail("Read shouldn't have worked")
    } catch (e: Exception) {
      firstFailure = e
    }
    try {
      badPublicSuffixDatabase.getEffectiveTldPlusOne("squareup.com")
      fail("Read shouldn't have worked")
    } catch (e: Exception) {
      // expected
      assertEquals(firstFailure.toString(), e.toString())
    }
  }

  /** These tests are provided by [publicsuffix.org](https://publicsuffix.org/list/). */
  @Test fun publicSuffixDotOrgTestCases() {
    // Any copyright is dedicated to the Public Domain.
    // https://creativecommons.org/publicdomain/zero/1.0/

    // Mixed case.
    checkPublicSuffix("COM", null)
    checkPublicSuffix("example.COM", "example.com")
    checkPublicSuffix("WwW.example.COM", "example.com")
    // Leading dot.
    checkPublicSuffix(".com", null)
    checkPublicSuffix(".example", null)
    checkPublicSuffix(".example.com", null)
    checkPublicSuffix(".example.example", null)
    // Unlisted TLD.
    checkPublicSuffix("example", null)
    checkPublicSuffix("example.example", "example.example")
    checkPublicSuffix("b.example.example", "example.example")
    checkPublicSuffix("a.b.example.example", "example.example")
    // Listed, but non-Internet, TLD.
    //checkPublicSuffix("local", null);
    //checkPublicSuffix("example.local", null);
    //checkPublicSuffix("b.example.local", null);
    //checkPublicSuffix("a.b.example.local", null);
    // TLD with only 1 rule.
    checkPublicSuffix("biz", null)
    checkPublicSuffix("domain.biz", "domain.biz")
    checkPublicSuffix("b.domain.biz", "domain.biz")
    checkPublicSuffix("a.b.domain.biz", "domain.biz")
    // TLD with some 2-level rules.
    checkPublicSuffix("com", null)
    checkPublicSuffix("example.com", "example.com")
    checkPublicSuffix("b.example.com", "example.com")
    checkPublicSuffix("a.b.example.com", "example.com")
    checkPublicSuffix("uk.com", null)
    checkPublicSuffix("example.uk.com", "example.uk.com")
    checkPublicSuffix("b.example.uk.com", "example.uk.com")
    checkPublicSuffix("a.b.example.uk.com", "example.uk.com")
    checkPublicSuffix("test.ac", "test.ac")
    // TLD with only 1 (wildcard) rule.
    checkPublicSuffix("mm", null)
    checkPublicSuffix("c.mm", null)
    checkPublicSuffix("b.c.mm", "b.c.mm")
    checkPublicSuffix("a.b.c.mm", "b.c.mm")
    // More complex TLD.
    checkPublicSuffix("jp", null)
    checkPublicSuffix("test.jp", "test.jp")
    checkPublicSuffix("www.test.jp", "test.jp")
    checkPublicSuffix("ac.jp", null)
    checkPublicSuffix("test.ac.jp", "test.ac.jp")
    checkPublicSuffix("www.test.ac.jp", "test.ac.jp")
    checkPublicSuffix("kyoto.jp", null)
    checkPublicSuffix("test.kyoto.jp", "test.kyoto.jp")
    checkPublicSuffix("ide.kyoto.jp", null)
    checkPublicSuffix("b.ide.kyoto.jp", "b.ide.kyoto.jp")
    checkPublicSuffix("a.b.ide.kyoto.jp", "b.ide.kyoto.jp")
    checkPublicSuffix("c.kobe.jp", null)
    checkPublicSuffix("b.c.kobe.jp", "b.c.kobe.jp")
    checkPublicSuffix("a.b.c.kobe.jp", "b.c.kobe.jp")
    checkPublicSuffix("city.kobe.jp", "city.kobe.jp")
    checkPublicSuffix("www.city.kobe.jp", "city.kobe.jp")
    // TLD with a wildcard rule and exceptions.
    checkPublicSuffix("ck", null)
    checkPublicSuffix("test.ck", null)
    checkPublicSuffix("b.test.ck", "b.test.ck")
    checkPublicSuffix("a.b.test.ck", "b.test.ck")
    checkPublicSuffix("www.ck", "www.ck")
    checkPublicSuffix("www.www.ck", "www.ck")
    // US K12.
    checkPublicSuffix("us", null)
    checkPublicSuffix("test.us", "test.us")
    checkPublicSuffix("www.test.us", "test.us")
    checkPublicSuffix("ak.us", null)
    checkPublicSuffix("test.ak.us", "test.ak.us")
    checkPublicSuffix("www.test.ak.us", "test.ak.us")
    checkPublicSuffix("k12.ak.us", null)
    checkPublicSuffix("test.k12.ak.us", "test.k12.ak.us")
    checkPublicSuffix("www.test.k12.ak.us", "test.k12.ak.us")
    // IDN labels.
    checkPublicSuffix("食狮.com.cn", "食狮.com.cn")
    checkPublicSuffix("食狮.公司.cn", "食狮.公司.cn")
    checkPublicSuffix("www.食狮.公司.cn", "食狮.公司.cn")
    checkPublicSuffix("shishi.公司.cn", "shishi.公司.cn")
    checkPublicSuffix("公司.cn", null)
    checkPublicSuffix("食狮.中国", "食狮.中国")
    checkPublicSuffix("www.食狮.中国", "食狮.中国")
    checkPublicSuffix("shishi.中国", "shishi.中国")
    checkPublicSuffix("中国", null)
    // Same as above, but punycoded.
    checkPublicSuffix("xn--85x722f.com.cn", "xn--85x722f.com.cn")
    checkPublicSuffix("xn--85x722f.xn--55qx5d.cn", "xn--85x722f.xn--55qx5d.cn")
    checkPublicSuffix("www.xn--85x722f.xn--55qx5d.cn", "xn--85x722f.xn--55qx5d.cn")
    checkPublicSuffix("shishi.xn--55qx5d.cn", "shishi.xn--55qx5d.cn")
    checkPublicSuffix("xn--55qx5d.cn", null)
    checkPublicSuffix("xn--85x722f.xn--fiqs8s", "xn--85x722f.xn--fiqs8s")
    checkPublicSuffix("www.xn--85x722f.xn--fiqs8s", "xn--85x722f.xn--fiqs8s")
    checkPublicSuffix("shishi.xn--fiqs8s", "shishi.xn--fiqs8s")
    checkPublicSuffix("xn--fiqs8s", null)
  }

  private fun checkPublicSuffix(domain: String, registrablePart: String?) {
    val canonicalDomain = domain.toCanonicalHost() ?: return
    val result = publicSuffixDatabase.getEffectiveTldPlusOne(canonicalDomain)
    if (registrablePart == null) {
      assertThat(result).isNull()
    } else {
      assertThat(result).isEqualTo(registrablePart.toCanonicalHost())
    }
  }
}
