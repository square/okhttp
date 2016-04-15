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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

/** Replays prerecorded outgoing frames and records incoming frames. */
public final class MockSpdyPeer implements Closeable {
  private static final Logger logger = Logger.getLogger(MockSpdyPeer.class.getName());

  private int frameCount = 0;
  private boolean client = false;
  private Variant variant = new Spdy3();
  private final Buffer bytesOut = new Buffer();
  private FrameWriter frameWriter = variant.newWriter(bytesOut, client);
  private final List<OutFrame> outFrames = new ArrayList<>();
  private final BlockingQueue<InFrame> inFrames = new LinkedBlockingQueue<>();
  private int port;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(
      Util.threadFactory("MockSpdyPeer", false));
  private ServerSocket serverSocket;
  private Socket socket;

  public void setVariantAndClient(Variant variant, boolean client) {
    if (this.variant.getProtocol() == variant.getProtocol() && this.client == client) {
      return;
    }
    this.client = client;
    this.variant = variant;
    this.frameWriter = variant.newWriter(bytesOut, client);
  }

  public void acceptFrame() {
    frameCount++;
  }

  /** Maximum length of an outbound data frame. */
  public int maxOutboundDataLength() {
    return frameWriter.maxDataLength();
  }

  /** Count of frames sent or received. */
  public int frameCount() {
    return frameCount;
  }

  public FrameWriter sendFrame() {
    outFrames.add(new OutFrame(frameCount++, bytesOut.size(), false));
    return frameWriter;
  }

  /**
   * Sends a manually-constructed frame. This is useful to test frames that won't be generated
   * naturally.
   */
  public void sendFrame(byte[] frame) throws IOException {
    outFrames.add(new OutFrame(frameCount++, bytesOut.size(), false));
    bytesOut.write(frame);
  }

  /**
   * Shortens the last frame from its original length to {@code length}. This will cause the peer to
   * close the socket as soon as this frame has been written; otherwise the peer stays open until
   * explicitly closed.
   */
  public FrameWriter truncateLastFrame(int length) {
    OutFrame lastFrame = outFrames.remove(outFrames.size() - 1);
    if (length >= bytesOut.size() - lastFrame.start) throw new IllegalArgumentException();

    // Move everything from bytesOut into a new buffer.
    Buffer fullBuffer = new Buffer();
    bytesOut.read(fullBuffer, bytesOut.size());

    // Copy back all but what we're truncating.
    fullBuffer.read(bytesOut, lastFrame.start + length);

    outFrames.add(new OutFrame(lastFrame.sequence, lastFrame.start, true));
    return frameWriter;
  }

  public InFrame takeFrame() throws InterruptedException {
    return inFrames.take();
  }

  public void play() throws IOException {
    if (serverSocket != null) throw new IllegalStateException();
    serverSocket = new ServerSocket();
    serverSocket.setReuseAddress(false);
    serverSocket.bind(new InetSocketAddress("localhost", 0), 1);
    port = serverSocket.getLocalPort();
    executor.execute(new Runnable() {
      @Override public void run() {
        try {
          readAndWriteFrames();
        } catch (IOException e) {
          Util.closeQuietly(MockSpdyPeer.this);
          logger.info(MockSpdyPeer.this + " done: " + e.getMessage());
        }
      }
    });
  }

  private void readAndWriteFrames() throws IOException {
    if (socket != null) throw new IllegalStateException();
    socket = serverSocket.accept();

    // Bail out now if this instance was closed while waiting for the socket to accept.
    synchronized (this) {
      if (executor.isShutdown()) {
        socket.close();
        return;
      }
    }

    OutputStream out = socket.getOutputStream();
    InputStream in = socket.getInputStream();
    FrameReader reader = variant.newReader(Okio.buffer(Okio.source(in)), client);

    Iterator<OutFrame> outFramesIterator = outFrames.iterator();
    byte[] outBytes = bytesOut.readByteArray();
    OutFrame nextOutFrame = null;

    for (int i = 0; i < frameCount; i++) {
      if (nextOutFrame == null && outFramesIterator.hasNext()) {
        nextOutFrame = outFramesIterator.next();
      }

      if (nextOutFrame != null && nextOutFrame.sequence == i) {
        long start = nextOutFrame.start;
        boolean truncated;
        long end;
        if (outFramesIterator.hasNext()) {
          nextOutFrame = outFramesIterator.next();
          end = nextOutFrame.start;
          truncated = false;
        } else {
          end = outBytes.length;
          truncated = nextOutFrame.truncated;
        }

        // Write a frame.
        int length = (int) (end - start);
        out.write(outBytes, (int) start, length);

        // If the last frame was truncated, immediately close the connection.
        if (truncated) {
          socket.close();
        }
      } else {
        // read a frame
        InFrame inFrame = new InFrame(i, reader);
        reader.nextFrame(inFrame);
        inFrames.add(inFrame);
      }
    }
  }

