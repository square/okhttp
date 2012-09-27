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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

/**
 * A basic SPDY server that serves the contents of a local directory. This
 * server will service a single SPDY connection.
 */
public final class SpdyServer implements IncomingStreamHandler {
    private final File baseDirectory;

    public SpdyServer(File baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    private void run() throws Exception {
        ServerSocket serverSocket = new ServerSocket(8888);
        serverSocket.setReuseAddress(true);

        Socket socket = serverSocket.accept();
        SpdyConnection connection = new SpdyConnection.Builder(false, socket)
                .handler(this)
                .build();

        // Chrome doesn't seem to like pings coming from the server:
        // https://groups.google.com/forum/?fromgroups=#!topic/spdy-dev/NgTHYUQKWBY
        // System.out.println("PING RTT TIME " + connection.ping().roundTripTime());
    }

    @Override public void receive(final SpdyStream stream) throws IOException {
        List<String> requestHeaders = stream.getRequestHeaders();
        String path = null;
        for (int i = 0; i < requestHeaders.size(); i += 2) {
            String s = requestHeaders.get(i);
            if ("url".equals(s)) {
                path = requestHeaders.get(i + 1);
                break;
            }
        }

        if (path == null) {
            // TODO: send bad request error
            throw new AssertionError();
        }

        File file = new File(baseDirectory + path);

        if (file.exists() && !file.isDirectory()) {
            serveFile(stream, file);
        } else {
            send404(stream, path);
        }
    }

    private void send404(SpdyStream stream, String path) throws IOException {
        List<String> responseHeaders = Arrays.asList(
                "status", "404",
                "version", "HTTP/1.1",
                "content-type", "text/plain"
        );
        OutputStream out = stream.reply(responseHeaders);
        String text = "Not found: " + path;
        out.write(text.getBytes("UTF-8"));
        out.close();
    }

    private void serveFile(SpdyStream stream, File file) throws IOException {
        InputStream in = new FileInputStream(file);
        byte[] buffer = new byte[8192];
        OutputStream out = stream.reply(Arrays.asList(
                "status", "200",
                "version", "HTTP/1.1",
                "content-type", contentType(file)
        ));
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        out.close();
    }

    private String contentType(File file) {
        return file.getName().endsWith(".html") ? "text/html" : "text/plain";
    }

    public static void main(String... args) throws Exception {
        if (args.length != 1 || args[0].startsWith("-")) {
            System.out.println("Usage: SpdyServer <base directory>");
            return;
        }

        new SpdyServer(new File(args[0])).run();
    }
}
