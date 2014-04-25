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

import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.HttpEngine;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Policy on when async requests are executed.
 *
 * <p>Each dispatcher uses an {@link ExecutorService} to run jobs internally. If you
 * supply your own executor, it should be able to run {@linkplain #getMaxRequests the
 * configured maximum} number of jobs concurrently.
 */
public final class Dispatcher {
  private int maxRequests = 64;
  private int maxRequestsPerHost = 5;

  /** Executes jobs. Created lazily. */
  private ExecutorService executorService;

  /** Ready jobs in the order they'll be run. */
  private final Deque<Job> readyJobs = new ArrayDeque<Job>();

  /** Running jobs. Includes canceled jobs that haven't finished yet. */
  private final Deque<Job> runningJobs = new ArrayDeque<Job>();

  public Dispatcher(ExecutorService executorService) {
    this.executorService = executorService;
  }

  public Dispatcher() {
  }

  public synchronized ExecutorService getExecutorService() {
    if (executorService == null) {
      executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
          new LinkedBlockingQueue<Runnable>(), Util.threadFactory("OkHttp Dispatcher", false));
    }
    return executorService;
  }

  /**
   * Set the maximum number of requests to execute concurrently. Above this
   * requests queue in memory, waiting for the running jobs to complete.
   *
   * <p>If more than {@code maxRequests} requests are in flight when this is
   * invoked, those requests will remain in flight.
   */
  public synchronized void setMaxRequests(int maxRequests) {
    if (maxRequests < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequests);
    }
    this.maxRequests = maxRequests;
    promoteJobs();
  }

  public synchronized int getMaxRequests() {
    return maxRequests;
  }

  /**
   * Set the maximum number of requests for each host to execute concurrently.
   * This limits requests by the URL's host name. Note that concurrent requests
   * to a single IP address may still exceed this limit: multiple hostnames may
   * share an IP address or be routed through the same HTTP proxy.
   *
   * <p>If more than {@code maxRequestsPerHost} requests are in flight when this
   * is invoked, those requests will remain in flight.
   */
  public synchronized void setMaxRequestsPerHost(int maxRequestsPerHost) {
    if (maxRequestsPerHost < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
    }
    this.maxRequestsPerHost = maxRequestsPerHost;
    promoteJobs();
  }

  public synchronized int getMaxRequestsPerHost() {
    return maxRequestsPerHost;
  }

  synchronized void enqueue(OkHttpClient client, Request request, Response.Callback callback) {
    // Copy the client. Otherwise changes (socket factory, redirect policy,
    // etc.) may incorrectly be reflected in the request when it is executed.
    client = client.copyWithDefaults();
    Job job = new Job(this, client, request, callback);

    if (runningJobs.size() < maxRequests && runningJobsForHost(job) < maxRequestsPerHost) {
      runningJobs.add(job);
      getExecutorService().execute(job);
    } else {
      readyJobs.add(job);
    }
  }

  /** Cancel all jobs with the tag {@code tag}. */
  public synchronized void cancel(Object tag) {
    for (Iterator<Job> i = readyJobs.iterator(); i.hasNext(); ) {
      if (Util.equal(tag, i.next().tag())) i.remove();
    }

    for (Job job : runningJobs) {
      if (Util.equal(tag, job.tag())) {
        job.canceled = true;
        HttpEngine engine = job.engine;
        if (engine != null) engine.disconnect();
      }
    }
  }

  /** Used by {@code Job#run} to signal completion. */
  synchronized void finished(Job job) {
    if (!runningJobs.remove(job)) throw new AssertionError("Job wasn't running!");
    promoteJobs();
  }

  private void promoteJobs() {
    if (runningJobs.size() >= maxRequests) return; // Already running max capacity.
    if (readyJobs.isEmpty()) return; // No ready jobs to promote.

    for (Iterator<Job> i = readyJobs.iterator(); i.hasNext(); ) {
      Job job = i.next();

      if (runningJobsForHost(job) < maxRequestsPerHost) {
        i.remove();
        runningJobs.add(job);
        getExecutorService().execute(job);
      }

      if (runningJobs.size() >= maxRequests) return; // Reached max capacity.
    }
  }

  /** Returns the number of running jobs that share a host with {@code job}. */
  private int runningJobsForHost(Job job) {
    int result = 0;
    for (Job j : runningJobs) {
      if (j.host().equals(job.host())) result++;
    }
    return result;
  }
}
