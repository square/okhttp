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

package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.internal.NamedRunnable;
import com.squareup.okhttp.internal.Util;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A socket connection to a remote peer. A connection hosts streams which can
 * send and receive data.
 *
 * <p>Many methods in this API are <strong>synchronous:</strong> the call is
 * completed before the method returns. This is typical for Java but atypical
 * for SPDY. This is motivated by exception transparency: an IOException that
 * was triggered by a certain caller can be caught and handled by that caller.
 */
public final class SpdyConnection implements Closeable {

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
      Util.daemonThreadFactory("OkHttp SpdyConnection"));

  /** The protocol variant, like SPDY/3 or HTTP-draft-04/2.0. */
  final Variant variant;

  /** True if this peer initiated the connection. */
  final boolean client;

  /**
   * User code to run in response to an incoming stream. Callbacks must not be
   * run on the callback executor.
   */
  private final IncomingStreamHandler handler;
  private final FrameReader frameReader;
  private final FrameWriter frameWriter;

  private final Map<Integer, SpdyStream> streams = new HashMap<Integer, SpdyStream>();
  private final String hostName;
  private int lastGoodStreamId;
  private int nextStreamId;
  private boolean shutdown;
  private long idleStartTimeNs = System.nanoTime();

  /** Lazily-created map of in-flight pings awaiting a response. Guarded by this. */
  private Map<Integer, Ping> pings;
  private int nextPingId;

  /** Lazily-created settings for the peer. */
  Settings settings;

  private SpdyConnection(Builder builder) {
    variant = builder.variant;
    client = builder.client;
    handler = builder.handler;
    frameReader = variant.newReader(builder.in, client);
    frameWriter = variant.newWriter(builder.out, client);
    nextStreamId = builder.client ? 1 : 2;
    nextPingId = builder.client ? 1 : 2;

    hostName = builder.hostName;

    new Thread(new Reader(), "Spdy Reader " + hostName).start();
  }

  public String getVariant() {
    return variant.getName();
  }

  /**
   * Returns the number of {@link SpdyStream#isOpen() open streams} on this
   * connection.
   */
  public synchronized int openStreamCount() {
    return streams.size();
  }

  private synchronized SpdyStream getStream(int id) {
    return streams.get(id);
  }

  synchronized SpdyStream removeStream(int streamId) {
    SpdyStream stream = streams.remove(streamId);
    if (stream != null && streams.isEmpty()) {
      setIdle(true);
    }
    return stream;
  }

  private synchronized void setIdle(boolean value) {
    idleStartTimeNs = value ? System.nanoTime() : 0L;
  }

  /** Returns true if this connection is idle. */
  public synchronized boolean isIdle() {
    return idleStartTimeNs != 0L;
  }

  /** Returns the time in ns when this connection became idle or 0L if connection is not idle. */
  public synchronized long getIdleStartTimeNs() {
    return idleStartTimeNs;
  }

  /**
   * Returns a new locally-initiated stream.
   *
   * @param out true to create an output stream that we can use to send data
   *     to the remote peer. Corresponds to {@code FLAG_FIN}.
   * @param in true to create an input stream that the remote peer can use to
   *     send data to us. Corresponds to {@code FLAG_UNIDIRECTIONAL}.
   */
  public SpdyStream newStream(List<String> requestHeaders, boolean out, boolean in)
      throws IOException {
    boolean outFinished = !out;
    boolean inFinished = !in;
    int associatedStreamId = 0;  // TODO: permit the caller to specify an associated stream?
    int priority = 0; // TODO: permit the caller to specify a priority?
    int slot = 0; // TODO: permit the caller to specify a slot?
    SpdyStream stream;
    int streamId;

    synchronized (frameWriter) {
      synchronized (this) {
        if (shutdown) {
          throw new IOException("shutdown");
        }
        streamId = nextStreamId;
        nextStreamId += 2;
        stream = new SpdyStream(
            streamId, this, outFinished, inFinished, priority, requestHeaders, settings);
        if (stream.isOpen()) {
          streams.put(streamId, stream);
          setIdle(false);
        }
      }

      frameWriter.synStream(outFinished, inFinished, streamId, associatedStreamId, priority, slot,
          requestHeaders);
    }

    return stream;
  }

  void writeSynReply(int streamId, boolean outFinished, List<String> alternating)
      throws IOException {
    frameWriter.synReply(outFinished, streamId, alternating);
  }

  public void writeData(int streamId, boolean outFinished, byte[] buffer, int offset, int byteCount)
      throws IOException {
    frameWriter.data(outFinished, streamId, buffer, offset, byteCount);
  }

  void writeSynResetLater(final int streamId, final ErrorCode errorCode) {
    executor.submit(new NamedRunnable("OkHttp SPDY Writer %s stream %d", hostName, streamId) {
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

  void writeWindowUpdateLater(final int streamId, final int deltaWindowSize) {
    executor.submit(new NamedRunnable("OkHttp SPDY Writer %s stream %d", hostName, streamId) {
      @Override public void execute() {
        try {
          writeWindowUpdate(streamId, deltaWindowSize);
        } catch (IOException ignored) {
        }
      }
    });
  }

  void writeWindowUpdate(int streamId, int deltaWindowSize) throws IOException {
    frameWriter.windowUpdate(streamId, deltaWindowSize);
  }

  /**
   * Sends a ping frame to the peer. Use the returned object to await the
   * ping's response and observe its round trip time.
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
      if (pings == null) pings = new HashMap<Integer, Ping>();
      pings.put(pingId, ping);
    }
    writePing(false, pingId, 0x4f4b6f6b /* ASCII "OKok" */, ping);
    return ping;
  }

  private void writePingLater(
      final boolean reply, final int payload1, final int payload2, final Ping ping) {
    executor.submit(new NamedRunnable("OkHttp SPDY Writer %s ping %08x%08x",
        hostName, payload1, payload2) {
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

  /** Sends a noop frame to the peer. */
  public void noop() throws IOException {
    frameWriter.noop();
  }

  public void flush() throws IOException {
    frameWriter.flush();
  }

  /**
   * Degrades this connection such that new streams can neither be created
   * locally, nor accepted from the remote peer. Existing streams are not
   * impacted. This is intended to permit an endpoint to gracefully stop
   * accepting new requests without harming previously established streams.
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
      frameWriter.goAway(lastGoodStreamId, statusCode);
    }
  }

  /**
   * Closes this connection. This cancels all open streams and unanswered
   * pings. It closes the underlying input and output streams and shuts down
   * internal executor services.
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

    SpdyStream[] streamsToClose = null;
    Ping[] pingsToCancel = null;
    synchronized (this) {
      if (!streams.isEmpty()) {
        streamsToClose = streams.values().toArray(new SpdyStream[streams.size()]);
        streams.clear();
        setIdle(false);
      }
      if (pings != null) {
        pingsToCancel = pings.values().toArray(new Ping[pings.size()]);
        pings = null;
      }
    }

    if (streamsToClose != null) {
      for (SpdyStream stream : streamsToClose) {
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

    try {
      frameReader.close();
    } catch (IOException e) {
      thrown = e;
    }
    try {
      frameWriter.close();
    } catch (IOException e) {
      if (thrown == null) thrown = e;
    }

    if (thrown != null) throw thrown;
  }

  /**
   * Sends a connection header if the current variant requires it. This should
   * be called after {@link Builder#build} for all new connections.
   */
  public void sendConnectionHeader() throws IOException {
    frameWriter.connectionHeader();
    frameWriter.settings(new Settings());
  }

  public static class Builder {
    private String hostName;
    private InputStream in;
    private OutputStream out;
    private IncomingStreamHandler handler = IncomingStreamHandler.REFUSE_INCOMING_STREAMS;
    private Variant variant = Variant.SPDY3;
    private boolean client;

    public Builder(boolean client, Socket socket) throws IOException {
      this("", client, socket.getInputStream(), socket.getOutputStream());
    }

    public Builder(boolean client, InputStream in, OutputStream out) {
      this("", client, in, out);
    }

    /**
     * @param client true if this peer initiated the connection; false if
     * this peer accepted the connection.
     */
    public Builder(String hostName, boolean client, Socket socket) throws IOException {
      this(hostName, client, socket.getInputStream(), socket.getOutputStream());
    }

    /**
     * @param client true if this peer initiated the connection; false if this
     * peer accepted the connection.
     */
    public Builder(String hostName, boolean client, InputStream in, OutputStream out) {
      this.hostName = hostName;
      this.client = client;
      this.in = in;
      this.out = out;
    }

    public Builder handler(IncomingStreamHandler handler) {
      this.handler = handler;
      return this;
    }

    public Builder spdy3() {
      this.variant = Variant.SPDY3;
      return this;
    }

    public Builder http20Draft04() {
      this.variant = Variant.HTTP_20_DRAFT_04;
      return this;
    }

    public SpdyConnection build() {
      return new SpdyConnection(this);
    }
  }

  private class Reader implements Runnable, FrameReader.Handler {
    @Override public void run() {
      ErrorCode connectionErrorCode = ErrorCode.INTERNAL_ERROR;
      ErrorCode streamErrorCode = ErrorCode.INTERNAL_ERROR;
      try {
        frameReader.readConnectionHeader();
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
      }
    }

    @Override public void data(boolean inFinished, int streamId, InputStream in, int length)
        throws IOException {
      SpdyStream dataStream = getStream(streamId);
      if (dataStream == null) {
        writeSynResetLater(streamId, ErrorCode.INVALID_STREAM);
        Util.skipByReading(in, length);
        return;
      }
      dataStream.receiveData(in, length);
      if (inFinished) {
        dataStream.receiveFin();
      }
    }

    @Override public void headers(boolean outFinished, boolean inFinished, int streamId,
        int associatedStreamId, int priority, List<String> nameValueBlock,
        HeadersMode headersMode) {
      SpdyStream stream;
      synchronized (SpdyConnection.this) {
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
          final SpdyStream newStream = new SpdyStream(streamId, SpdyConnection.this, outFinished,
              inFinished, priority, nameValueBlock, settings);
          lastGoodStreamId = streamId;
          streams.put(streamId, newStream);
          executor.submit(new NamedRunnable("OkHttp Callback %s stream %d", hostName, streamId) {
            @Override public void execute() {
              try {
                handler.receive(newStream);
              } catch (IOException e) {
                throw new RuntimeException(e);
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
      stream.receiveHeaders(nameValueBlock, headersMode);
      if (inFinished) stream.receiveFin();
    }

    @Override public void rstStream(int streamId, ErrorCode errorCode) {
      SpdyStream rstStream = removeStream(streamId);
      if (rstStream != null) {
        rstStream.receiveRstStream(errorCode);
      }
    }

    @Override public void settings(boolean clearPrevious, Settings newSettings) {
      SpdyStream[] streamsToNotify = null;
      synchronized (SpdyConnection.this) {
        if (settings == null || clearPrevious) {
          settings = newSettings;
        } else {
          settings.merge(newSettings);
        }
        if (!streams.isEmpty()) {
          streamsToNotify = streams.values().toArray(new SpdyStream[streams.size()]);
        }
      }
      if (streamsToNotify != null) {
        for (SpdyStream stream : streamsToNotify) {
          // The synchronization here is ugly. We need to synchronize on 'this' to guard
          // reads to 'settings'. We synchronize on 'stream' to guard the state change.
          // And we need to acquire the 'stream' lock first, since that may block.
          // TODO: this can block the reader thread until a write completes. That's bad!
          synchronized (stream) {
            synchronized (SpdyConnection.this) {
              stream.receiveSettings(settings);
            }
          }
        }
      }
    }

    @Override public void noop() {
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

    @Override public void goAway(int lastGoodStreamId, ErrorCode errorCode) {
      synchronized (SpdyConnection.this) {
        shutdown = true;

        // Fail all streams created after the last good stream ID.
        for (Iterator<Map.Entry<Integer, SpdyStream>> i = streams.entrySet().iterator();
            i.hasNext(); ) {
          Map.Entry<Integer, SpdyStream> entry = i.next();
          int streamId = entry.getKey();
          if (streamId > lastGoodStreamId && entry.getValue().isLocallyInitiated()) {
            entry.getValue().receiveRstStream(ErrorCode.REFUSED_STREAM);
            i.remove();
          }
        }
      }
    }

    @Override public void windowUpdate(int streamId, int deltaWindowSize, boolean endFlowControl) {
      if (streamId == 0) {
        // TODO: honor whole-stream flow control
        return;
      }

      // TODO: honor endFlowControl
      SpdyStream stream = getStream(streamId);
      if (stream != null) {
        stream.receiveWindowUpdate(deltaWindowSize);
      }
    }

    @Override public void priority(int streamId, int priority) {
      // TODO: honor priority.
    }
  }
}
