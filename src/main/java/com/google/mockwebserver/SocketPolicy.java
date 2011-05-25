/*
 * Copyright (C) 2011 Google Inc.
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

package com.google.mockwebserver;

/**
 * What should be done with the incoming socket.
 */
public enum SocketPolicy {

    /**
     * Keep the socket open after the response. This is the default HTTP/1.1
     * behavior.
     */
    KEEP_OPEN,

    /**
     * Close the socket after the response. This is the default HTTP/1.0
     * behavior.
     */
    DISCONNECT_AT_END,

    /**
     * Wrap the socket with SSL at the completion of this request/response
     * pair. Used for CONNECT messages to tunnel SSL over an HTTP proxy.
     */
    UPGRADE_TO_SSL_AT_END,

    /**
     * Request immediate close of connection without even reading the
     * request.
     *
     * <p>Use to simulate the real life case of losing connection
     * because of bugger SSL server close connection when it seems
     * something like a compression method or TLS extension it doesn't
     * understand, instead of simply ignoring it like it should.
     */
    DISCONNECT_AT_START,

    /**
     * Shutdown the socket input after sending the response. For testing bad
     * behavior.
     */
    SHUTDOWN_INPUT_AT_END,

    /**
     * Shutdown the socket output after sending the response. For testing bad
     * behavior.
     */
    SHUTDOWN_OUTPUT_AT_END
}
