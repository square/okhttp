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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.squareup.okhttp.internal.spdy.hpackjson.Case;
import com.squareup.okhttp.internal.spdy.hpackjson.HpackJsonUtil;
import com.squareup.okhttp.internal.spdy.hpackjson.Story;
import okio.Buffer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Tests Hpack implementation using https://github.com/http2jp/hpack-test-case/
 */
public class HpackDecodeTestBase {

  /**
   * Reads all stories in the folders provided, asserts if no story found.
   */
  protected static Collection<Story[]> createStories(String[] interopTests)
      throws Exception {
    List<Story[]> result = new ArrayList<>();
    for (String interopTestName : interopTests) {
      List<Story> stories = HpackJsonUtil.readStories(interopTestName);
      if (stories.isEmpty()) {
        fail("No stories for: " + interopTestName);
      }
      for (Story story : stories) {
        result.add(new Story[] { story });
      }
    }
    return result;
  }

  private final Buffer bytesIn = new Buffer();
  private final HpackDraft08.Reader hpackReader = new HpackDraft08.Reader(4096, bytesIn);

  private final Story story;

  public HpackDecodeTestBase(Story story) {
    this.story = story;
  }

  /**
   * Expects wire to be set for all cases, and compares the decoder's output to
   * expected headers.
   */
  protected void testDecoder() throws Exception {
    testDecoder(story);
  }

  protected void testDecoder(Story story) throws Exception {
    for (Case caze : story.getCases()) {
      bytesIn.write(caze.getWire());
      hpackReader.readHeaders();
      hpackReader.emitReferenceSet();
      assertSetEquals(String.format("seqno=%d", caze.getSeqno()), caze.getHeaders(),
          hpackReader.getAndReset());
    }
  }
  /**
   * Checks if {@code expected} and {@code observed} are equal when viewed as a
   * set and headers are deduped.
   *
   * TODO: See if duped headers should be preserved on decode and verify.
   */
  private static void assertSetEquals(
      String message, List<Header> expected, List<Header> observed) {
    assertEquals(message, new LinkedHashSet<>(expected), new LinkedHashSet<>(observed));
  }

  protected Story getStory() {
    return story;
  }
}
