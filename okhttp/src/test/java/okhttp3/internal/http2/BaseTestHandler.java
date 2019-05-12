/*
 * Copyright (C) 2013 Square, Inc.
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

import java.io.IOException;
import java.util.List;
import okio.BufferedSource;
import okio.ByteString;

import static org.junit.Assert.fail;

class BaseTestHandler implements Http2Reader.Handler {
  @Override public void data(boolean inFinished, int streamId, BufferedSource source, int length)
      throws IOException {
    fail();
  }

  @Override public void headers(boolean inFinished, int streamId, int associatedStreamId,
      List<Header> headerBlock) {
    fail();
  }

  @Override public void rstStream(int streamId, ErrorCode errorCode) {
    fail();
  }

  @Override public void settings(boolean clearPrevious, Settings settings) {
    fail();
  }

  @Override public void ackSettings() {
    fail();
  }

  @Override public void ping(boolean ack, int payload1, int payload2) {
    fail();
  }

  @Override public void goAway(int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
    fail();
  }

  @Override public void windowUpdate(int streamId, long windowSizeIncrement) {
    fail();
  }

  @Override public void priority(int streamId, int streamDependency, int weight,
      boolean exclusive) {
    fail();
  }

  @Override
  public void pushPromise(int streamId, int associatedStreamId, List<Header> headerBlock) {
    fail();
  }

  @Override public void alternateService(int streamId, String origin, ByteString protocol,
      String host, int port, long maxAge) {
    fail();
  }
}