  public Socket openSocket() throws IOException {
    return new Socket("localhost", port);
  }

  @Override public synchronized void close() throws IOException {
    executor.shutdown();
    Util.closeQuietly(socket);
    Util.closeQuietly(serverSocket);
  }

  @Override public String toString() {
    return "MockSpdyPeer[" + port + "]";
  }

  private static class OutFrame {
    private final int sequence;
    private final long start;
    private final boolean truncated;

    private OutFrame(int sequence, long start, boolean truncated) {
      this.sequence = sequence;
      this.start = start;
      this.truncated = truncated;
    }
  }

  public static class InFrame implements FrameReader.Handler {
    public final int sequence;
    public final FrameReader reader;
    public int type = -1;
    public boolean clearPrevious;
    public boolean outFinished;
    public boolean inFinished;
    public int streamId;
    public int associatedStreamId;
    public ErrorCode errorCode;
    public long windowSizeIncrement;
    public List<Header> headerBlock;
    public byte[] data;
    public Settings settings;
    public HeadersMode headersMode;
    public boolean ack;
    public int payload1;
    public int payload2;

    public InFrame(int sequence, FrameReader reader) {
      this.sequence = sequence;
      this.reader = reader;
    }

    @Override public void settings(boolean clearPrevious, Settings settings) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = Spdy3.TYPE_SETTINGS;
      this.clearPrevious = clearPrevious;
      this.settings = settings;
    }

    @Override public void ackSettings() {
      if (this.type != -1) throw new IllegalStateException();
      this.type = Spdy3.TYPE_SETTINGS;
      this.ack = true;
    }

    @Override public void headers(boolean outFinished, boolean inFinished, int streamId,
        int associatedStreamId, List<Header> headerBlock, HeadersMode headersMode) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = Spdy3.TYPE_HEADERS;
      this.outFinished = outFinished;
      this.inFinished = inFinished;
      this.streamId = streamId;
      this.associatedStreamId = associatedStreamId;
      this.headerBlock = headerBlock;
      this.headersMode = headersMode;
    }

    @Override public void data(boolean inFinished, int streamId, BufferedSource source, int length)
        throws IOException {
      if (this.type != -1) throw new IllegalStateException();
      this.type = Spdy3.TYPE_DATA;
      this.inFinished = inFinished;
      this.streamId = streamId;
      this.data = source.readByteString(length).toByteArray();
    }

    @Override public void rstStream(int streamId, ErrorCode errorCode) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = Spdy3.TYPE_RST_STREAM;
      this.streamId = streamId;
      this.errorCode = errorCode;
    }

    @Override public void ping(boolean ack, int payload1, int payload2) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = Spdy3.TYPE_PING;
      this.ack = ack;
      this.payload1 = payload1;
      this.payload2 = payload2;
    }

    @Override public void goAway(int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = Spdy3.TYPE_GOAWAY;
      this.streamId = lastGoodStreamId;
      this.errorCode = errorCode;
      this.data = debugData.toByteArray();
    }

    @Override public void windowUpdate(int streamId, long windowSizeIncrement) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = Spdy3.TYPE_WINDOW_UPDATE;
      this.streamId = streamId;
      this.windowSizeIncrement = windowSizeIncrement;
    }

    @Override public void priority(int streamId, int streamDependency, int weight,
        boolean exclusive) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void pushPromise(int streamId, int associatedStreamId, List<Header> headerBlock) {
      this.type = Http2.TYPE_PUSH_PROMISE;
      this.streamId = streamId;
      this.associatedStreamId = associatedStreamId;
      this.headerBlock = headerBlock;
    }

    @Override public void alternateService(int streamId, String origin, ByteString protocol,
        String host, int port, long maxAge) {
      throw new UnsupportedOperationException();
    }
  }
}
