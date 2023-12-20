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
package okhttp3;

import javax.annotation.Nullable;
import okio.ByteString;

public final class RecordingWebSocketListener extends WebSocketListener {
  @Override public void onOpen(WebSocket webSocket, Response response) {
    // TODO
  }

  @Override public void onMessage(WebSocket webSocket, String text) {
    // TODO
  }

  @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
    // TODO
  }

  @Override public void onClosing(WebSocket webSocket, int code, String reason) {
    // TODO
  }

  @Override public void onClosed(WebSocket webSocket, int code, String reason) {
    // TODO
  }

  @Override public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
    // TODO
  }
}
