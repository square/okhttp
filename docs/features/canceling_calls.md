Canceling Calls
===============

Call.cancel() Just Works
------------------------

Cancel calls with `Call.cancel()`. It's safe to call from any thread and has no harmful
side effects.


Thread.interrupt() is Clumsy
----------------------------

`Thread.interrupt()` is Java's built-in mechanism to cancel an in-flight `Thread`, regardless of
what work it's currently performing.

We recommend against using `Thread.interrupt()` with OkHttp because it may disrupt shared resources
including HTTP/2 connections and cache files. In particular, calling `Thread.interrupt()` may cause
unrelated threads' call to fail with an `IOException`.

Also avoid these related methods:

 * `Future.cancel(true)` interrupts the thread performing the work.
 * `ExecutorService.shutdownNow()` interrupts all threads owned by the executor service.

The rest of this document describes how OkHttp supports thread interrupts.

### Types of Threads

**Call threads** are threads that perform the HTTP call; these are either owned by the
application (`Call.execute()`) or by OkHttp’s Dispatcher (`Call.enqueue()`).

**OkHttp-internal threads** are helper threads that OkHttp uses to manage shared resources. This
includes HTTP/2 connection reader, connection pool manager, cache journal compaction, fast
fallback, and web socket writes. These are usually managed by OkHttp's internal task runner.

Note that the application’s `EventListeners` may be invoked on either kind of thread, or even by
completely unrelated threads as in `EventListener.canceled()`.

### Types of Interrupts

We define an interrupt type for each thread type:

**Precise interrupts** apply to a specific call thread.

**Broad interrupts** apply to OkHttp-internal threads. This happens when a framework loops over
threads it doesn't own and cancels them.

### Precise Interrupt Policy

**We discourage precise interrupts because they may have collateral damage.** Canceling a thread
that happens to be writing to a shared HTTP/2 connection damages that connection for all calls
sharing that connection. It may cause multiple unrelated calls that share that connection to fail.
Similarly, interrupting a thread that’s writing the cache journal may require the journal to be
rebuilt. We recommend `Call.cancel()` because it achieves the same effect without these drawbacks.

**We support precise interrupts as much as possible.** If a user is using thread interruption, we
treat it like call cancellation for the call the interrupted thread is performing.

To implement this policy, we have code to limit the blast radius of untimely interruption, including
checking the interrupt state before operating on shared resources. We have tests specifically
confirming that precise interrupts don't permanently damage the response cache.

### Broad Interrupt Policy

**We treat broad interrupts as hostile shutdown signals.** If an OkHttp-internal thread is
interrupted, we stop managing shared resources and fail all calls that follow.

Once the connection pool thread is interrupted, OkHttp degrades the connection pool and cancels
every call that attempts to use it.

The same is true for the cache journal connection thread. Once interrupted, OkHttp degrades the
cache and cancels every call that attempts to use it.

Note that it is never necessary to perform broad interrupts.
