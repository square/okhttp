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
package okhttp3.sse.internal;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.junit5.StartStop;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EventSourceFactoryTest {

  @StartStop
  private final MockWebServer server = new MockWebServer();

  @Test
  public void testEventSourceFactory() throws Exception {
    OkHttpClient client = new OkHttpClient();
    EventSource.Factory factory = EventSource.Factory.create(client);
    server.enqueue(
      new MockResponse.Builder()
        .body("data: hello\n\n")
        .setHeader("content-type", "text/event-stream")
        .build()
    );
    Request request = new Request.Builder().url(server.url("/")).build();
    CompletableFuture<Void> future = new CompletableFuture<>();
    factory.newEventSource(request, new EventSourceListener() {
      @Override
      public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
        try {
          assertEquals("text/event-stream", response.request().header("Accept"));
        } catch (Exception e) {
          future.completeExceptionally(e);
        }
      }

      @Override
      public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
        try {
          assertEquals("hello", data);
          future.complete(null);
        } catch (Exception e) {
          future.completeExceptionally(e);
        }
      }

      @Override
      public void onClosed(@NotNull EventSource eventSource) {
        future.completeExceptionally(new IllegalStateException("closed"));
      }

      @Override
      public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
        future.completeExceptionally(t == null ? new NullPointerException() : t);
      }
    });
    future.get();
  }

}
