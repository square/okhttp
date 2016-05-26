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
package okhttp3.internal.framed;

import java.io.IOException;

/** Thrown when an HTTP/2 stream is canceled without damage to the socket that carries it. */
public final class StreamResetException extends IOException {
  public final ErrorCode errorCode;

  public StreamResetException(ErrorCode errorCode) {
    super("stream was reset: " + errorCode);
    this.errorCode = errorCode;
  }
}
