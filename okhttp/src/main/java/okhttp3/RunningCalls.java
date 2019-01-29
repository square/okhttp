package okhttp3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Tracks in-flight calls and accumulates statistics about number of calls per host, for use
 * in the {@link Dispatcher}. Includes canceled calls that haven't finished yet.
 */
@NotThreadSafe
final class RunningCalls {

  /** Running asynchronous calls. Includes canceled calls that haven't finished yet. */
  private final Deque<RealCall.AsyncCall> runningAsyncCalls;

  /** Running synchronous calls. Includes canceled calls that haven't finished yet. */
  private final Deque<RealCall> runningSyncCalls;

  /** Number of active calls to each host, excluding websocket connections. */
  private final Map<String, Integer> callsPerHost = new HashMap<>();

  RunningCalls(Deque<RealCall.AsyncCall> runningAsyncCalls, Deque<RealCall> runningSyncCalls) {
    this.runningAsyncCalls = runningAsyncCalls;
    this.runningSyncCalls = runningSyncCalls;
  }

  int callsPerHost(String host) {
    Integer integer = callsPerHost.get(host);
    return integer != null ? integer : 0;
  }

  void addSyncCall(RealCall call) {
    runningSyncCalls.add(call);
    incrementCallPerHost(call.originalRequest.url.host, call.forWebSocket);
  }

  void addAsyncCall(RealCall.AsyncCall call) {
    runningAsyncCalls.add(call);
    incrementCallPerHost(call.host(), call.get().forWebSocket);
  }

  void finishSyncCall(RealCall call) {
    if (!runningSyncCalls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
    decrementCallPerHost(call.request().url.host, call.forWebSocket);
  }

  void finishAsyncCall(RealCall.AsyncCall call) {
    if (!runningAsyncCalls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
    decrementCallPerHost(call.host(), call.get().forWebSocket);
  }

  void cancelAll() {
    for (RealCall.AsyncCall call : runningAsyncCalls) {
      call.get().cancel();
    }

    for (RealCall call : runningSyncCalls) {
      call.cancel();
    }
  }

  int count() {
    return runningAsyncCalls.size() + runningSyncCalls.size();
  }

  List<Call> runningCalls() {
    List<Call> result = new ArrayList<>();
    result.addAll(runningSyncCalls);
    for (RealCall.AsyncCall asyncCall : runningAsyncCalls) {
      result.add(asyncCall.get());
    }
    return Collections.unmodifiableList(result);
  }

  private void incrementCallPerHost(String host, boolean forWebSocket) {
    if (!forWebSocket) {
      Integer integer = callsPerHost.get(host);
      callsPerHost.put(host, integer == null ? 1 : integer + 1);
    }
  }

  private void decrementCallPerHost(String host, boolean forWebSocket) {
    if (!forWebSocket) {
      Integer integer = callsPerHost.get(host);
      callsPerHost.put(host, integer == 1 ? null : integer - 1);
    }
  }
}
