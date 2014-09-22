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
package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.internal.spdy.hpackjson.Story;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;

/**
 * Known good tests for HPACK interop.
 */
@RunWith(Parameterized.class)
public class HpackDecodeInteropGoodTest extends HpackDecodeTestBase {

  // TODO: Filter on the json key for draft, which explains some of the failures.
  private static final String[] GOOD_INTEROP_TESTS = {
      "go-hpack",
      "haskell-http2-linear",
      "haskell-http2-linear-huffman",
      "haskell-http2-naive",
      "haskell-http2-naive-huffman",
      "haskell-http2-static",
      "haskell-http2-static-huffman",
      "nghttp2",
      "nghttp2-16384-4096",
      "nghttp2-change-table-size",
      "node-http2-hpack",
  };

  public HpackDecodeInteropGoodTest(Story story) {
    super(story);
  }

  @Parameterized.Parameters(name="{0}")
  public static Collection<Story[]> createStories() throws Exception {
    return createStories(GOOD_INTEROP_TESTS);
  }

  @Test
  public void testGoodDecoderInterop() throws Exception {
    testDecoder();
  }
}
