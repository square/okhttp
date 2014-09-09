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
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;

/**
 * Known bad tests for HPACK interop.
 */
// TODO: fix these tests (see if the input/test is legit, fix the implementation.)
@Ignore
@RunWith(Parameterized.class)
public class HpackDecodeInteropBadTest extends HpackDecodeTestBase {

  private static final String[] BAD_INTEROP_TESTS = {
      "hyper-hpack",
      "node-http2-protocol",
      "raw-data",
      "twitter-hpack"
  };

  public HpackDecodeInteropBadTest(Story story) {
    super(story);
  }

  @Parameterized.Parameters(name="{0}")
  public static Collection<Story[]> createStories() throws Exception {
    return createStories(BAD_INTEROP_TESTS);
  }

  @Test
  public void testGoodDecoderInterop() throws Exception {
    testDecoder();
  }
}
