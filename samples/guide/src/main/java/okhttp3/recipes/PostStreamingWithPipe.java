/*
 * Copyright (C) 2017 Square, Inc.
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
package okhttp3.recipes;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Pipe;

public final class PostStreamingWithPipe {
  public static final MediaType MEDIA_TYPE_MARKDOWN
      = MediaType.parse("text/x-markdown; charset=utf-8");

  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    final PipeBody pipeBody = new PipeBody();

    Request request = new Request.Builder()
        .url("https://api.github.com/markdown/raw")
        .post(pipeBody)
        .build();

    streamPrimesToSinkAsynchronously(pipeBody.sink());

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      System.out.println(response.body().string());
    }
  }

  private void streamPrimesToSinkAsynchronously(final BufferedSink sink) {
    Thread thread = new Thread("writer") {
      @Override public void run() {
        try {
          sink.writeUtf8("Numbers\n");
          sink.writeUtf8("-------\n");
          for (int i = 2; i <= 997; i++) {
            System.out.println(i);
            Thread.sleep(10);
            sink.writeUtf8(String.format(" * %s = %s\n", i, factor(i)));
          }
          sink.close();
        } catch (IOException | InterruptedException e) {
          e.printStackTrace();
        }
      }

      private String factor(int n) {
        for (int i = 2; i < n; i++) {
          int x = n / i;
          if (x * i == n) return factor(x) + " × " + i;
        }
        return Integer.toString(n);
      }
    };

    thread.start();
  }

  /**
   * This request body makes it possible for another thread to stream data to the uploading request.
   * This is potentially useful for posting live event streams like video capture. Callers should
   * write to {@code sink()} and close it to complete the post.
   */
  static final class PipeBody extends RequestBody {
    private final Pipe pipe = new Pipe(8192);
    private final BufferedSink sink = Okio.buffer(pipe.sink());

    public BufferedSink sink() {
      return sink;
    }

    @Override public MediaType contentType() {
      return MEDIA_TYPE_MARKDOWN;
    }

    @Override public void writeTo(BufferedSink sink) throws IOException {
      sink.writeAll(pipe.source());
    }
  }

  public static void main(String... args) throws Exception {
    new PostStreamingWithPipe().run();
  }
}
