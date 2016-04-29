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
package okhttp3.internal.framed;

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
import java.util.logging.Level;
import okhttp3.Protocol;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

import static okhttp3.internal.Internal.logger;
import static okhttp3.internal.framed.Settings.DEFAULT_INITIAL_WINDOW_SIZE;

/**
 * A socket connection to a remote peer. A connection hosts streams which can send and receive
 * data.
 *
 * <p>Many methods in this API are <strong>synchronous:</strong> the call is completed before the
 * method returns. This is typical for Java but atypical for SPDY. This is motivated by exception
 * transparency: an IOException that was triggered by a certain caller can be caught and handled by
 * that caller.
 */
public final class FramedConnection implements Closeable {

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

  /** The protocol variant, like {@link Spdy3}. */
  final Protocol protocol;

  /** True if this peer initiated the connection. */
  final boolean client;

  /**
   * User code to run in response to incoming streams or settings. Calls to this are always invoked
   * on {@link #executor}.
   */
  private final Listener listener;
  private final Map<Integer, FramedStream> streams = new HashMap<>();
  private final String hostname;
  private int lastGoodStreamId;
  private int nextStreamId;
  private boolean shutdown;
  private long idleStartTimeNs = System.nanoTime();

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
  final Variant variant;
  final Socket socket;
  final FrameWriter frameWriter;

  // Visible for testing
  final Reader readerRunnable;

  private FramedConnection(Builder builder) throws IOException {
    protocol = builder.protocol;
    pushObserver = builder.pushObserver;
    client = builder.client;
    listener = builder.listener;
    // http://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-5.1.1
    nextStreamId = builder.client ? 1 : 2;
    if (builder.client && protocol == Protocol.HTTP_2) {
      nextStreamId += 2; // In HTTP/2, 1 on client is reserved for Upgrade.
    }

    nextPingId = builder.client ? 1 : 2;

    // Flow control was designed more for servers, or proxies than edge clients.
    // If we are a client, set the flow control window to 16MiB.  This avoids
    // thrashing window updates every 64KiB, yet small enough to avoid blowing
    // up the heap.
    if (builder.client) {
      okHttpSettings.set(Settings.INITIAL_WINDOW_SIZE, 0, OKHTTP_CLIENT_WINDOW_SIZE);
    }

    hostname = builder.hostname;

    if (protocol == Protocol.HTTP_2) {
      variant = new Http2();
      // Like newSingleThreadExecutor, except lazy creates the thread.
      pushExecutor = new ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS,
          new LinkedBlockingQueue<Runnable>(),
          Util.threadFactory(Util.format("OkHttp %s Push Observer", hostname), true));
      // 1 less than SPDY http://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-6.9.2
      peerSettings.set(Settings.INITIAL_WINDOW_SIZE, 0, 65535);
      peerSettings.set(Settings.MAX_FRAME_SIZE, 0, Http2.INITIAL_MAX_FRAME_SIZE);
    } else if (protocol == Protocol.SPDY_3) {
      variant = new Spdy3();
      pushExecutor = null;
    } else {
      throw new AssertionError(protocol);
    }
    bytesLeftInWriteWindow = peerSettings.getInitialWindowSize(DEFAULT_INITIAL_WINDOW_SIZE);
    socket = builder.socket;
    frameWriter = variant.newWriter(builder.sink, client);

