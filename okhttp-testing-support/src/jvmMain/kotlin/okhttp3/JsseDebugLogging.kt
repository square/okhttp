/*
 * Copyright (C) 2021 Square, Inc.
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
package okhttp3

import java.io.Closeable
import java.util.logging.Handler
import java.util.logging.LogRecord

object JsseDebugLogging {
  data class JsseDebugMessage(val message: String, val param: String?) {
    enum class Type {
      Handshake, Plaintext, Encrypted, Setup, Unknown
    }

    val type: Type
      get() = when {
        message == "adding as trusted certificates" -> Type.Setup
        message == "Raw read" || message == "Raw write" -> Type.Encrypted
        message == "Plaintext before ENCRYPTION" || message == "Plaintext after DECRYPTION" -> Type.Plaintext
        message.startsWith("System property ") -> Type.Setup
        message.startsWith("Reload ") -> Type.Setup
        message == "No session to resume." -> Type.Handshake
        message.startsWith("Consuming ") -> Type.Handshake
        message.startsWith("Produced ") -> Type.Handshake
        message.startsWith("Negotiated ") -> Type.Handshake
        message.startsWith("Found resumable session") -> Type.Handshake
        message.startsWith("Resuming session") -> Type.Handshake
        message.startsWith("Using PSK to derive early secret") -> Type.Handshake
        else -> Type.Unknown
      }

    override fun toString(): String {
      return if (param != null) {
        message + "\n" + param
      } else {
        message
      }
    }
  }

  private fun quietDebug(message: JsseDebugMessage) {
    if (message.message.startsWith("Ignore")) {
      return
    }

    when (message.type) {
      JsseDebugMessage.Type.Setup, JsseDebugMessage.Type.Encrypted, JsseDebugMessage.Type.Plaintext -> {
        println(message.message + " (skipped output)")
      }
      else -> println(message)
    }
  }

  fun enableJsseDebugLogging(debugHandler: (JsseDebugMessage) -> Unit = this::quietDebug): Closeable {
    System.setProperty("javax.net.debug", "")
    return OkHttpDebugLogging.enable("javax.net.ssl", object : Handler() {
      override fun publish(record: LogRecord) {
        val param = record.parameters?.firstOrNull() as? String
        debugHandler(JsseDebugMessage(record.message, param))
      }

      override fun flush() {
      }

      override fun close() {
      }
    })
  }
}