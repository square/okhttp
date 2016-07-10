/*
 * Copyright (C) 2011 The Android Open Source Project
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
package okhttp3.internal.http2;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.Protocol;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.Util;
import okhttp3.internal.platform.Platform;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

import static okhttp3.internal.http2.Settings.DEFAULT_INITIAL_WINDOW_SIZE;
import static okhttp3.internal.platform.Platform.INFO;

/**
 * A socket connection to a remote peer. A connection hosts streams which can send and receive
 * data.
 *
 * <p>Many methods in this API are <strong>synchronous:</strong> the call is completed before the
 * method returns. This is typical for Java but atypical for HTTP/2. This is motivated by exception
 * transparency: an IOException that was triggered by a certain caller can be caught and handled by
 * that caller.
 */
public final class Http2Connection implements Closeable {

  // Internal state of this connection is guarded by 'this'. No blocking
  // operations may be performed while holding this lock!
  //
  // Socket writes are guarded by frameWriter.
  //
  // Socket reads are unguarded but are only made by the reader thread.
  //
  // Certain operations (like SYN_STREAM) need to synchronize on both the
  // frameWriter (to do blocking I/O) and this (to create streams). Such
  // operations must synchronize on 'this' last. This ensures that we never
  // wait for a blocking operation while holding 'this'.

  private static final ExecutorService executor = new ThreadPoolExecutor(0,
      Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
      Util.threadFactory("OkHttp FramedConnection", true));

  /** True if this peer initiated the connection. */
  final boolean client;

  /**
   * User code to run in response to incoming streams or settings. Calls to this are always invoked
   * on {@link #executor}.
   */
  private final Listener listener;
  private final Map<Integer, Http2Stream> streams = new HashMap<>();
  private final String hostname;
  private int lastGoodStreamId;
  private int nextStreamId;
  private boolean shutdown;

  /** Ensures push promise callbacks events are sent in order per stream. */
  private final ExecutorService pushExecutor;

  /** Lazily-created map of in-flight pings awaiting a response. Guarded by this. */
  private Map<Integer, Ping> pings;
  /** User code to run in response to push promise events. */
  private final PushObserver pushObserver;
  private int nextPingId;

  /**
   * The total number of bytes consumed by the application, but not yet acknowledged by sending a
   * {@code WINDOW_UPDATE} frame on this connection.
   */
  // Visible for testing
  long unacknowledgedBytesRead = 0;

  /**
   * Count of bytes that can be written on the connection before receiving a window update.
   */
  // Visible for testing
  long bytesLeftInWriteWindow;

  /** Settings we communicate to the peer. */
  Settings okHttpSettings = new Settings();

  private static final int OKHTTP_CLIENT_WINDOW_SIZE = 16 * 1024 * 1024;

  /** Settings we receive from the peer. */
  // TODO: MWS will need to guard on this setting before attempting to push.
  final Settings peerSettings = new Settings();

  private boolean receivedInitialPeerSettings = false;
  final Socket socket;
  final Http2Writer writer;

  // Visible for testing
  final ReaderRunnable readerRunnable;

  private Http2Connection(Builder builder) {
    pushObserver = builder.pushObserver;
    client = builder.client;
    listener = builder.listener;
    // http://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-5.1.1
    nextStreamId = builder.client ? 1 : 2;
    if (builder.client) {
      nextStreamId += 2; // In HTTP/2, 1 on client is reserved for Upgrade.
    }

    nextPingId = builder.client ? 1 : 2;

    // Flow control was designed more for servers, or proxies than edge clients.
    // If we are a client, set the flow control window to 16MiB.  This avoids
    // thrashing window updates every 64KiB, yet small enough to avoid blowing
    // up the heap.
    if (builder.client) {
      okHttpSettings.set(Settings.INITIAL_WINDOW_SIZE, OKHTTP_CLIENT_WINDOW_SIZE);
    }

    hostname = builder.hostname;

    // Like newSingleThreadExecutor, except lazy creates the thread.
    pushExecutor = new ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(),
        Util.threadFactory(Util.format("OkHttp %s Push Observer", hostname), true));
    peerSettings.set(Settings.INITIAL_WINDOW_SIZE, DEFAULT_INITIAL_WINDOW_SIZE);
    peerSettings.set(Settings.MAX_FRAME_SIZE, Http2.INITIAL_MAX_FRAME_SIZE);
    bytesLeftInWriteWindow = peerSettings.getInitialWindowSize();
    socket = builder.socket;
    writer = new Http2Writer(builder.sink, client);

