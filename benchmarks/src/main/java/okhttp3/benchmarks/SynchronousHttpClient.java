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
package okhttp3.benchmarks;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;

/** Any HTTP client with a blocking API. */
abstract class SynchronousHttpClient implements HttpClient {
  ThreadPoolExecutor executor;
  int targetBacklog;

  @Override public void prepare(Benchmark benchmark) {
    this.targetBacklog = benchmark.targetBacklog;
    executor = new ThreadPoolExecutor(benchmark.concurrencyLevel, benchmark.concurrencyLevel,
        1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
  }

  @Override public void enqueue(HttpUrl url) {
    executor.execute(request(url));
  }

  @Override public boolean acceptingJobs() {
    return executor.getQueue().size() < targetBacklog;
  }

  static long readAllAndClose(InputStream in) throws IOException {
    byte[] buffer = new byte[1024];
    long total = 0;
    for (int count; (count = in.read(buffer)) != -1; ) {
      total += count;
    }
    in.close();
    return total;
  }

  abstract Runnable request(HttpUrl url);
}
