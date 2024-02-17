/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.internal.http2

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Internal interface for HTTP/2 Frame Logging.
 */
internal interface FrameLogger {
  fun logFrame(frameLog: () -> String)

  object Noop : FrameLogger {
    override fun logFrame(frameLog: () -> String) {
    }
  }

  object Logging : FrameLogger {
    private val logger = Logger.getLogger(Http2::class.java.name)

    override fun logFrame(frameLog: () -> String) {
      if (logger.isLoggable(Level.FINE)) {
        logger.log(Level.FINE, frameLog())
      }
    }
  }
}