    readerRunnable = new ReaderRunnable(new Http2Reader(builder.source, client));
  }

  /** The protocol as selected using ALPN. */
  public Protocol getProtocol() {
    return Protocol.HTTP_2;
  }

  /**
   * Returns the number of {@link Http2Stream#isOpen() open streams} on this connection.
   */
  public synchronized int openStreamCount() {
    return streams.size();
  }

  synchronized Http2Stream getStream(int id) {
    return streams.get(id);
  }

  synchronized Http2Stream removeStream(int streamId) {
    Http2Stream stream = streams.remove(streamId);
    notifyAll(); // The removed stream may be blocked on a connection-wide window update.
    return stream;
  }

  public synchronized int maxConcurrentStreams() {
    return peerSettings.getMaxConcurrentStreams(Integer.MAX_VALUE);
  }

  /**
   * Returns a new server-initiated stream.
   *
   * @param associatedStreamId the stream that triggered the sender to create this stream.
   * @param out true to create an output stream that we can use to send data to the remote peer.
   * Corresponds to {@code FLAG_FIN}.
   */
  public Http2Stream pushStream(int associatedStreamId, List<Header> requestHeaders, boolean out)
      throws IOException {
    if (client) throw new IllegalStateException("Client cannot push requests.");
    return newStream(associatedStreamId, requestHeaders, out);
  }

  /**
   * Returns a new locally-initiated stream.
   * @param out true to create an output stream that we can use to send data to the remote peer.
   * Corresponds to {@code FLAG_FIN}.
   */
  public Http2Stream newStream(List<Header> requestHeaders, boolean out) throws IOException {
    return newStream(0, requestHeaders, out);
  }

  private Http2Stream newStream(
      int associatedStreamId, List<Header> requestHeaders, boolean out) throws IOException {
    boolean outFinished = !out;
    boolean inFinished = false;
    boolean flushHeaders;
    Http2Stream stream;
    int streamId;

    synchronized (writer) {
      synchronized (this) {
        if (shutdown) {
          throw new IOException("shutdown");
        }
        streamId = nextStreamId;
        nextStreamId += 2;
        stream = new Http2Stream(streamId, this, outFinished, inFinished, requestHeaders);
        flushHeaders = !out || bytesLeftInWriteWindow == 0L || stream.bytesLeftInWriteWindow == 0L;
        if (stream.isOpen()) {
          streams.put(streamId, stream);
        }
      }
      if (associatedStreamId == 0) {
        writer.synStream(outFinished, streamId, associatedStreamId, requestHeaders);
      } else if (client) {
        throw new IllegalArgumentException("client streams shouldn't have associated stream IDs");
      } else { // HTTP/2 has a PUSH_PROMISE frame.
        writer.pushPromise(associatedStreamId, streamId, requestHeaders);
      }
    }

    if (flushHeaders) {
      writer.flush();
    }

    return stream;
  }

  void writeSynReply(int streamId, boolean outFinished, List<Header> alternating)
      throws IOException {
    writer.synReply(outFinished, streamId, alternating);
  }

  /**
   * Callers of this method are not thread safe, and sometimes on application threads. Most often,
   * this method will be called to send a buffer worth of data to the peer.
   *
   * <p>Writes are subject to the write window of the stream and the connection. Until there is a
   * window sufficient to send {@code byteCount}, the caller will block. For example, a user of
   * {@code HttpURLConnection} who flushes more bytes to the output stream than the connection's
   * write window will block.
   *
   * <p>Zero {@code byteCount} writes are not subject to flow control and will not block. The only
   * use case for zero {@code byteCount} is closing a flushed output stream.
   */
  public void writeData(int streamId, boolean outFinished, Buffer buffer, long byteCount)
      throws IOException {
    if (byteCount == 0) { // Empty data frames are not flow-controlled.
      writer.data(outFinished, streamId, buffer, 0);
      return;
    }

    while (byteCount > 0) {
      int toWrite;
      synchronized (Http2Connection.this) {
        try {
          while (bytesLeftInWriteWindow <= 0) {
            // Before blocking, confirm that the stream we're writing is still open. It's possible
            // that the stream has since been closed (such as if this write timed out.)
            if (!streams.containsKey(streamId)) {
              throw new IOException("stream closed");
            }
            Http2Connection.this.wait(); // Wait until we receive a WINDOW_UPDATE.
          }
        } catch (InterruptedException e) {
          throw new InterruptedIOException();
        }

        toWrite = (int) Math.min(byteCount, bytesLeftInWriteWindow);
        toWrite = Math.min(toWrite, writer.maxDataLength());
        bytesLeftInWriteWindow -= toWrite;
      }

      byteCount -= toWrite;
      writer.data(outFinished && byteCount == 0, streamId, buffer, toWrite);
    }
  }

  /**
   * {@code delta} will be negative if a settings frame initial window is smaller than the last.
   */
  void addBytesToWriteWindow(long delta) {
    bytesLeftInWriteWindow += delta;
    if (delta > 0) Http2Connection.this.notifyAll();
  }

  void writeSynResetLater(final int streamId, final ErrorCode errorCode) {
    executor.submit(new NamedRunnable("OkHttp %s stream %d", hostname, streamId) {
      @Override public void execute() {
        try {
          writeSynReset(streamId, errorCode);
        } catch (IOException ignored) {
        }
      }
    });
  }

  void writeSynReset(int streamId, ErrorCode statusCode) throws IOException {
    writer.rstStream(streamId, statusCode);
  }

  void writeWindowUpdateLater(final int streamId, final long unacknowledgedBytesRead) {
    executor.execute(new NamedRunnable("OkHttp Window Update %s stream %d", hostname, streamId) {
      @Override public void execute() {
        try {
          writer.windowUpdate(streamId, unacknowledgedBytesRead);
        } catch (IOException ignored) {
        }
      }
    });
  }

  /**
   * Sends a ping frame to the peer. Use the returned object to await the ping's response and
   * observe its round trip time.
   */
  public Ping ping() throws IOException {
    Ping ping = new Ping();
    int pingId;
    synchronized (this) {
      if (shutdown) {
        throw new IOException("shutdown");
      }
      pingId = nextPingId;
      nextPingId += 2;
      if (pings == null) pings = new HashMap<>();
      pings.put(pingId, ping);
    }
    writePing(false, pingId, 0x4f4b6f6b /* ASCII "OKok" */, ping);
    return ping;
  }

  private void writePingLater(
      final boolean reply, final int payload1, final int payload2, final Ping ping) {
    executor.execute(new NamedRunnable("OkHttp %s ping %08x%08x",
        hostname, payload1, payload2) {
      @Override public void execute() {
        try {
          writePing(reply, payload1, payload2, ping);
        } catch (IOException ignored) {
        }
      }
    });
  }

  private void writePing(boolean reply, int payload1, int payload2, Ping ping) throws IOException {
    synchronized (writer) {
      // Observe the sent time immediately before performing I/O.
      if (ping != null) ping.send();
      writer.ping(reply, payload1, payload2);
    }
  }

  private synchronized Ping removePing(int id) {
    return pings != null ? pings.remove(id) : null;
  }

  public void flush() throws IOException {
    writer.flush();
  }

  /**
   * Degrades this connection such that new streams can neither be created locally, nor accepted
   * from the remote peer. Existing streams are not impacted. This is intended to permit an endpoint
   * to gracefully stop accepting new requests without harming previously established streams.
   */
  public void shutdown(ErrorCode statusCode) throws IOException {
    synchronized (writer) {
      int lastGoodStreamId;
      synchronized (this) {
        if (shutdown) {
          return;
        }
        shutdown = true;
        lastGoodStreamId = this.lastGoodStreamId;
      }
      // TODO: propagate exception message into debugData
      writer.goAway(lastGoodStreamId, statusCode, Util.EMPTY_BYTE_ARRAY);
    }
  }

  /**
   * Closes this connection. This cancels all open streams and unanswered pings. It closes the
   * underlying input and output streams and shuts down internal executor services.
   */
  @Override public void close() throws IOException {
    close(ErrorCode.NO_ERROR, ErrorCode.CANCEL);
  }

  private void close(ErrorCode connectionCode, ErrorCode streamCode) throws IOException {
    assert (!Thread.holdsLock(this));
    IOException thrown = null;
    try {
      shutdown(connectionCode);
    } catch (IOException e) {
      thrown = e;
    }

    Http2Stream[] streamsToClose = null;
    Ping[] pingsToCancel = null;
    synchronized (this) {
      if (!streams.isEmpty()) {
        streamsToClose = streams.values().toArray(new Http2Stream[streams.size()]);
        streams.clear();
      }
      if (pings != null) {
        pingsToCancel = pings.values().toArray(new Ping[pings.size()]);
        pings = null;
      }
    }

    if (streamsToClose != null) {
      for (Http2Stream stream : streamsToClose) {
        try {
          stream.close(streamCode);
        } catch (IOException e) {
          if (thrown != null) thrown = e;
        }
      }
    }

    if (pingsToCancel != null) {
      for (Ping ping : pingsToCancel) {
        ping.cancel();
      }
    }

    // Close the writer to release its resources (such as deflaters).
    try {
      writer.close();
    } catch (IOException e) {
      if (thrown == null) thrown = e;
    }

    // Close the socket to break out the reader thread, which will clean up after itself.
    try {
      socket.close();
    } catch (IOException e) {
      thrown = e;
    }

    if (thrown != null) throw thrown;
  }

  /**
   * Sends any initial frames and starts reading frames from the remote peer. This should be called
   * after {@link Builder#build} for all new connections.
   */
  public void start() throws IOException {
    start(true);
  }

  /**
   * @param sendConnectionPreface true to send connection preface frames. This should always be true
   *     except for in tests that don't check for a connection preface.
   */
  void start(boolean sendConnectionPreface) throws IOException {
    if (sendConnectionPreface) {
      writer.connectionPreface();
      writer.settings(okHttpSettings);
      int windowSize = okHttpSettings.getInitialWindowSize();
      if (windowSize != Settings.DEFAULT_INITIAL_WINDOW_SIZE) {
        writer.windowUpdate(0, windowSize - Settings.DEFAULT_INITIAL_WINDOW_SIZE);
      }
    }
    new Thread(readerRunnable).start(); // Not a daemon thread.
  }

  /** Merges {@code settings} into this peer's settings and sends them to the remote peer. */
  public void setSettings(Settings settings) throws IOException {
    synchronized (writer) {
      synchronized (this) {
        if (shutdown) {
          throw new IOException("shutdown");
        }
        okHttpSettings.merge(settings);
        writer.settings(settings);
      }
    }
  }

  public static class Builder {
    private Socket socket;
    private String hostname;
    private BufferedSource source;
    private BufferedSink sink;
    private Listener listener = Listener.REFUSE_INCOMING_STREAMS;
    private PushObserver pushObserver = PushObserver.CANCEL;
    private boolean client;

    /**
     * @param client true if this peer initiated the connection; false if this peer accepted the
     * connection.
     */
    public Builder(boolean client) {
      this.client = client;
    }

    public Builder socket(Socket socket) throws IOException {
      return socket(socket, ((InetSocketAddress) socket.getRemoteSocketAddress()).getHostName(),
          Okio.buffer(Okio.source(socket)), Okio.buffer(Okio.sink(socket)));
    }

    public Builder socket(
        Socket socket, String hostname, BufferedSource source, BufferedSink sink) {
      this.socket = socket;
      this.hostname = hostname;
      this.source = source;
      this.sink = sink;
      return this;
    }

    public Builder listener(Listener listener) {
      this.listener = listener;
      return this;
    }

    public Builder pushObserver(PushObserver pushObserver) {
      this.pushObserver = pushObserver;
      return this;
    }

    public Http2Connection build() throws IOException {
      return new Http2Connection(this);
    }
  }

  /**
   * Methods in this class must not lock FrameWriter.  If a method needs to write a frame, create an
   * async task to do so.
   */
  class ReaderRunnable extends NamedRunnable implements Http2Reader.Handler {
    final Http2Reader reader;

    private ReaderRunnable(Http2Reader reader) {
      super("OkHttp %s", hostname);
      this.reader = reader;
    }

    @Override protected void execute() {
      ErrorCode connectionErrorCode = ErrorCode.INTERNAL_ERROR;
      ErrorCode streamErrorCode = ErrorCode.INTERNAL_ERROR;
      try {
        if (!client) {
          reader.readConnectionPreface();
        }
        while (reader.nextFrame(this)) {
        }
        connectionErrorCode = ErrorCode.NO_ERROR;
        streamErrorCode = ErrorCode.CANCEL;
      } catch (IOException e) {
        connectionErrorCode = ErrorCode.PROTOCOL_ERROR;
        streamErrorCode = ErrorCode.PROTOCOL_ERROR;
      } finally {
        try {
          close(connectionErrorCode, streamErrorCode);
        } catch (IOException ignored) {
        }
        Util.closeQuietly(reader);
      }
    }

    @Override public void data(boolean inFinished, int streamId, BufferedSource source, int length)
        throws IOException {
      if (pushedStream(streamId)) {
        pushDataLater(streamId, source, length, inFinished);
        return;
      }
      Http2Stream dataStream = getStream(streamId);
      if (dataStream == null) {
        writeSynResetLater(streamId, ErrorCode.PROTOCOL_ERROR);
        source.skip(length);
        return;
      }
      dataStream.receiveData(source, length);
      if (inFinished) {
        dataStream.receiveFin();
      }
    }

    @Override public void headers(boolean inFinished, int streamId, int associatedStreamId,
        List<Header> headerBlock) {
      if (pushedStream(streamId)) {
        pushHeadersLater(streamId, headerBlock, inFinished);
        return;
      }
      Http2Stream stream;
      synchronized (Http2Connection.this) {
        // If we're shutdown, don't bother with this stream.
        if (shutdown) return;

        stream = getStream(streamId);

        if (stream == null) {
          // If the stream ID is less than the last created ID, assume it's already closed.
          if (streamId <= lastGoodStreamId) return;

          // If the stream ID is in the client's namespace, assume it's already closed.
          if (streamId % 2 == nextStreamId % 2) return;

          // Create a stream.
          final Http2Stream newStream = new Http2Stream(streamId, Http2Connection.this,
              false, inFinished, headerBlock);
          lastGoodStreamId = streamId;
          streams.put(streamId, newStream);
          executor.execute(new NamedRunnable("OkHttp %s stream %d", hostname, streamId) {
            @Override public void execute() {
              try {
                listener.onStream(newStream);
              } catch (IOException e) {
                Platform.get().log(INFO, "FramedConnection.Listener failure for " + hostname, e);
                try {
                  newStream.close(ErrorCode.PROTOCOL_ERROR);
                } catch (IOException ignored) {
                }
              }
            }
          });
          return;
        }
      }

      // Update an existing stream.
      stream.receiveHeaders(headerBlock);
      if (inFinished) stream.receiveFin();
    }

    @Override public void rstStream(int streamId, ErrorCode errorCode) {
      if (pushedStream(streamId)) {
        pushResetLater(streamId, errorCode);
        return;
      }
      Http2Stream rstStream = removeStream(streamId);
      if (rstStream != null) {
        rstStream.receiveRstStream(errorCode);
      }
    }

    @Override public void settings(boolean clearPrevious, Settings newSettings) {
      long delta = 0;
      Http2Stream[] streamsToNotify = null;
      synchronized (Http2Connection.this) {
        int priorWriteWindowSize = peerSettings.getInitialWindowSize();
        if (clearPrevious) peerSettings.clear();
        peerSettings.merge(newSettings);
        applyAndAckSettings(newSettings);
        int peerInitialWindowSize = peerSettings.getInitialWindowSize();
        if (peerInitialWindowSize != -1 && peerInitialWindowSize != priorWriteWindowSize) {
          delta = peerInitialWindowSize - priorWriteWindowSize;
          if (!receivedInitialPeerSettings) {
            addBytesToWriteWindow(delta);
            receivedInitialPeerSettings = true;
          }
          if (!streams.isEmpty()) {
            streamsToNotify = streams.values().toArray(new Http2Stream[streams.size()]);
          }
        }
        executor.execute(new NamedRunnable("OkHttp %s settings", hostname) {
          @Override public void execute() {
            listener.onSettings(Http2Connection.this);
          }
        });
      }
      if (streamsToNotify != null && delta != 0) {
        for (Http2Stream stream : streamsToNotify) {
          synchronized (stream) {
            stream.addBytesToWriteWindow(delta);
          }
        }
      }
    }

    private void applyAndAckSettings(final Settings peerSettings) {
      executor.execute(new NamedRunnable("OkHttp %s ACK Settings", hostname) {
        @Override public void execute() {
          try {
            writer.applyAndAckSettings(peerSettings);
          } catch (IOException ignored) {
          }
        }
      });
    }

    @Override public void ackSettings() {
      // TODO: If we don't get this callback after sending settings to the peer, SETTINGS_TIMEOUT.
    }

    @Override public void ping(boolean reply, int payload1, int payload2) {
      if (reply) {
        Ping ping = removePing(payload1);
        if (ping != null) {
          ping.receive();
        }
      } else {
        // Send a reply to a client ping if this is a server and vice versa.
        writePingLater(true, payload1, payload2, null);
      }
    }

    @Override public void goAway(int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
      if (debugData.size() > 0) { // TODO: log the debugData
      }

      // Copy the streams first. We don't want to hold a lock when we call receiveRstStream().
      Http2Stream[] streamsCopy;
      synchronized (Http2Connection.this) {
        streamsCopy = streams.values().toArray(new Http2Stream[streams.size()]);
        shutdown = true;
      }

      // Fail all streams created after the last good stream ID.
      for (Http2Stream http2Stream : streamsCopy) {
        if (http2Stream.getId() > lastGoodStreamId && http2Stream.isLocallyInitiated()) {
          http2Stream.receiveRstStream(ErrorCode.REFUSED_STREAM);
          removeStream(http2Stream.getId());
        }
      }
    }

    @Override public void windowUpdate(int streamId, long windowSizeIncrement) {
      if (streamId == 0) {
        synchronized (Http2Connection.this) {
          bytesLeftInWriteWindow += windowSizeIncrement;
          Http2Connection.this.notifyAll();
        }
      } else {
        Http2Stream stream = getStream(streamId);
        if (stream != null) {
          synchronized (stream) {
            stream.addBytesToWriteWindow(windowSizeIncrement);
          }
        }
      }
    }

    @Override public void priority(int streamId, int streamDependency, int weight,
        boolean exclusive) {
      // TODO: honor priority.
    }

    @Override
    public void pushPromise(int streamId, int promisedStreamId, List<Header> requestHeaders) {
      pushRequestLater(promisedStreamId, requestHeaders);
    }

    @Override public void alternateService(int streamId, String origin, ByteString protocol,
        String host, int port, long maxAge) {
      // TODO: register alternate service.
    }
  }

  /** Even, positive numbered streams are pushed streams in HTTP/2. */
  private boolean pushedStream(int streamId) {
    return streamId != 0 && (streamId & 1) == 0;
  }

  // Guarded by this.
  private final Set<Integer> currentPushRequests = new LinkedHashSet<>();

  private void pushRequestLater(final int streamId, final List<Header> requestHeaders) {
    synchronized (this) {
      if (currentPushRequests.contains(streamId)) {
        writeSynResetLater(streamId, ErrorCode.PROTOCOL_ERROR);
        return;
      }
      currentPushRequests.add(streamId);
    }
    pushExecutor.execute(new NamedRunnable("OkHttp %s Push Request[%s]", hostname, streamId) {
      @Override public void execute() {
        boolean cancel = pushObserver.onRequest(streamId, requestHeaders);
        try {
          if (cancel) {
            writer.rstStream(streamId, ErrorCode.CANCEL);
            synchronized (Http2Connection.this) {
              currentPushRequests.remove(streamId);
            }
          }
        } catch (IOException ignored) {
        }
      }
    });
  }

  private void pushHeadersLater(final int streamId, final List<Header> requestHeaders,
      final boolean inFinished) {
    pushExecutor.execute(new NamedRunnable("OkHttp %s Push Headers[%s]", hostname, streamId) {
      @Override public void execute() {
        boolean cancel = pushObserver.onHeaders(streamId, requestHeaders, inFinished);
        try {
          if (cancel) writer.rstStream(streamId, ErrorCode.CANCEL);
          if (cancel || inFinished) {
            synchronized (Http2Connection.this) {
              currentPushRequests.remove(streamId);
            }
          }
        } catch (IOException ignored) {
        }
      }
    });
  }

  /**
   * Eagerly reads {@code byteCount} bytes from the source before launching a background task to
   * process the data.  This avoids corrupting the stream.
   */
  private void pushDataLater(final int streamId, final BufferedSource source, final int byteCount,
      final boolean inFinished) throws IOException {
    final Buffer buffer = new Buffer();
    source.require(byteCount); // Eagerly read the frame before firing client thread.
    source.read(buffer, byteCount);
    if (buffer.size() != byteCount) throw new IOException(buffer.size() + " != " + byteCount);
    pushExecutor.execute(new NamedRunnable("OkHttp %s Push Data[%s]", hostname, streamId) {
      @Override public void execute() {
        try {
          boolean cancel = pushObserver.onData(streamId, buffer, byteCount, inFinished);
          if (cancel) writer.rstStream(streamId, ErrorCode.CANCEL);
          if (cancel || inFinished) {
            synchronized (Http2Connection.this) {
              currentPushRequests.remove(streamId);
            }
          }
        } catch (IOException ignored) {
        }
      }
    });
  }

  private void pushResetLater(final int streamId, final ErrorCode errorCode) {
    pushExecutor.execute(new NamedRunnable("OkHttp %s Push Reset[%s]", hostname, streamId) {
      @Override public void execute() {
        pushObserver.onReset(streamId, errorCode);
        synchronized (Http2Connection.this) {
          currentPushRequests.remove(streamId);
        }
      }
    });
  }

  /** Listener of streams and settings initiated by the peer. */
  public abstract static class Listener {
    public static final Listener REFUSE_INCOMING_STREAMS = new Listener() {
      @Override public void onStream(Http2Stream stream) throws IOException {
        stream.close(ErrorCode.REFUSED_STREAM);
      }
    };

    /**
     * Handle a new stream from this connection's peer. Implementations should respond by either
     * {@linkplain Http2Stream#reply replying to the stream} or {@linkplain Http2Stream#close
     * closing it}. This response does not need to be synchronous.
     */
    public abstract void onStream(Http2Stream stream) throws IOException;

    /**
     * Notification that the connection's peer's settings may have changed. Implementations should
     * take appropriate action to handle the updated settings.
     *
     * <p>It is the implementation's responsibility to handle concurrent calls to this method. A
     * remote peer that sends multiple settings frames will trigger multiple calls to this method,
     * and those calls are not necessarily serialized.
     */
    public void onSettings(Http2Connection connection) {
    }
  }
}
