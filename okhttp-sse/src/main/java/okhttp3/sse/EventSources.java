/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.sse;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.internal.sse.RealEventSource;

public final class EventSources {
  public static EventSource.Factory createFactory(final OkHttpClient client) {
    return new EventSource.Factory() {
      @Override public EventSource newEventSource(Request request, EventSourceListener listener) {
        RealEventSource eventSource = new RealEventSource(request, listener);
        eventSource.connect(client);
        return eventSource;
      }
    };
  }

  private EventSources() {
    throw new AssertionError();
  }
}
