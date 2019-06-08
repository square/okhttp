Concurrency
===========

This document describes the concurrency considerations for http/2 connections and the connection pool within OkHttp.

## HTTP/2 Connections

The HttpURLConnection API is a blocking API. You make a blocking write to send a request, and a blocking read to receive the response.

#### Blocking APIs

Blocking APIs are convenient because you get top-to-bottom procedural code without indirection. Network calls work like regular method calls: ask for data and it is returned. If the request fails, you get a stacktrace right were the call was made.

Blocking APIs may be inefficient because you hold a thread idle while waiting on the network. Threads are expensive because they have both a memory overhead and a context-switching overhead.

#### Framed protocols

Framed protocols like http/2 don't lend themselves to blocking APIs. Each application-layer thread wants to do blocking I/O for a specific stream, but the streams are multiplexed on the socket. You can't just talk to the socket, you need to cooperate with the other application-layer threads that you're sharing it with.

Framing rules make it impractical to implement http/2 correctly on a single blocking thread. The flow-control features introduce feedback between reads and writes, requiring writes to acknowledge reads and reads to throttle writes.

In OkHttp we expose a blocking API over a framed protocol. This document explains the code and policy that makes that work.

### Threads

#### Application's calling thread

The application-layer must block on writing I/O. We can't return from a write until we've pushed its bytes onto the socket. Otherwise, if the write fails we are unable to deliver its IOException to the application. We would have told the application layer that the write succeeded, but it didn't!

The application-layer can also do blocking reads. If the application asks to read and there's nothing available, we need to hold that thread until either the bytes arrive, the stream is closed, or a timeout elapses. If we get bytes but there's nobody asking for them, we buffer them. We don't consider bytes as delivered for flow control until they're consumed by the application.

Consider an application streaming a video over http/2. Perhaps the user pauses the video and the application stops reading bytes from this stream. The buffer will fill up, and flow control prevents the server from sending more data on this stream. When the user unpauses her video the buffer drains, the read is acknowledged, and the server proceeds to stream data.

#### Shared reader thread

We can't rely on application threads to read data from the socket. Application threads are transient: sometimes they're reading and writing and sometimes they're off doing application-layer things. But the socket is permanent, and it needs constant attention: we dispatch all incoming frames so the connection is good-to-go when the application layer needs it.

So we have a dedicated thread for every socket that just reads frames and dispatches them.

The reader thread must never run application-layer code. Otherwise one slow stream can hold up the entire connection.

Similarly, the reader thread must never block on writing because this can deadlock the connection. Consider a client and server that both violate this rule. If you get unlucky, they could fill up their TCP buffers (so that writes block) and then use their reader threads to write a frame. Nobody is reading on either end, and the buffers are never drained.

#### Do-stuff-later pool

Sometimes there's an action required like calling the application layer or responding to a ping, and the thread discovering the action is not the thread that should do the work. We enqueue a runnable on this executor and it gets handled by one of the executor's threads.

### Locks

We have 3 different things that we synchronize on.

#### Http2Connection

This lock guards internal state of each connection. This lock is never held for blocking operations. That means that we acquire the lock, read or write a few fields and release the lock. No I/O and no application-layer callbacks.

#### Http2Stream

This lock guards the internal state of each stream. As above, it is never held for blocking operations. When we need to hold an application thread to block a read, we use wait/notify on this lock. This works because the lock is released while `wait()` is waiting.

#### Http2Writer

Socket writes are guarded by the Http2Writer. Only one stream can write at a time so that messages are not interleaved. Writes are either made by application-layer threads or the do-stuff-later pool.

### Holding multiple locks

You're allowed to take the Http2Connection lock while holding the Http2Writer lock. But not vice-versa. Because taking the Http2Writer lock can block.

This is necessary for bookkeeping when creating new streams. Correct framing requires that stream IDs are sequential on the socket, so we need to bundle assigning the ID with sending the `SYN_STREAM` frame.

## Connection Pool

### Background

A primary responsibility for any HTTP client is to efficiently manage network connections. Creating and establishing new connections require a fair amount of overhead and added latency. OkHttp will make every effort to reuse existing connections to avoid this overhead and added latency.

Every OkHttpClient uses a connection pool. Its job is to maintain a reference to all open connections. When an HTTP request is started, OkHttp will attempt to reuse an existing connection from the pool. If there are no existing connections, a new one is created and put into the connection pool. For http/2, the connection can be reused immediately. For http/1, the request must be completed before it can be reused.

Since HTTP requests frequently happen in parallel, the connection pool implementation must be thread-safe.

### ConnectionPool, RealConnection, and StreamAllocation

The primary classes involved with establishing, sharing and terminating connections are ConnectionPool, RealConnection and StreamAllocation.

**ConnectionPool**: Manages reuse of HTTP and HTTP/2 connections for reduced latency. Every OkHttpClient has one, and its lifetime spans the lifetime of the OkHttpClient.

**RealConnection**: The socket and streams of an HTTP and HTTP/2 connection. These are created on demand to fulfill HTTP requests. They may be reused for many HTTP request/response exchanges. Their lifetime is typically shorter than ConnectionPool.

**StreamAllocation**: Coordinates the relationship between connections, streams and calls. These are created for a single HTTP request/response exchange. Their lifetime is typically shorter than RealConnection.

### Locks

A single lock is used to synchronize and guard the state of ConnectionPool, RealConnection and StreamAllocation.

### ConnectionPool

The fields in ConnectionPool, RealConnection and StreamAllocation are all guarded by the connection pool instance. This lock is never held while doing I/O (even closing a socket) to prevent contention.

A single lock is preferred to avoid deadlock scenarios and the added overhead of aggregate lock/unlock that would occur if multiple locks were used.