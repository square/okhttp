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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import okhttp3.internal.http2.hpackjson.Case;
import okhttp3.internal.http2.hpackjson.HpackJsonUtil;
import okhttp3.internal.http2.hpackjson.Story;
import okio.Buffer;

import static okhttp3.internal.http2.hpackjson.Story.MISSING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests Hpack implementation using https://github.com/http2jp/hpack-test-case/
 */
public class HpackDecodeTestBase {

  /**
   * Reads all stories in the folders provided, asserts if no story found.
   */
  protected static Collection<Story[]> createStories(String[] interopTests)
      throws Exception {
    if (interopTests.length == 0) {
      return Collections.singletonList(new Story[] {MISSING});
    }

    List<Story[]> result = new ArrayList<>();
    for (String interopTestName : interopTests) {
      List<Story> stories = HpackJsonUtil.readStories(interopTestName);
      for (Story story : stories) {
        result.add(new Story[] {story});
      }
    }
    return result;
  }

  private final Buffer bytesIn = new Buffer();
  private final Hpack.Reader hpackReader = new Hpack.Reader(bytesIn, 4096);

  private final Story story;

  public HpackDecodeTestBase(Story story) {
    this.story = story;
  }

  /**
   * Expects wire to be set for all cases, and compares the decoder's output to expected headers.
   */
  protected void testDecoder() throws Exception {
    testDecoder(story);
  }

  protected void testDecoder(Story story) throws Exception {
    for (Case testCase : story.getCases()) {
      bytesIn.write(testCase.getWire());
      hpackReader.readHeaders();
      assertSetEquals(String.format("seqno=%d", testCase.getSeqno()), testCase.getHeaders(),
          hpackReader.getAndResetHeaderList());
    }
  }

  /**
   * Checks if {@code expected} and {@code observed} are equal when viewed as a set and headers are
   * deduped.
   *
   * TODO: See if duped headers should be preserved on decode and verify.
   */
  private static void assertSetEquals(
      String message, List<Header> expected, List<Header> observed) {
    assertThat(new LinkedHashSet<>(observed)).overridingErrorMessage(message).isEqualTo(
        new LinkedHashSet<>(expected));
  }

  protected Story getStory() {
    return story;
  }
}
