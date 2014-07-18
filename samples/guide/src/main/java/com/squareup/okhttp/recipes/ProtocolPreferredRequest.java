/*
 * Copyright (C) 2014 Square, Inc.
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

package com.squareup.okhttp.recipes;

import java.io.IOException;

import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

/**
 * It demonstrates how to connect a http(without TLS NPN/ALPN) based spdy host
 * using spdy 3.1 protocol preferred request. This feature is meaningful for
 * some web service refining with spdy. and also it can unify the web service
 * request facade such as Andriod and iOS, one example for iOS request:
 * https://github.com/twitter/CocoaSPDY
 */
public class ProtocolPreferredRequest {

  OkHttpClient client = new OkHttpClient();
  {
    // config the preferred protocol for a specified host.
    // 118.186.217.31 hosts a spdy 3.1(can access without TSL NPN/ALPN) web
    // service.
    client.setPreferredProtocol("118.186.217.31", Protocol.SPDY_3);
  }

  String run(String url) throws IOException {
    Request request = new Request.Builder().url(url).build();

    Response response = client.newCall(request).execute();
    System.out.println(response.headers());
    return response.body().string();
  }

  public static void main(String[] args) throws IOException {
    ProtocolPreferredRequest request = new ProtocolPreferredRequest();
    String response = request.run("http://118.186.217.31/");
    System.out.println(response);

    // evict all connections.
    ConnectionPool.getDefault().evictAll();
  }
}
