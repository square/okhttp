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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

class Dispatcher {
  // TODO: thread pool size should be configurable; possibly configurable per host.
  private final ThreadPoolExecutor executorService = new ThreadPoolExecutor(
      8, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
  private final Map<Object, List<Job>> enqueuedJobs = new LinkedHashMap<Object, List<Job>>();

  public synchronized void enqueue(
      HttpURLConnection connection, Request request, Response.Receiver responseReceiver) {
    Job job = new Job(connection, request, responseReceiver);
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

  private synchronized void finished(Job job) {
    List<Job> jobs = enqueuedJobs.get(job.request.tag());
    if (jobs != null) jobs.remove(job);
  }

  public class Job implements Runnable {
    private final HttpURLConnection connection;
    private final Request request;
    private final Response.Receiver responseReceiver;

    public Job(HttpURLConnection connection, Request request, Response.Receiver responseReceiver) {
      this.connection = connection;
      this.request = request;
      this.responseReceiver = responseReceiver;
    }

    @Override public void run() {
      try {
        sendRequest();
        Response response = readResponse();
        responseReceiver.onResponse(response);
      } catch (IOException e) {
        responseReceiver.onFailure(new Failure.Builder()
            .request(request)
            .exception(e)
            .build());
      } finally {
        connection.disconnect();
        finished(this);
      }
    }

    private HttpURLConnection sendRequest()
        throws IOException {
      for (int i = 0; i < request.headerCount(); i++) {
        connection.addRequestProperty(request.headerName(i), request.headerValue(i));
      }
      Request.Body body = request.body();
      if (body != null) {
        connection.setDoOutput(true);
        long contentLength = body.contentLength();
        if (contentLength == -1 || contentLength > Integer.MAX_VALUE) {
          connection.setChunkedStreamingMode(0);
        } else {
          // Don't call setFixedLengthStreamingMode(long); that's only available on Java 1.7+.
          connection.setFixedLengthStreamingMode((int) contentLength);
        }
        body.writeTo(connection.getOutputStream());
      }
      return connection;
    }

    private Response readResponse() throws IOException {
      int responseCode = connection.getResponseCode();
      Response.Builder responseBuilder = new Response.Builder(request, responseCode);

      for (int i = 0; true; i++) {
        String name = connection.getHeaderFieldKey(i);
        if (name == null) break;
        String value = connection.getHeaderField(i);
        responseBuilder.addHeader(name, value);
      }

      responseBuilder.body(new RealResponseBody(connection, connection.getInputStream()));
      // TODO: set redirectedBy
      return responseBuilder.build();
    }
  }

  static class RealResponseBody extends Response.Body {
    private final HttpURLConnection connection;
    private final InputStream in;

    RealResponseBody(HttpURLConnection connection, InputStream in) {
      this.connection = connection;
      this.in = in;
    }

    @Override public String contentType() {
      return connection.getHeaderField("Content-Type");
    }

    @Override public long contentLength() {
      return connection.getContentLength(); // TODO: getContentLengthLong
    }

    @Override public InputStream byteStream() throws IOException {
      return in;
    }
  }
}
