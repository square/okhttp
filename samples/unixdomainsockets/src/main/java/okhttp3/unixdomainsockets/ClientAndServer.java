/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.unixdomainsockets;

import java.io.File;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * Create UNIX domain sockets for MockWebServer and OkHttp and connect 'em together. Note that we
 * cannot do TLS over domain sockets.
 */
public class ClientAndServer {
  public void run() throws Exception {
    File socketFile = new File("/tmp/ClientAndServer.sock");
    socketFile.delete(); // Clean up from previous runs.

    MockWebServer server = new MockWebServer();
    server.setServerSocketFactory(new UnixDomainServerSocketFactory(socketFile));
    server.enqueue(new MockResponse().setBody("hello"));
    server.start();

    OkHttpClient client = new OkHttpClient.Builder()
        .socketFactory(new UnixDomainSocketFactory(socketFile))
        .build();

    Request request = new Request.Builder()
        .url("http://publicobject.com/helloworld.txt")
        .build();

    try (Response response = client.newCall(request).execute()) {
      System.out.println(response.body().string());
    }

    server.shutdown();
    socketFile.delete();
  }

  public static void main(String... args) throws Exception {
    new ClientAndServer().run();
  }
}
