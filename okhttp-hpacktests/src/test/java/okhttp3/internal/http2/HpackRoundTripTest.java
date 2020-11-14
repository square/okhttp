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

import java.util.List;
import okhttp3.SimpleProvider;
import okhttp3.internal.http2.hpackjson.Case;
import okhttp3.internal.http2.hpackjson.Story;
import okio.Buffer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

/**
 * Tests for round-tripping headers through hpack..
 */
// TODO: update hpack-test-case with the output of our encoder.
// This test will hide complementary bugs in the encoder and decoder,
// We should test that the encoder is producing responses that are
public class HpackRoundTripTest extends HpackDecodeTestBase {

  private static final String[] RAW_DATA = {"raw-data"};

  static class StoriesTestProvider extends SimpleProvider {
    @NotNull @Override public List<Object> arguments() throws Exception {
      return createStories(RAW_DATA);
    }
  }

  private Buffer bytesOut = new Buffer();
  private Hpack.Writer hpackWriter = new Hpack.Writer(bytesOut);

  @ParameterizedTest
  @ArgumentsSource(StoriesTestProvider.class)
  public void testRoundTrip(Story story) throws Exception {
    Assumptions.assumeFalse(story == Story.MISSING, "Test stories missing, checkout git submodule");

    Story story2 = story.clone();
    // Mutate cases in base class.
    for (Case caze : story2.getCases()) {
      hpackWriter.writeHeaders(caze.getHeaders());
      caze.setWire(bytesOut.readByteString());
    }

    testDecoder(story2);
  }
}
