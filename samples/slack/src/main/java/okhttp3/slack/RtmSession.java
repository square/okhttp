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
package okhttp3.slack;

import java.io.Closeable;
import java.io.IOException;
import okhttp3.Body;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketCall;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** A realtime messaging session. */
public final class RtmSession implements WebSocketListener, Closeable {
  private final SlackApi slackApi;
  private WebSocketCall webSocketCall;

  /** Guarded by this. */
  private WebSocket webSocket;

  public RtmSession(SlackApi slackApi) {
    this.slackApi = slackApi;
  }

  public void open(String accessToken) throws IOException {
    if (webSocketCall != null) throw new IllegalStateException();

    RtmStartResponse rtmStartResponse = slackApi.rtmStart(accessToken);
    webSocketCall = slackApi.rtm(rtmStartResponse.url);
    webSocketCall.enqueue(this);
  }

  // TODO(jwilson): can I read the response body? Do I have to?
  //                the body from slack is a 0-byte-buffer
  @Override public synchronized void onOpen(WebSocket webSocket, Response response) {
    System.out.println("onOpen: " + response);
    this.webSocket = webSocket;
  }

  // TOOD(jwilson): decode incoming messages and dispatch them somewhere.
  @Override public void onMessage(Body message) throws IOException {
    System.out.println("onMessage: " + message.string());
  }

  @Override public void onPong(ByteString payload) {
    System.out.println("onPong: " + payload);
  }

  @Override public void onClose(int code, String reason) {
    System.out.println("onClose (" + code + "): " + reason);
  }

  // TODO(jwilson): can I read the response body? Do I have to?
  @Override public void onFailure(Throwable t, Response response) {
    System.out.println("onFailure " + response);
  }

  @Override public void close() throws IOException {
    if (webSocketCall == null) return;

    WebSocket webSocket;
    synchronized (this) {
      webSocket = this.webSocket;
    }

    // TODO(jwilson): Racy? Is there an interleaving of events where the websocket is not closed?
    //                Our docs say we can’t close if we have an active writer: that seems like it
    //                could cause problems?
    if (webSocket != null) {
      webSocket.close(1000, "bye");
    } else {
      webSocketCall.cancel();
    }
  }
}
