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
package okhttp3.recipes;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;
import java.io.File;
import java.io.IOException;

public final class UploadProgress {
	private static final String IMGUR_CLIENT_ID = "9199fdef135c122";
	private static final MediaType MEDIA_TYPE_PNG = MediaType.get("image/png");

	private final OkHttpClient client = new OkHttpClient();

	public void run() throws Exception {
		// Use the imgur image upload API as documented at https://api.imgur.com/endpoints/image
		final ProgressListener progressListener = new ProgressListener() {
			boolean firstUpdate = true;

			@Override public void update(long bytesWritten, long contentLength, boolean done) {
				if (done) {
					System.out.println("completed");
				} else {
					if (firstUpdate) {
						firstUpdate = false;
						if (contentLength == -1) {
							System.out.println("content-length: unknown");
						} else {
							System.out.format("content-length: %d\n", contentLength);
						}
					}

					System.out.println(bytesWritten);

					if (contentLength != -1) {
						System.out.format("%d%% done\n", (100 * bytesWritten) / contentLength);
					}
				}
			}
		};

		RequestBody requestBody = RequestBody.create(
			new File("docs/images/logo-square.png"),
			MEDIA_TYPE_PNG);

		Request request = new Request.Builder()
			.header("Authorization", "Client-ID " + IMGUR_CLIENT_ID)
			.url("https://api.imgur.com/3/image")
			.post(new ProgressRequestBody(requestBody, progressListener))
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
		private final RequestBody delegate;

		public ProgressRequestBody(RequestBody delegate, ProgressListener progressListener) {
			this.delegate = delegate;
			this.progressListener = progressListener;
		}

		@Override
		public MediaType contentType() {
			return delegate.contentType();
		}

		@Override
		public long contentLength() throws IOException {
			return delegate.contentLength();
		}

		@Override
		public void writeTo(BufferedSink sink) throws IOException {
			BufferedSink bufferedSink = Okio.buffer(sink(sink));
			delegate.writeTo(bufferedSink);
			bufferedSink.flush();
		}

		public Sink sink(Sink sink) {
			return new ForwardingSink(sink) {
				private long totalBytesWritten = 0L;
				private boolean completed = false;

				@Override
				public void write(Buffer source, long byteCount) throws IOException {
					super.write(source, byteCount);
					totalBytesWritten += byteCount;
					progressListener.update(totalBytesWritten, contentLength(), completed);
				}

				@Override
				public void close() throws IOException {
					super.close();
					if (!completed) {
						completed = true;
						progressListener.update(totalBytesWritten, contentLength(), completed);
					}
				}
			};
		}
	}

	interface ProgressListener {
		void update(long bytesWritten, long contentLength, boolean done);
	}
}
