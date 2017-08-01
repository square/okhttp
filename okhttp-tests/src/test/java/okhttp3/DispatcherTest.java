package okhttp3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.RealCall.AsyncCall;
import org.junit.Before;
import org.junit.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static okhttp3.TestUtil.defaultClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DispatcherTest {
  RecordingExecutor executor = new RecordingExecutor();
  RecordingCallback callback = new RecordingCallback();
  Dispatcher dispatcher = new Dispatcher(executor);
  OkHttpClient client = defaultClient().newBuilder()
      .dispatcher(dispatcher)
      .build();

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
    Call c1 = client.newCall(newRequest("http://a/1", "tag1"));
    Call c2 = client.newCall(newRequest("http://a/2"));
    c1.enqueue(callback);
    c2.enqueue(callback);
    c1.cancel();
    executor.assertJobs("http://a/1");
    executor.finishJob("http://a/1");
    executor.assertJobs("http://a/2");
  }

  @Test public void asyncCallAccessors() throws Exception {
    dispatcher.setMaxRequests(3);
    Call a1 = client.newCall(newRequest("http://a/1"));
    Call a2 = client.newCall(newRequest("http://a/2"));
    Call a3 = client.newCall(newRequest("http://a/3"));
    Call a4 = client.newCall(newRequest("http://a/4"));
    Call a5 = client.newCall(newRequest("http://a/5"));
    a1.enqueue(callback);
    a2.enqueue(callback);
    a3.enqueue(callback);
    a4.enqueue(callback);
    a5.enqueue(callback);
    assertEquals(3, dispatcher.runningCallsCount());
    assertEquals(2, dispatcher.queuedCallsCount());
    assertEquals(set(a1, a2, a3), set(dispatcher.runningCalls()));
    assertEquals(set(a4, a5), set(dispatcher.queuedCalls()));
  }

  @Test public void synchronousCallAccessors() throws Exception {
    final CountDownLatch ready = new CountDownLatch(2);
    final CountDownLatch waiting = new CountDownLatch(1);
    client = client.newBuilder()
        .addInterceptor(
            new Interceptor() {
              @Override public Response intercept(Chain chain) throws IOException {
                try {
                  ready.countDown();
                  waiting.await();
                } catch (InterruptedException e) {
                  throw new AssertionError();
                }
                throw new IOException();
              }
            })
        .build();

    Call a1 = client.newCall(newRequest("http://a/1"));
    Call a2 = client.newCall(newRequest("http://a/2"));
    Call a3 = client.newCall(newRequest("http://a/3"));
    Call a4 = client.newCall(newRequest("http://a/4"));
    Thread t1 = makeSynchronousCall(a1);
    Thread t2 = makeSynchronousCall(a2);

    // We created 4 calls and started 2 of them. That's 2 running calls and 0 queued.
    ready.await();
    assertEquals(2, dispatcher.runningCallsCount());
    assertEquals(0, dispatcher.queuedCallsCount());
    assertEquals(set(a1, a2), set(dispatcher.runningCalls()));
    assertEquals(Collections.emptyList(), dispatcher.queuedCalls());

    // Cancel some calls. That doesn't impact running or queued.
    a2.cancel();
    a3.cancel();
    assertEquals(set(a1, a2), set(dispatcher.runningCalls()));
    assertEquals(Collections.emptyList(), dispatcher.queuedCalls());

    // Let the calls finish.
    waiting.countDown();
    t1.join();
    t2.join();

    // Now we should have 0 running calls and 0 queued calls.
    assertEquals(0, dispatcher.runningCallsCount());
    assertEquals(0, dispatcher.queuedCallsCount());
    assertEquals(Collections.emptyList(), dispatcher.runningCalls());
    assertEquals(Collections.emptyList(), dispatcher.queuedCalls());

    assertTrue(a1.isExecuted());
    assertFalse(a1.isCanceled());

    assertTrue(a2.isExecuted());
    assertTrue(a2.isCanceled());

    assertFalse(a3.isExecuted());
    assertTrue(a3.isCanceled());

    assertFalse(a4.isExecuted());
    assertFalse(a4.isCanceled());
  }

  @Test public void idleCallbackInvokedWhenIdle() throws InterruptedException {
    final AtomicBoolean idle = new AtomicBoolean();
    dispatcher.setIdleCallback(new Runnable() {
      @Override public void run() {
        idle.set(true);
      }
    });

    client.newCall(newRequest("http://a/1")).enqueue(callback);
    client.newCall(newRequest("http://a/2")).enqueue(callback);
    executor.finishJob("http://a/1");
    assertFalse(idle.get());

    final CountDownLatch ready = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    client = client.newBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            ready.countDown();
            try {
              proceed.await(5, SECONDS);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            return chain.proceed(chain.request());
          }
        })
        .build();

    Thread t1 = makeSynchronousCall(client.newCall(newRequest("http://a/3")));
    ready.await(5, SECONDS);
    executor.finishJob("http://a/2");
    assertFalse(idle.get());

    proceed.countDown();
    t1.join();
    assertTrue(idle.get());
  }

  private <T> Set<T> set(T... values) {
    return set(Arrays.asList(values));
  }

  private <T> Set<T> set(List<T> list) {
    return new LinkedHashSet<>(list);
  }

  private Thread makeSynchronousCall(final Call call) {
    Thread thread = new Thread() {
      @Override public void run() {
        try {
          call.execute();
          throw new AssertionError();
        } catch (IOException expected) {
        }
      }
    };
    thread.start();
    return thread;
  }

  class RecordingExecutor extends AbstractExecutorService {
    private List<AsyncCall> calls = new ArrayList<>();

    @Override public void execute(Runnable command) {
      calls.add((AsyncCall) command);
    }

    public void assertJobs(String... expectedUrls) {
      List<String> actualUrls = new ArrayList<>();
      calls.forEach(call -> {
        actualUrls.add(call.request().url().toString());
      });
      assertEquals(Arrays.asList(expectedUrls), actualUrls);
    }

    public void finishJob(String url) {
      for (Iterator<AsyncCall> i = calls.iterator(); i.hasNext(); ) {
        AsyncCall call = i.next();
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
