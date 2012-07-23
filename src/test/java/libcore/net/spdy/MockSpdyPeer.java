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

package libcore.net.spdy;

import java.io.ByteArrayOutputStream;
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
import libcore.io.Streams;

/**
 * Replays prerecorded outgoing frames and records incoming frames.
 */
public final class MockSpdyPeer {
    private int frameCount = 0;
    private final List<OutFrame> outFrames = new ArrayList<OutFrame>();
    private final BlockingQueue<InFrame> inFrames = new LinkedBlockingQueue<InFrame>();
    private int port;
    private final Executor executor = Executors.newCachedThreadPool(
            Threads.newThreadFactory("MockSpdyPeer"));

    public void acceptFrame() {
        frameCount++;
    }

    public SpdyWriter sendFrame() {
        OutFrame frame = new OutFrame(frameCount++);
        outFrames.add(frame);
        return new SpdyWriter(frame.out);
    }

    public int getPort() {
        return port;
    }

    public InFrame takeFrame() throws InterruptedException {
        return inFrames.take();
    }

    public void play() throws IOException {
        final ServerSocket serverSocket = new ServerSocket(0);
        serverSocket.setReuseAddress(true);
        this.port = serverSocket.getLocalPort();
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    readAndWriteFrames(serverSocket);
                } catch (IOException e) {
                    e.printStackTrace(); // TODO
                }
            }
        });
    }

    private void readAndWriteFrames(ServerSocket serverSocket) throws IOException {
        Socket socket = serverSocket.accept();
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        Iterator<OutFrame> outFramesIterator = outFrames.iterator();
        OutFrame nextOutFrame = null;

        for (int i = 0; i < frameCount; i++) {
            if (nextOutFrame == null && outFramesIterator.hasNext()) {
                nextOutFrame = outFramesIterator.next();
            }

            if (nextOutFrame != null && nextOutFrame.sequence == i) {
                // write a frame
                nextOutFrame.out.writeTo(out);
                nextOutFrame = null;

            } else {
                // read a frame
                SpdyReader reader = new SpdyReader(in);
                byte[] data = null;
                int type = reader.nextFrame();
                if (type == SpdyConnection.TYPE_DATA) {
                    data = new byte[reader.length];
                    Streams.readFully(in, data);
                }
                inFrames.add(new InFrame(i, reader, data));
            }
        }
    }

    public Socket openSocket() throws IOException {
        return new Socket("localhost", port);
    }

    private static class OutFrame {
        private final int sequence;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private OutFrame(int sequence) {
            this.sequence = sequence;
        }
    }

    public static class InFrame {
        public final int sequence;
        public final SpdyReader reader;
        public final byte[] data;

        public InFrame(int sequence, SpdyReader reader, byte[] data) {
            this.sequence = sequence;
            this.reader = reader;
            this.data = data;
        }
    }
}