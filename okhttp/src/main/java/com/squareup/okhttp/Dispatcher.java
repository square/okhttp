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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.http.ResponseHeaders;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class Dispatcher {
  // TODO: thread pool size should be configurable; possibly configurable per host.
  private final ThreadPoolExecutor executorService = new ThreadPoolExecutor(
      8, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
  private final Map<Object, List<Job>> enqueuedJobs = new LinkedHashMap<Object, List<Job>>();

  public synchronized void enqueue(
      OkHttpClient client, Request request, Response.Receiver responseReceiver) {
    Job job = new Job(this, client, request, responseReceiver);
    List<Job> jobsForTag = enqueuedJobs.get(request.tag());
    if (jobsForTag == null) {
      jobsForTag = new ArrayList<Job>(2);
      enqueuedJobs.put(request.tag(), jobsForTag);
    }
    jobsForTag.add(job);
    executorService.execute(job);
  }

  public synchronized void cancel(Object tag) {
    List<Job> jobs = enqueuedJobs.remove(tag);
    if (jobs == null) return;
    for (Job job : jobs) {
      executorService.remove(job);
    }
  }

  synchronized void finished(Job job) {
    List<Job> jobs = enqueuedJobs.get(job.tag());
    if (jobs != null) jobs.remove(job);
  }

  static class RealResponseBody extends Response.Body {
    private final ResponseHeaders responseHeaders;
    private final InputStream in;

    RealResponseBody(ResponseHeaders responseHeaders, InputStream in) {
      this.responseHeaders = responseHeaders;
      this.in = in;
    }

    @Override public boolean ready() throws IOException {
      return true;
    }

    @Override public MediaType contentType() {
      String contentType = responseHeaders.getContentType();
      return contentType != null ? MediaType.parse(contentType) : null;
    }

    @Override public long contentLength() {
      return responseHeaders.getContentLength();
    }

    @Override public InputStream byteStream() throws IOException {
      return in;
    }
  }
}
