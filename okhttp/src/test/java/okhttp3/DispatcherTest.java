package okhttp3;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class DispatcherTest {
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  RecordingExecutor executor = new RecordingExecutor(this);
  RecordingCallback callback = new RecordingCallback();
  RecordingWebSocketListener webSocketListener = new RecordingWebSocketListener();
  Dispatcher dispatcher = new Dispatcher(executor);
  RecordingEventListener listener = new RecordingEventListener();
  OkHttpClient client = clientTestRule.newClientBuilder()
      .dispatcher(dispatcher)
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build();

  @Before public void setUp() throws Exception {
    dispatcher.setMaxRequests(20);
    dispatcher.setMaxRequestsPerHost(10);
    listener.forbidLock(dispatcher);
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

  @Test public void maxPerHostNotEnforcedForWebSockets() {
    dispatcher.setMaxRequestsPerHost(2);
    client.newWebSocket(newRequest("http://a/1"), webSocketListener);
    client.newWebSocket(newRequest("http://a/2"), webSocketListener);
    client.newWebSocket(newRequest("http://a/3"), webSocketListener);
    executor.assertJobs("http://a/1", "http://a/2", "http://a/3");
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

  @Test public void enqueuedCallsStillRespectMaxCallsPerHost() throws Exception {
    dispatcher.setMaxRequests(1);
    dispatcher.setMaxRequestsPerHost(1);
    client.newCall(newRequest("http://a/1")).enqueue(callback);
    client.newCall(newRequest("http://b/1")).enqueue(callback);
    client.newCall(newRequest("http://b/2")).enqueue(callback);
    client.newCall(newRequest("http://b/3")).enqueue(callback);
    dispatcher.setMaxRequests(3);
    executor.finishJob("http://a/1");
    executor.assertJobs("http://b/1");
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
    assertThat(dispatcher.runningCallsCount()).isEqualTo(3);
    assertThat(dispatcher.queuedCallsCount()).isEqualTo(2);
    assertThat(dispatcher.runningCalls()).containsExactlyInAnyOrder(a1, a2, a3);
    assertThat(dispatcher.queuedCalls()).containsExactlyInAnyOrder(a4, a5);
  }

  @Test public void synchronousCallAccessors() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch waiting = new CountDownLatch(1);
    client = client.newBuilder()
        .addInterceptor(chain -> {
          try {
            ready.countDown();
            waiting.await();
          } catch (InterruptedException e) {
            throw new AssertionError();
          }
          throw new IOException();
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
    assertThat(dispatcher.runningCallsCount()).isEqualTo(2);
    assertThat(dispatcher.queuedCallsCount()).isEqualTo(0);
    assertThat(dispatcher.runningCalls()).containsExactlyInAnyOrder(a1, a2);
    assertThat(dispatcher.queuedCalls()).isEmpty();

    // Cancel some calls. That doesn't impact running or queued.
    a2.cancel();
    a3.cancel();
    assertThat(dispatcher.runningCalls()).containsExactlyInAnyOrder(a1, a2);
    assertThat(dispatcher.queuedCalls()).isEmpty();

    // Let the calls finish.
    waiting.countDown();
    t1.join();
    t2.join();

    // Now we should have 0 running calls and 0 queued calls.
    assertThat(dispatcher.runningCallsCount()).isEqualTo(0);
    assertThat(dispatcher.queuedCallsCount()).isEqualTo(0);
    assertThat(dispatcher.runningCalls()).isEmpty();
    assertThat(dispatcher.queuedCalls()).isEmpty();

    assertThat(a1.isExecuted()).isTrue();
    assertThat(a1.isCanceled()).isFalse();

    assertThat(a2.isExecuted()).isTrue();
    assertThat(a2.isCanceled()).isTrue();

    assertThat(a3.isExecuted()).isFalse();
    assertThat(a3.isCanceled()).isTrue();

    assertThat(a4.isExecuted()).isFalse();
    assertThat(a4.isCanceled()).isFalse();
  }

  @Test public void idleCallbackInvokedWhenIdle() throws Exception {
    AtomicBoolean idle = new AtomicBoolean();
    dispatcher.setIdleCallback(() -> idle.set(true));

    client.newCall(newRequest("http://a/1")).enqueue(callback);
    client.newCall(newRequest("http://a/2")).enqueue(callback);
    executor.finishJob("http://a/1");
    assertThat(idle.get()).isFalse();

    CountDownLatch ready = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    client = client.newBuilder()
        .addInterceptor(chain -> {
          ready.countDown();
          try {
            proceed.await(5, SECONDS);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          return chain.proceed(chain.request());
        })
        .build();

    Thread t1 = makeSynchronousCall(client.newCall(newRequest("http://a/3")));
    ready.await(5, SECONDS);
    executor.finishJob("http://a/2");
    assertThat(idle.get()).isFalse();

    proceed.countDown();
    t1.join();
    assertThat(idle.get()).isTrue();
  }

  @Test public void executionRejectedImmediately() throws Exception {
    Request request = newRequest("http://a/1");
    executor.shutdown();
    client.newCall(request).enqueue(callback);
    callback.await(request.url()).assertFailure(InterruptedIOException.class);
    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "CallFailed");
  }

  @Test public void executionRejectedAfterMaxRequestsChange() throws Exception {
    Request request1 = newRequest("http://a/1");
    Request request2 = newRequest("http://a/2");
    dispatcher.setMaxRequests(1);
    client.newCall(request1).enqueue(callback);
    executor.shutdown();
    client.newCall(request2).enqueue(callback);
    dispatcher.setMaxRequests(2); // Trigger promotion.
    callback.await(request2.url()).assertFailure(InterruptedIOException.class);

    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "CallStart",
        "CallFailed");
  }

  @Test public void executionRejectedAfterMaxRequestsPerHostChange() throws Exception {
    Request request1 = newRequest("http://a/1");
    Request request2 = newRequest("http://a/2");
    dispatcher.setMaxRequestsPerHost(1);
    client.newCall(request1).enqueue(callback);
    executor.shutdown();
    client.newCall(request2).enqueue(callback);
    dispatcher.setMaxRequestsPerHost(2); // Trigger promotion.
    callback.await(request2.url()).assertFailure(InterruptedIOException.class);
    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "CallStart",
        "CallFailed");
  }

  @Test public void executionRejectedAfterPrecedingCallFinishes() throws Exception {
    Request request1 = newRequest("http://a/1");
    Request request2 = newRequest("http://a/2");
    dispatcher.setMaxRequests(1);
    client.newCall(request1).enqueue(callback);
    executor.shutdown();
    client.newCall(request2).enqueue(callback);
    executor.finishJob("http://a/1"); // Trigger promotion.
    callback.await(request2.url()).assertFailure(InterruptedIOException.class);
    assertThat(listener.recordedEventTypes()).containsExactly("CallStart", "CallStart",
        "CallFailed");
  }

  private Thread makeSynchronousCall(Call call) {
    Thread thread = new Thread(() -> {
      try {
        call.execute();
        throw new AssertionError();
      } catch (IOException expected) {
      }
    });
    thread.start();
    return thread;
  }

  private Request newRequest(String url) {
    return new Request.Builder().url(url).build();
  }

  private Request newRequest(String url, String tag) {
    return new Request.Builder().url(url).tag(tag).build();
  }
}
