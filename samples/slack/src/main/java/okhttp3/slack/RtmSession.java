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
import okhttp3.NewWebSocket;
import okhttp3.Response;

/** A realtime messaging session. */
public final class RtmSession extends NewWebSocket.Listener implements Closeable {
  private final SlackApi slackApi;

  /** Guarded by this. */
  private NewWebSocket webSocket;

  public RtmSession(SlackApi slackApi) {
    this.slackApi = slackApi;
  }

  public void open(String accessToken) throws IOException {
    if (webSocket != null) throw new IllegalStateException();

    RtmStartResponse rtmStartResponse = slackApi.rtmStart(accessToken);
    webSocket = slackApi.rtm(rtmStartResponse.url, this);
  }

  // TODO(jwilson): can I read the response body? Do I have to?
  //                the body from slack is a 0-byte-buffer
  @Override public synchronized void onOpen(NewWebSocket webSocket, Response response) {
    System.out.println("onOpen: " + response);
  }

  // TOOD(jwilson): decode incoming messages and dispatch them somewhere.
  @Override public void onMessage(NewWebSocket webSocket, String text) {
    System.out.println("onMessage: " + text);
  }

  @Override public void onClosing(NewWebSocket webSocket, int code, String reason) {
    webSocket.close(1000, null);
    System.out.println("onClose (" + code + "): " + reason);
  }

  @Override public void onFailure(NewWebSocket webSocket, Throwable t, Response response) {
    // TODO(jwilson): can I read the response body? Do I have to?
    System.out.println("onFailure " + response);
  }

  @Override public void close() throws IOException {
    if (webSocket == null) return;

    NewWebSocket webSocket;
    synchronized (this) {
      webSocket = this.webSocket;
    }

    if (webSocket != null) {
      webSocket.close(1000, "bye");
    }
  }
}
