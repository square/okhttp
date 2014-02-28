package com.squareup.okhttp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class DispatcherTest {
  RecordingExecutor executor = new RecordingExecutor();
  RecordingReceiver receiver = new RecordingReceiver();
  Dispatcher dispatcher = new Dispatcher(executor);
  OkHttpClient client = new OkHttpClient().setDispatcher(dispatcher);

  @Before public void setUp() throws Exception {
    dispatcher.setMaxRequests(20);
    dispatcher.setMaxRequestsPerHost(10);
  }

  @Test public void maxRequestsZero() throws Exception {
    try {
      dispatcher.setMaxRequests(0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void maxPerHostZero() throws Exception {
    try {
      dispatcher.setMaxRequestsPerHost(0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void enqueuedJobsRunImmediately() throws Exception {
    client.enqueue(newRequest("http://a/1"), receiver);
    executor.assertJobs("http://a/1");
  }

  @Test public void maxRequestsEnforced() throws Exception {
    dispatcher.setMaxRequests(3);
    client.enqueue(newRequest("http://a/1"), receiver);
    client.enqueue(newRequest("http://a/2"), receiver);
    client.enqueue(newRequest("http://b/1"), receiver);
    client.enqueue(newRequest("http://b/2"), receiver);
    executor.assertJobs("http://a/1", "http://a/2", "http://b/1");
  }

  @Test public void maxPerHostEnforced() throws Exception {
    dispatcher.setMaxRequestsPerHost(2);
    client.enqueue(newRequest("http://a/1"), receiver);
    client.enqueue(newRequest("http://a/2"), receiver);
    client.enqueue(newRequest("http://a/3"), receiver);
    executor.assertJobs("http://a/1", "http://a/2");
  }

  @Test public void increasingMaxRequestsPromotesJobsImmediately() throws Exception {
    dispatcher.setMaxRequests(2);
    client.enqueue(newRequest("http://a/1"), receiver);
    client.enqueue(newRequest("http://b/1"), receiver);
    client.enqueue(newRequest("http://c/1"), receiver);
    client.enqueue(newRequest("http://a/2"), receiver);
    client.enqueue(newRequest("http://b/2"), receiver);
    dispatcher.setMaxRequests(4);
    executor.assertJobs("http://a/1", "http://b/1", "http://c/1", "http://a/2");
  }

  @Test public void increasingMaxPerHostPromotesJobsImmediately() throws Exception {
    dispatcher.setMaxRequestsPerHost(2);
    client.enqueue(newRequest("http://a/1"), receiver);
    client.enqueue(newRequest("http://a/2"), receiver);
    client.enqueue(newRequest("http://a/3"), receiver);
    client.enqueue(newRequest("http://a/4"), receiver);
    client.enqueue(newRequest("http://a/5"), receiver);
    dispatcher.setMaxRequestsPerHost(4);
    executor.assertJobs("http://a/1", "http://a/2", "http://a/3", "http://a/4");
  }

  @Test public void oldJobFinishesNewJobCanRunDifferentHost() throws Exception {
    dispatcher.setMaxRequests(1);
    client.enqueue(newRequest("http://a/1"), receiver);
    client.enqueue(newRequest("http://b/1"), receiver);
    executor.finishJob("http://a/1");
    executor.assertJobs("http://b/1");
  }

  @Test public void oldJobFinishesNewJobWithSameHostStarts() throws Exception {
    dispatcher.setMaxRequests(2);
    dispatcher.setMaxRequestsPerHost(1);
    client.enqueue(newRequest("http://a/1"), receiver);
    client.enqueue(newRequest("http://b/1"), receiver);
    client.enqueue(newRequest("http://b/2"), receiver);
    client.enqueue(newRequest("http://a/2"), receiver);
    executor.finishJob("http://a/1");
    executor.assertJobs("http://b/1", "http://a/2");
  }

  @Test public void oldJobFinishesNewJobCantRunDueToHostLimit() throws Exception {
    dispatcher.setMaxRequestsPerHost(1);
    client.enqueue(newRequest("http://a/1"), receiver);
    client.enqueue(newRequest("http://b/1"), receiver);
    client.enqueue(newRequest("http://a/2"), receiver);
    executor.finishJob("http://b/1");
    executor.assertJobs("http://a/1");
  }

  @Test public void cancelingReadyJobPreventsItFromStarting() throws Exception {
    dispatcher.setMaxRequestsPerHost(1);
    client.enqueue(newRequest("http://a/1"), receiver);
    client.enqueue(newRequest("http://a/2", "tag1"), receiver);
    dispatcher.cancel("tag1");
    executor.finishJob("http://a/1");
    executor.assertJobs();
  }

  @Test public void cancelingRunningJobTakesNoEffectUntilJobFinishes() throws Exception {
    dispatcher.setMaxRequests(1);
    client.enqueue(newRequest("http://a/1", "tag1"), receiver);
    client.enqueue(newRequest("http://a/2"), receiver);
    dispatcher.cancel("tag1");
    executor.assertJobs("http://a/1");
    executor.finishJob("http://a/1");
    executor.assertJobs("http://a/2");
  }

  class RecordingExecutor extends AbstractExecutorService {
    private List<Job> jobs = new ArrayList<Job>();

    @Override public void execute(Runnable command) {
      jobs.add((Job) command);
    }

    public void assertJobs(String... expectedUrls) {
      List<String> actualUrls = new ArrayList<String>();
      for (Job job : jobs) {
        actualUrls.add(job.request().urlString());
      }
      assertEquals(Arrays.asList(expectedUrls), actualUrls);
    }

    public void finishJob(String url) {
      for (Iterator<Job> i = jobs.iterator(); i.hasNext(); ) {
        Job job = i.next();
        if (job.request().urlString().equals(url)) {
          i.remove();
          dispatcher.finished(job);
          return;
        }
      }
      throw new AssertionError("No such job: " + url);
    }

    @Override public void shutdown() {
      throw new UnsupportedOperationException();
    }

    @Override public List<Runnable> shutdownNow() {
      throw new UnsupportedOperationException();
    }

    @Override public boolean isShutdown() {
      throw new UnsupportedOperationException();
    }

    @Override public boolean isTerminated() {
      throw new UnsupportedOperationException();
    }

    @Override public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
      throw new UnsupportedOperationException();
    }
  }

  private Request newRequest(String url) {
    return new Request.Builder().url(url).build();
  }

  private Request newRequest(String url, String tag) {
    return new Request.Builder().url(url).tag(tag).build();
  }
}
