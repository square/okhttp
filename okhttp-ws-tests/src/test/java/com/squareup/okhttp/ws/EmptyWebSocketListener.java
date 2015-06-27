/*
 * Copyright (C) 2015 Square, Inc.
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

import java.io.IOException;
import okio.Buffer;
import okio.BufferedSource;

class EmptyWebSocketListener implements WebSocket.Listener {
  @Override public void onMessage(BufferedSource payload, WebSocket.PayloadType type)
      throws IOException {
  }

  @Override public void onPong(Buffer payload) {
  }

  @Override public void onClose(int code, String reason) {
  }

  @Override public void onFailure(IOException e) {
  }
}
