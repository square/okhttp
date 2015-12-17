package okhttp3;

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
  RecordingCallback callback = new RecordingCallback();
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
    client.newCall(newRequest("http://a/1")).enqueue(callback);
    executor.assertJobs("http://a/1");
  }

  @Test public void maxRequestsEnforced() throws Exception {
    dispatcher.setMaxRequests(3);
    client.newCall(newRequest("http://a/1")).enqueue(callback);
    client.newCall(newRequest("http://a/2")).enqueue(callback);
    client.newCall(newRequest("http://b/1")).enqueue(callback);
    client.newCall(newRequest("http://b/2")).enqueue(callback);
    executor.assertJobs("http://a/1", "http://a/2", "http://b/1");
  }

  @Test public void maxPerHostEnforced() throws Exception {
    dispatcher.setMaxRequestsPerHost(2);
    client.newCall(newRequest("http://a/1")).enqueue(callback);
    client.newCall(newRequest("http://a/2")).enqueue(callback);
    client.newCall(newRequest("http://a/3")).enqueue(callback);
    executor.assertJobs("http://a/1", "http://a/2");
  }

  @Test public void increasingMaxRequestsPromotesJobsImmediately() throws Exception {
    dispatcher.setMaxRequests(2);
    client.newCall(newRequest("http://a/1")).enqueue(callback);
    client.newCall(newRequest("http://b/1")).enqueue(callback);
    client.newCall(newRequest("http://c/1")).enqueue(callback);
    client.newCall(newRequest("http://a/2")).enqueue(callback);
    client.newCall(newRequest("http://b/2")).enqueue(callback);
    dispatcher.setMaxRequests(4);
    executor.assertJobs("http://a/1", "http://b/1", "http://c/1", "http://a/2");
  }

  @Test public void increasingMaxPerHostPromotesJobsImmediately() throws Exception {
    dispatcher.setMaxRequestsPerHost(2);
    client.newCall(newRequest("http://a/1")).enqueue(callback);
    client.newCall(newRequest("http://a/2")).enqueue(callback);
    client.newCall(newRequest("http://a/3")).enqueue(callback);
    client.newCall(newRequest("http://a/4")).enqueue(callback);
    client.newCall(newRequest("http://a/5")).enqueue(callback);
    dispatcher.setMaxRequestsPerHost(4);
    executor.assertJobs("http://a/1", "http://a/2", "http://a/3", "http://a/4");
  }

  @Test public void oldJobFinishesNewJobCanRunDifferentHost() throws Exception {
    dispatcher.setMaxRequests(1);
    client.newCall(newRequest("http://a/1")).enqueue(callback);
    client.newCall(newRequest("http://b/1")).enqueue(callback);
    executor.finishJob("http://a/1");
    executor.assertJobs("http://b/1");
  }

  @Test public void oldJobFinishesNewJobWithSameHostStarts() throws Exception {
    dispatcher.setMaxRequests(2);
    dispatcher.setMaxRequestsPerHost(1);
    client.newCall(newRequest("http://a/1")).enqueue(callback);
    client.newCall(newRequest("http://b/1")).enqueue(callback);
    client.newCall(newRequest("http://b/2")).enqueue(callback);
    client.newCall(newRequest("http://a/2")).enqueue(callback);
    executor.finishJob("http://a/1");
    executor.assertJobs("http://b/1", "http://a/2");
  }

  @Test public void oldJobFinishesNewJobCantRunDueToHostLimit() throws Exception {
    dispatcher.setMaxRequestsPerHost(1);
    client.newCall(newRequest("http://a/1")).enqueue(callback);
    client.newCall(newRequest("http://b/1")).enqueue(callback);
    client.newCall(newRequest("http://a/2")).enqueue(callback);
    executor.finishJob("http://b/1");
    executor.assertJobs("http://a/1");
  }

  @Test public void cancelingRunningJobTakesNoEffectUntilJobFinishes() throws Exception {
    dispatcher.setMaxRequests(1);
    client.newCall(newRequest("http://a/1", "tag1")).enqueue(callback);
    client.newCall(newRequest("http://a/2")).enqueue(callback);
    dispatcher.cancel("tag1");
    executor.assertJobs("http://a/1");
    executor.finishJob("http://a/1");
    executor.assertJobs("http://a/2");
  }

  class RecordingExecutor extends AbstractExecutorService {
    private List<Call.AsyncCall> calls = new ArrayList<>();

    @Override public void execute(Runnable command) {
      calls.add((Call.AsyncCall) command);
    }

    public void assertJobs(String... expectedUrls) {
      List<String> actualUrls = new ArrayList<>();
      for (Call.AsyncCall call : calls) {
        actualUrls.add(call.request().url().toString());
      }
      assertEquals(Arrays.asList(expectedUrls), actualUrls);
    }

    public void finishJob(String url) {
      for (Iterator<Call.AsyncCall> i = calls.iterator(); i.hasNext(); ) {
        Call.AsyncCall call = i.next();
        if (call.request().url().toString().equals(url)) {
          i.remove();
          dispatcher.finished(call);
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
