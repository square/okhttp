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
package okhttp3.internal.sse;

import java.io.IOException;
import javax.annotation.Nullable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Util;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okio.BufferedSource;

public final class RealEventSource
    implements EventSource, ServerSentEventReader.Callback, Callback {

  private final Request request;
  private final EventSourceListener listener;

  private Call call;

  public RealEventSource(Request request, EventSourceListener listener) {
    this.request = request;
    this.listener = listener;
  }

  public void connect(OkHttpClient client) {
    client = client.newBuilder()
        .eventListener(EventListener.NONE)
        .build();
    call = client.newCall(request);
    call.enqueue(this);
  }

  @Override public void onResponse(Call call, Response response) {
    processResponse(response);
  }

  public void processResponse(Response response) {
    try {
      //noinspection ConstantConditions main body is never null
      BufferedSource source = response.body().source();
      ServerSentEventReader reader = new ServerSentEventReader(source, this);

      if (!response.isSuccessful()) {
        listener.onFailure(this, null, response);
        return;
      }

      MediaType contentType = response.body().contentType();
      if (!isEventStream(contentType)) {
        listener.onFailure(this,
            new IllegalStateException("Invalid content-type: " + contentType), response);
        return;
      }

      response = response.newBuilder().body(Util.EMPTY_RESPONSE).build();

      try {
        listener.onOpen(this, response);
        while (reader.processNextEvent()) {
        }
      } catch (Exception e) {
        listener.onFailure(this, e, response);
        return;
      }

      listener.onClosed(this);
    } finally {
      response.close();
    }
  }

  private static boolean isEventStream(@Nullable MediaType contentType) {
    return contentType != null && contentType.type().equals("text") && contentType.subtype()
        .equals("event-stream");
  }

  @Override public void onFailure(Call call, IOException e) {
    listener.onFailure(this, e, null);
  }

  @Override public Request request() {
    return request;
  }

  @Override public void cancel() {
    call.cancel();
  }

  @Override public void onEvent(@Nullable String id, @Nullable String type, String data) {
    listener.onEvent(this, id, type, data);
  }

  @Override public void onRetryChange(long timeMs) {
    // Ignored. We do not auto-retry.
  }
}
