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
package okhttp3.internal.http2;

import java.util.Collection;
import okhttp3.internal.http2.hpackjson.Story;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static okhttp3.internal.http2.hpackjson.HpackJsonUtil.storiesForCurrentDraft;

@RunWith(Parameterized.class)
public class HpackDecodeInteropTest extends HpackDecodeTestBase {

  public HpackDecodeInteropTest(Story story) {
    super(story);
  }

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Story[]> createStories() throws Exception {
    return createStories(storiesForCurrentDraft());
  }

  @Test
  public void testGoodDecoderInterop() throws Exception {
    Assume.assumeFalse("Test stories missing, checkout git submodule", getStory() == Story.MISSING);

    testDecoder();
  }
}
