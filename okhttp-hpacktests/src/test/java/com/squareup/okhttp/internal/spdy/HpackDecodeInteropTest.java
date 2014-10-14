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
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.squareup.okhttp.internal.spdy.hpackjson.HpackJsonUtil.storiesForCurrentDraft;

@RunWith(Parameterized.class)
public class HpackDecodeInteropTest extends HpackDecodeTestBase {

  public HpackDecodeInteropTest(Story story) {
    super(story);
  }

  @Parameterized.Parameters(name="{0}")
  public static Collection<Story[]> createStories() throws Exception {
    return createStories(storiesForCurrentDraft());
  }

  @Test
  public void testGoodDecoderInterop() throws Exception {
    testDecoder();
  }
}
