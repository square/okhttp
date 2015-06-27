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
package com.squareup.okhttp.ws;

import com.squareup.okhttp.Response;
import java.io.IOException;

public interface WebSocketCallback {
  /**
   * Called when the request has successfully been upgraded to a web socket. You <b>must</b> call
   * {@link WebSocket#start(WebSocket.Listener) start()} on the {@code WebSocket} with a listener.
   * <p>
   * This callback is called on the reader thread. Messages from the peer will not be read until
   * this method returns and {@code start()} has been called. <b>Do not</b> use this callback to
   * write to the web socket. Start a new thread or use another thread in your application.
   */
  void onConnect(WebSocket webSocket, Response response);

  /**
   * Called when the transport or protocol layer of this web socket errors during communication.
   *
   * @param response Present when the failure is a direct result of the response (e.g., failed
   * upgrade, non-101 response code, etc.). {@code null} otherwise.
   */
  void onFailure(IOException e, Response response);
}
