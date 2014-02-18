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

import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.bytes.BufferedSource;
import com.squareup.okhttp.internal.bytes.ByteString;
import com.squareup.okhttp.internal.bytes.OkBuffers;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/** Replays prerecorded outgoing frames and records incoming frames. */
public final class MockSpdyPeer implements Closeable {
  private int frameCount = 0;
  private boolean client = false;
  private Variant variant = new Spdy3();
  private final ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
  private FrameWriter frameWriter = variant.newWriter(bytesOut, client);
  private final List<OutFrame> outFrames = new ArrayList<OutFrame>();
  private final BlockingQueue<InFrame> inFrames = new LinkedBlockingQueue<InFrame>();
  private int port;
  private final ExecutorService executor = Executors.newCachedThreadPool(
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

  public int frameCount() {
    return frameCount;
  }

  public FrameWriter sendFrame() {
    outFrames.add(new OutFrame(frameCount++, bytesOut.size(), Integer.MAX_VALUE));
    return frameWriter;
  }

  /**
   * Sends a manually-constructed frame. This is useful to test frames that
   * won't be generated naturally.
   */
  public void sendFrame(byte[] frame) throws IOException {
    outFrames.add(new OutFrame(frameCount++, bytesOut.size(), Integer.MAX_VALUE));
    bytesOut.write(frame);
  }

  /**
   * Sends a frame, truncated to {@code truncateToLength} bytes. This is only
   * useful for testing error handling as the truncated frame will be
   * malformed.
   */
  public FrameWriter sendTruncatedFrame(int truncateToLength) {
    outFrames.add(new OutFrame(frameCount++, bytesOut.size(), truncateToLength));
    return frameWriter;
  }

  public InFrame takeFrame() throws InterruptedException {
    return inFrames.take();
  }

  public void play() throws IOException {
    if (serverSocket != null) throw new IllegalStateException();
    serverSocket = new ServerSocket(0);
    serverSocket.setReuseAddress(true);
    port = serverSocket.getLocalPort();
    executor.execute(new Runnable() {
      @Override public void run() {
        try {
          readAndWriteFrames();
        } catch (IOException e) {
          Util.closeQuietly(MockSpdyPeer.this);
          throw new RuntimeException(e);
        }
      }
    });
  }

  private void readAndWriteFrames() throws IOException {
    if (socket != null) throw new IllegalStateException();
    socket = serverSocket.accept();
    OutputStream out = socket.getOutputStream();
    InputStream in = socket.getInputStream();
    FrameReader reader = variant.newReader(new BufferedSource(OkBuffers.source(in)), client);

    Iterator<OutFrame> outFramesIterator = outFrames.iterator();
    byte[] outBytes = bytesOut.toByteArray();
    OutFrame nextOutFrame = null;

    for (int i = 0; i < frameCount; i++) {
      if (nextOutFrame == null && outFramesIterator.hasNext()) {
        nextOutFrame = outFramesIterator.next();
      }

      if (nextOutFrame != null && nextOutFrame.sequence == i) {
        int start = nextOutFrame.start;
        int truncateToLength = nextOutFrame.truncateToLength;
        int end;
        if (outFramesIterator.hasNext()) {
          nextOutFrame = outFramesIterator.next();
          end = nextOutFrame.start;
        } else {
          end = outBytes.length;
        }

        // write a frame
        int length = Math.min(end - start, truncateToLength);
        out.write(outBytes, start, length);
      } else {
        // read a frame
        InFrame inFrame = new InFrame(i, reader);
        reader.nextFrame(inFrame);
        inFrames.add(inFrame);
      }
    }
    Util.closeQuietly(socket);
  }

  public Socket openSocket() throws IOException {
    return new Socket("localhost", port);
  }

  @Override public synchronized void close() throws IOException {
    executor.shutdown();
    Socket socket = this.socket;
    if (socket != null) {
      Util.closeQuietly(socket);
      this.socket = null;
    }
    ServerSocket serverSocket = this.serverSocket;
    if (serverSocket != null) {
      Util.closeQuietly(serverSocket);
      this.serverSocket = null;
    }
  }

  private static class OutFrame {
    private final int sequence;
    private final int start;
    private final int truncateToLength;

    private OutFrame(int sequence, int start, int truncateToLength) {
      this.sequence = sequence;
      this.start = start;
      this.truncateToLength = truncateToLength;
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
    public int priority;
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
        int associatedStreamId, int priority, List<Header> headerBlock,
        HeadersMode headersMode) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = Spdy3.TYPE_HEADERS;
      this.outFinished = outFinished;
      this.inFinished = inFinished;
      this.streamId = streamId;
      this.associatedStreamId = associatedStreamId;
      this.priority = priority;
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

    @Override public void priority(int streamId, int priority) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void pushPromise(int streamId, int associatedStreamId, List<Header> headerBlock) {
      this.type = Http20Draft09.TYPE_PUSH_PROMISE;
      this.streamId = streamId;
      this.associatedStreamId = associatedStreamId;
      this.headerBlock = headerBlock;
    }
  }
}
