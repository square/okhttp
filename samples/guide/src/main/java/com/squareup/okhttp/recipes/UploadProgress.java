/*
 * Copyright (C) 2015 Square, Inc.
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
package com.squareup.okhttp.recipes;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;
import okio.Source;

import java.io.File;
import java.io.IOException;

public final class UploadProgress {
  /**
   * The imgur client ID for OkHttp recipes. If you're using imgur for anything
   * other than running these examples, please request your own client ID!
   * https://api.imgur.com/oauth2
   */
  private static final String IMGUR_CLIENT_ID = "9199fdef135c122";
  private static final MediaType MEDIA_TYPE_PNG = MediaType.parse("image/png");

  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    final ProgressListener progressListener = new ProgressListener() {
      @Override public void update(long bytesWritten, long contentLength, boolean done) {
        System.out.println(bytesWritten);
        System.out.println(contentLength);
        System.out.println(done);
        System.out.format("%d%% done\n", (100 * bytesWritten) / contentLength);
      }
    };

    // Use the imgur image upload API as documented at https://api.imgur.com/endpoints/image
    Request request = new Request.Builder()
        .header("Authorization", "Client-ID " + IMGUR_CLIENT_ID)
        .url("https://api.imgur.com/3/image")
        .post(new ProgressRequestBody(
            MEDIA_TYPE_PNG,
            new File("website/static/logo-square.png"),
            progressListener))
        .build();

    Response response = client.newCall(request).execute();
    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

    System.out.println(response.body().string());
  }

  public static void main(String... args) throws Exception {
    new UploadProgress().run();
  }

  private static class ProgressRequestBody extends RequestBody {

    private final ProgressListener progressListener;
    private final MediaType contentType;
    private final File file;

    public ProgressRequestBody(MediaType contentType, File file,
                               ProgressListener progressListener) {
      this.contentType = contentType;
      this.file = file;
      this.progressListener = progressListener;
    }

    @Override public MediaType contentType() {
      return contentType;
    }

    @Override public long contentLength() {
      return file.length();
    }

    @Override public void writeTo(BufferedSink sink) throws IOException {
      BufferedSink bufferedSink = Okio.buffer(sink(sink));
      Source source = Okio.source(file);
      bufferedSink.writeAll(source);
      source.close();
      bufferedSink.close();
    }

    public Sink sink(Sink sink) {
      return new ForwardingSink(sink) {
        long totalBytesWritten = 0L;

        @Override public void write(Buffer source, long byteCount) throws IOException {
          super.write(source, byteCount);
          totalBytesWritten += byteCount;
          progressListener.update(totalBytesWritten, contentLength(),
              totalBytesWritten == contentLength());
        }
      };
    }
  }

  interface ProgressListener {
    void update(long bytesWritten, long contentLength, boolean done);
  }
}