    readerRunnable = new Reader(variant.newReader(builder.source, client));
  }

  /** The protocol as selected using ALPN. */
  public Protocol getProtocol() {
    return protocol;
  }

  /**
   * Returns the number of {@link FramedStream#isOpen() open streams} on this connection.
   */
  public synchronized int openStreamCount() {
    return streams.size();
  }

  synchronized FramedStream getStream(int id) {
    return streams.get(id);
  }

  synchronized FramedStream removeStream(int streamId) {
    FramedStream stream = streams.remove(streamId);
    if (stream != null && streams.isEmpty()) {
      setIdle(true);
    }
    notifyAll(); // The removed stream may be blocked on a connection-wide window update.
    return stream;
  }

  private synchronized void setIdle(boolean value) {
    idleStartTimeNs = value ? System.nanoTime() : Long.MAX_VALUE;
  }

  /** Returns true if this connection is idle. */
  public synchronized boolean isIdle() {
    return idleStartTimeNs != Long.MAX_VALUE;
  }

  public synchronized int maxConcurrentStreams() {
    return peerSettings.getMaxConcurrentStreams(Integer.MAX_VALUE);
  }

  /**
   * Returns the time in ns when this connection became idle or Long.MAX_VALUE if connection is not
   * idle.
   */
  public synchronized long getIdleStartTimeNs() {
    return idleStartTimeNs;
  }

  /**
   * Returns a new server-initiated stream.
   *
   * @param associatedStreamId the stream that triggered the sender to create this stream.
   * @param out true to create an output stream that we can use to send data to the remote peer.
   * Corresponds to {@code FLAG_FIN}.
   */
  public FramedStream pushStream(int associatedStreamId, List<Header> requestHeaders, boolean out)
      throws IOException {
    if (client) throw new IllegalStateException("Client cannot push requests.");
    if (protocol != Protocol.HTTP_2) throw new IllegalStateException("protocol != HTTP_2");
    return newStream(associatedStreamId, requestHeaders, out, false);
  }

  /**
   * Returns a new locally-initiated stream.
   *
   * @param out true to create an output stream that we can use to send data to the remote peer.
   * Corresponds to {@code FLAG_FIN}.
   * @param in true to create an input stream that the remote peer can use to send data to us.
   * Corresponds to {@code FLAG_UNIDIRECTIONAL}.
   */
  public FramedStream newStream(List<Header> requestHeaders, boolean out, boolean in)
      throws IOException {
    return newStream(0, requestHeaders, out, in);
  }

  private FramedStream newStream(int associatedStreamId, List<Header> requestHeaders, boolean out,
      boolean in) throws IOException {
    boolean outFinished = !out;
    boolean inFinished = !in;
    FramedStream stream;
    int streamId;

    synchronized (frameWriter) {
      synchronized (this) {
        if (shutdown) {
          throw new IOException("shutdown");
        }
        streamId = nextStreamId;
        nextStreamId += 2;
        stream = new FramedStream(streamId, this, outFinished, inFinished, requestHeaders);
        if (stream.isOpen()) {
          streams.put(streamId, stream);
          setIdle(false);
        }
      }
      if (associatedStreamId == 0) {
        frameWriter.synStream(outFinished, inFinished, streamId, associatedStreamId,
            requestHeaders);
      } else if (client) {
        throw new IllegalArgumentException("client streams shouldn't have associated stream IDs");
      } else { // HTTP/2 has a PUSH_PROMISE frame.
        frameWriter.pushPromise(associatedStreamId, streamId, requestHeaders);
      }
    }

    if (!out) {
      frameWriter.flush();
    }

    return stream;
  }

  void writeSynReply(int streamId, boolean outFinished, List<Header> alternating)
      throws IOException {
    frameWriter.synReply(outFinished, streamId, alternating);
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
      frameWriter.data(outFinished, streamId, buffer, 0);
      return;
    }

    while (byteCount > 0) {
      int toWrite;
      synchronized (FramedConnection.this) {
        try {
          while (bytesLeftInWriteWindow <= 0) {
            // Before blocking, confirm that the stream we're writing is still open. It's possible
            // that the stream has since been closed (such as if this write timed out.)
            if (!streams.containsKey(streamId)) {
              throw new IOException("stream closed");
            }
            FramedConnection.this.wait(); // Wait until we receive a WINDOW_UPDATE.
          }
        } catch (InterruptedException e) {
          throw new InterruptedIOException();
        }

        toWrite = (int) Math.min(byteCount, bytesLeftInWriteWindow);
        toWrite = Math.min(toWrite, frameWriter.maxDataLength());
        bytesLeftInWriteWindow -= toWrite;
      }

      byteCount -= toWrite;
      frameWriter.data(outFinished && byteCount == 0, streamId, buffer, toWrite);
    }
  }

  /**
   * {@code delta} will be negative if a settings frame initial window is smaller than the last.
   */
  void addBytesToWriteWindow(long delta) {
    bytesLeftInWriteWindow += delta;
    if (delta > 0) FramedConnection.this.notifyAll();
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
    frameWriter.rstStream(streamId, statusCode);
  }

  void writeWindowUpdateLater(final int streamId, final long unacknowledgedBytesRead) {
    executor.execute(new NamedRunnable("OkHttp Window Update %s stream %d", hostname, streamId) {
      @Override public void execute() {
        try {
          frameWriter.windowUpdate(streamId, unacknowledgedBytesRead);
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
    synchronized (frameWriter) {
      // Observe the sent time immediately before performing I/O.
      if (ping != null) ping.send();
      frameWriter.ping(reply, payload1, payload2);
    }
  }

  private synchronized Ping removePing(int id) {
    return pings != null ? pings.remove(id) : null;
  }

  public void flush() throws IOException {
    frameWriter.flush();
  }

  /**
   * Degrades this connection such that new streams can neither be created locally, nor accepted
   * from the remote peer. Existing streams are not impacted. This is intended to permit an endpoint
   * to gracefully stop accepting new requests without harming previously established streams.
   */
  public void shutdown(ErrorCode statusCode) throws IOException {
    synchronized (frameWriter) {
      int lastGoodStreamId;
      synchronized (this) {
        if (shutdown) {
          return;
        }
        shutdown = true;
        lastGoodStreamId = this.lastGoodStreamId;
      }
      // TODO: propagate exception message into debugData
      frameWriter.goAway(lastGoodStreamId, statusCode, Util.EMPTY_BYTE_ARRAY);
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

    FramedStream[] streamsToClose = null;
    Ping[] pingsToCancel = null;
    synchronized (this) {
      if (!streams.isEmpty()) {
        streamsToClose = streams.values().toArray(new FramedStream[streams.size()]);
        streams.clear();
        setIdle(false);
      }
      if (pings != null) {
        pingsToCancel = pings.values().toArray(new Ping[pings.size()]);
        pings = null;
      }
    }

    if (streamsToClose != null) {
      for (FramedStream stream : streamsToClose) {
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
      frameWriter.close();
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
      frameWriter.connectionPreface();
      frameWriter.settings(okHttpSettings);
      int windowSize = okHttpSettings.getInitialWindowSize(Settings.DEFAULT_INITIAL_WINDOW_SIZE);
      if (windowSize != Settings.DEFAULT_INITIAL_WINDOW_SIZE) {
        frameWriter.windowUpdate(0, windowSize - Settings.DEFAULT_INITIAL_WINDOW_SIZE);
      }
    }
    new Thread(readerRunnable).start(); // Not a daemon thread.
  }

  /** Merges {@code settings} into this peer's settings and sends them to the remote peer. */
  public void setSettings(Settings settings) throws IOException {
    synchronized (frameWriter) {
      synchronized (this) {
        if (shutdown) {
          throw new IOException("shutdown");
        }
        okHttpSettings.merge(settings);
        frameWriter.settings(settings);
      }
    }
  }

  public static class Builder {
    private Socket socket;
    private String hostname;
    private BufferedSource source;
    private BufferedSink sink;
    private Listener listener = Listener.REFUSE_INCOMING_STREAMS;
    private Protocol protocol = Protocol.SPDY_3;
    private PushObserver pushObserver = PushObserver.CANCEL;
    private boolean client;

    /**
     * @param client true if this peer initiated the connection; false if this peer accepted the
     * connection.
     */
    public Builder(boolean client) throws IOException {
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

    public Builder protocol(Protocol protocol) {
      this.protocol = protocol;
      return this;
    }

    public Builder pushObserver(PushObserver pushObserver) {
      this.pushObserver = pushObserver;
      return this;
    }

    public FramedConnection build() throws IOException {
      return new FramedConnection(this);
    }
  }

  /**
   * Methods in this class must not lock FrameWriter.  If a method needs to write a frame, create an
   * async task to do so.
   */
  class Reader extends NamedRunnable implements FrameReader.Handler {
    final FrameReader frameReader;

    private Reader(FrameReader frameReader) {
      super("OkHttp %s", hostname);
      this.frameReader = frameReader;
    }

    @Override protected void execute() {
      ErrorCode connectionErrorCode = ErrorCode.INTERNAL_ERROR;
      ErrorCode streamErrorCode = ErrorCode.INTERNAL_ERROR;
      try {
        if (!client) {
          frameReader.readConnectionPreface();
        }
        while (frameReader.nextFrame(this)) {
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
        Util.closeQuietly(frameReader);
      }
    }

    @Override public void data(boolean inFinished, int streamId, BufferedSource source, int length)
        throws IOException {
      if (pushedStream(streamId)) {
        pushDataLater(streamId, source, length, inFinished);
        return;
      }
      FramedStream dataStream = getStream(streamId);
      if (dataStream == null) {
        writeSynResetLater(streamId, ErrorCode.INVALID_STREAM);
        source.skip(length);
        return;
      }
      dataStream.receiveData(source, length);
      if (inFinished) {
        dataStream.receiveFin();
      }
    }

    @Override public void headers(boolean outFinished, boolean inFinished, int streamId,
        int associatedStreamId, List<Header> headerBlock, HeadersMode headersMode) {
      if (pushedStream(streamId)) {
        pushHeadersLater(streamId, headerBlock, inFinished);
        return;
      }
      FramedStream stream;
      synchronized (FramedConnection.this) {
        // If we're shutdown, don't bother with this stream.
        if (shutdown) return;

        stream = getStream(streamId);

        if (stream == null) {
          // The headers claim to be for an existing stream, but we don't have one.
          if (headersMode.failIfStreamAbsent()) {
            writeSynResetLater(streamId, ErrorCode.INVALID_STREAM);
            return;
          }

          // If the stream ID is less than the last created ID, assume it's already closed.
          if (streamId <= lastGoodStreamId) return;

          // If the stream ID is in the client's namespace, assume it's already closed.
          if (streamId % 2 == nextStreamId % 2) return;

          // Create a stream.
          final FramedStream
              newStream = new FramedStream(streamId, FramedConnection.this, outFinished,
              inFinished, headerBlock);
          lastGoodStreamId = streamId;
          streams.put(streamId, newStream);
          executor.execute(new NamedRunnable("OkHttp %s stream %d", hostname, streamId) {
            @Override public void execute() {
              try {
                listener.onStream(newStream);
              } catch (IOException e) {
                logger.log(Level.INFO, "FramedConnection.Listener failure for " + hostname, e);
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

      // The headers claim to be for a new stream, but we already have one.
      if (headersMode.failIfStreamPresent()) {
        stream.closeLater(ErrorCode.PROTOCOL_ERROR);
        removeStream(streamId);
        return;
      }

      // Update an existing stream.
      stream.receiveHeaders(headerBlock, headersMode);
      if (inFinished) stream.receiveFin();
    }

    @Override public void rstStream(int streamId, ErrorCode errorCode) {
      if (pushedStream(streamId)) {
        pushResetLater(streamId, errorCode);
        return;
      }
      FramedStream rstStream = removeStream(streamId);
      if (rstStream != null) {
        rstStream.receiveRstStream(errorCode);
      }
    }

    @Override public void settings(boolean clearPrevious, Settings newSettings) {
      long delta = 0;
      FramedStream[] streamsToNotify = null;
      synchronized (FramedConnection.this) {
        int priorWriteWindowSize = peerSettings.getInitialWindowSize(DEFAULT_INITIAL_WINDOW_SIZE);
        if (clearPrevious) peerSettings.clear();
        peerSettings.merge(newSettings);
        if (getProtocol() == Protocol.HTTP_2) {
          ackSettingsLater(newSettings);
        }
        int peerInitialWindowSize = peerSettings.getInitialWindowSize(DEFAULT_INITIAL_WINDOW_SIZE);
        if (peerInitialWindowSize != -1 && peerInitialWindowSize != priorWriteWindowSize) {
          delta = peerInitialWindowSize - priorWriteWindowSize;
          if (!receivedInitialPeerSettings) {
            addBytesToWriteWindow(delta);
            receivedInitialPeerSettings = true;
          }
          if (!streams.isEmpty()) {
            streamsToNotify = streams.values().toArray(new FramedStream[streams.size()]);
          }
        }
        executor.execute(new NamedRunnable("OkHttp %s settings", hostname) {
          @Override public void execute() {
            listener.onSettings(FramedConnection.this);
          }
        });
      }
      if (streamsToNotify != null && delta != 0) {
        for (FramedStream stream : streamsToNotify) {
          synchronized (stream) {
            stream.addBytesToWriteWindow(delta);
          }
        }
      }
    }

    private void ackSettingsLater(final Settings peerSettings) {
      executor.execute(new NamedRunnable("OkHttp %s ACK Settings", hostname) {
        @Override public void execute() {
          try {
            frameWriter.ackSettings(peerSettings);
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
      FramedStream[] streamsCopy;
      synchronized (FramedConnection.this) {
        streamsCopy = streams.values().toArray(new FramedStream[streams.size()]);
        shutdown = true;
      }

      // Fail all streams created after the last good stream ID.
      for (FramedStream framedStream : streamsCopy) {
        if (framedStream.getId() > lastGoodStreamId && framedStream.isLocallyInitiated()) {
          framedStream.receiveRstStream(ErrorCode.REFUSED_STREAM);
          removeStream(framedStream.getId());
        }
      }
    }

    @Override public void windowUpdate(int streamId, long windowSizeIncrement) {
      if (streamId == 0) {
        synchronized (FramedConnection.this) {
          bytesLeftInWriteWindow += windowSizeIncrement;
          FramedConnection.this.notifyAll();
        }
      } else {
        FramedStream stream = getStream(streamId);
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
    return protocol == Protocol.HTTP_2 && streamId != 0 && (streamId & 1) == 0;
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
            frameWriter.rstStream(streamId, ErrorCode.CANCEL);
            synchronized (FramedConnection.this) {
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
          if (cancel) frameWriter.rstStream(streamId, ErrorCode.CANCEL);
          if (cancel || inFinished) {
            synchronized (FramedConnection.this) {
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
          if (cancel) frameWriter.rstStream(streamId, ErrorCode.CANCEL);
          if (cancel || inFinished) {
            synchronized (FramedConnection.this) {
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
        synchronized (FramedConnection.this) {
          currentPushRequests.remove(streamId);
        }
      }
    });
  }

  /** Listener of streams and settings initiated by the peer. */
  public abstract static class Listener {
    public static final Listener REFUSE_INCOMING_STREAMS = new Listener() {
      @Override public void onStream(FramedStream stream) throws IOException {
        stream.close(ErrorCode.REFUSED_STREAM);
      }
    };

    /**
     * Handle a new stream from this connection's peer. Implementations should respond by either
     * {@linkplain FramedStream#reply replying to the stream} or {@linkplain FramedStream#close
     * closing it}. This response does not need to be synchronous.
     */
    public abstract void onStream(FramedStream stream) throws IOException;

    /**
     * Notification that the connection's peer's settings may have changed. Implementations should
     * take appropriate action to handle the updated settings.
     *
     * <p>It is the implementation's responsibility to handle concurrent calls to this method. A
     * remote peer that sends multiple settings frames will trigger multiple calls to this method,
     * and those calls are not necessarily serialized.
     */
    public void onSettings(FramedConnection connection) {
    }
  }
}
