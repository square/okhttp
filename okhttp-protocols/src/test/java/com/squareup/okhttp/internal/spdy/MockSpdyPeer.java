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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import static java.util.concurrent.Executors.defaultThreadFactory;

/** Replays prerecorded outgoing frames and records incoming frames. */
public final class MockSpdyPeer implements Closeable {
  private int frameCount = 0;
  private final ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
  private final SpdyWriter spdyWriter = new SpdyWriter(bytesOut);
  private final List<OutFrame> outFrames = new ArrayList<OutFrame>();
  private final BlockingQueue<InFrame> inFrames = new LinkedBlockingQueue<InFrame>();
  private int port;
  private final Executor executor = Executors.newCachedThreadPool(defaultThreadFactory());
  private ServerSocket serverSocket;
  private Socket socket;

  public void acceptFrame() {
    frameCount++;
  }

  public SpdyWriter sendFrame() {
    outFrames.add(new OutFrame(frameCount++, bytesOut.size(), Integer.MAX_VALUE));
    return spdyWriter;
  }

  /**
   * Sends a frame, truncated to {@code truncateToLength} bytes. This is only
   * useful for testing error handling as the truncated frame will be
   * malformed.
   */
  public SpdyWriter sendTruncatedFrame(int truncateToLength) {
    outFrames.add(new OutFrame(frameCount++, bytesOut.size(), truncateToLength));
    return spdyWriter;
  }

  public int getPort() {
    return port;
  }

  public InFrame takeFrame() throws InterruptedException {
    return inFrames.take();
  }

  public void play() throws IOException {
    if (serverSocket != null) throw new IllegalStateException();
    serverSocket = new ServerSocket(0);
    serverSocket.setReuseAddress(true);
    this.port = serverSocket.getLocalPort();
    executor.execute(new Runnable() {
      @Override public void run() {
        try {
          readAndWriteFrames();
        } catch (IOException e) {
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
    SpdyReader reader = new SpdyReader(in);

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

  @Override public void close() throws IOException {
    Socket socket = this.socket;
    if (socket != null) {
      socket.close();
      this.socket = null;
    }
    ServerSocket serverSocket = this.serverSocket;
    if (serverSocket != null) {
      serverSocket.close();
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

  public static class InFrame implements SpdyReader.Handler {
    public final int sequence;
    public final SpdyReader reader;
    public int type = -1;
    public int flags;
    public int streamId;
    public int associatedStreamId;
    public int priority;
    public int slot;
    public int statusCode;
    public int deltaWindowSize;
    public List<String> nameValueBlock;
    public byte[] data;
    public Settings settings;

    public InFrame(int sequence, SpdyReader reader) {
      this.sequence = sequence;
      this.reader = reader;
    }

    @Override public void settings(int flags, Settings settings) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = SpdyConnection.TYPE_SETTINGS;
      this.flags = flags;
      this.settings = settings;
    }

    @Override public void synStream(int flags, int streamId, int associatedStreamId, int priority,
        int slot, List<String> nameValueBlock) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = SpdyConnection.TYPE_SYN_STREAM;
      this.flags = flags;
      this.streamId = streamId;
      this.associatedStreamId = associatedStreamId;
      this.priority = priority;
      this.slot = slot;
      this.nameValueBlock = nameValueBlock;
    }

    @Override public void synReply(int flags, int streamId, List<String> nameValueBlock) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = SpdyConnection.TYPE_SYN_REPLY;
      this.streamId = streamId;
      this.flags = flags;
      this.nameValueBlock = nameValueBlock;
    }

    @Override public void headers(int flags, int streamId, List<String> nameValueBlock) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = SpdyConnection.TYPE_HEADERS;
      this.streamId = streamId;
      this.flags = flags;
      this.nameValueBlock = nameValueBlock;
    }

    @Override public void data(int flags, int streamId, InputStream in, int length)
        throws IOException {
      if (this.type != -1) throw new IllegalStateException();
      this.type = SpdyConnection.TYPE_DATA;
      this.flags = flags;
      this.streamId = streamId;
      this.data = new byte[length];
      Util.readFully(in, this.data);
    }

    @Override public void rstStream(int flags, int streamId, int statusCode) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = SpdyConnection.TYPE_RST_STREAM;
      this.flags = flags;
      this.streamId = streamId;
      this.statusCode = statusCode;
    }

    @Override public void ping(int flags, int streamId) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = SpdyConnection.TYPE_PING;
      this.flags = flags;
      this.streamId = streamId;
    }

    @Override public void noop() {
      if (this.type != -1) throw new IllegalStateException();
      this.type = SpdyConnection.TYPE_NOOP;
    }

    @Override public void goAway(int flags, int lastGoodStreamId, int statusCode) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = SpdyConnection.TYPE_GOAWAY;
      this.flags = flags;
      this.streamId = lastGoodStreamId;
      this.statusCode = statusCode;
    }

    @Override public void windowUpdate(int flags, int streamId, int deltaWindowSize) {
      if (this.type != -1) throw new IllegalStateException();
      this.type = SpdyConnection.TYPE_WINDOW_UPDATE;
      this.flags = flags;
      this.streamId = streamId;
      this.deltaWindowSize = deltaWindowSize;
    }
  }
}