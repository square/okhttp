/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.mockwebserver

enum class SocketPolicy {
  SHUTDOWN_SERVER_AFTER_RESPONSE,
  KEEP_OPEN,
  DISCONNECT_AT_END,
  UPGRADE_TO_SSL_AT_END,
  DISCONNECT_AT_START,
  DISCONNECT_AFTER_REQUEST,
  DISCONNECT_DURING_REQUEST_BODY,
  DISCONNECT_DURING_RESPONSE_BODY,
  DO_NOT_READ_REQUEST_BODY,
  FAIL_HANDSHAKE,
  SHUTDOWN_INPUT_AT_END,
  SHUTDOWN_OUTPUT_AT_END,
  STALL_SOCKET_AT_START,
  NO_RESPONSE,
  RESET_STREAM_AT_START,
  EXPECT_CONTINUE,
  CONTINUE_ALWAYS
}
