/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.okwebserver;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Util;
import okhttp3.webserver.OkHttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public final class OkHttpServerTest {

  OkHttpServer server;
  OkHttpClient client;
  FakeDispatcher dispatcher;

  @Before public void before() throws IOException {
    client = new OkHttpClient();
    server = new OkHttpServer();
    dispatcher = new FakeDispatcher();
    server.setDispatcher(dispatcher);
  }

  @After public void after() throws IOException {
    server.close();
  }

  @Test public void basicHttpGet() throws Exception {
    server.setProtocols(Util.immutableList(Protocol.HTTP_1_1));
    server.start();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .get()
        .build();

    Response response = client.newCall(request).execute();
    assertEquals(200, response.code());
    assertEquals("OK", response.message());
    assertEquals(Protocol.HTTP_1_1, response.protocol());
    assertEquals(1, response.headers().size());
    assertEquals("0", response.header("Content-Length"));
  }
}
