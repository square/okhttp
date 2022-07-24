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
package okhttp3.survey

import java.util.regex.Pattern
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Example: `TLS_RSA_WITH_3DES_EDE_CBC_SHA (0xa)  112`.  */
val SSL_LABS_ROW = Pattern.compile("(\\w+)\\s+\\(0x(\\w+)\\).*")

fun parseSslLabsRow(s: String): SuiteId {
  val matcher = SSL_LABS_ROW.matcher(s)
  require(matcher.matches()) { "'$s'" }
  var hexId = matcher.group(2)
  while (hexId.length < 4) {
    hexId = "0$hexId"
  }
  val id: ByteString = hexId.decodeHex()
  return SuiteId(id, matcher.group(1))
}

val firefox65 = Client(
  "Firefox 65",
  enabled = listOf(
    parseSslLabsRow("TLS_AES_128_GCM_SHA256 (0x1301)   Forward Secrecy 	128"),
    parseSslLabsRow("TLS_CHACHA20_POLY1305_SHA256 (0x1303)   Forward Secrecy 	256"),
    parseSslLabsRow("TLS_AES_256_GCM_SHA384 (0x1302)   Forward Secrecy 	256"),
    parseSslLabsRow("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 (0xc02b)   Forward Secrecy 	128"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 (0xc02f)   Forward Secrecy 	128"),
    parseSslLabsRow(
      "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256 (0xcca9)   Forward Secrecy 	256"
    ),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256 (0xcca8)   Forward Secrecy 	256"),
    parseSslLabsRow("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 (0xc02c)   Forward Secrecy 	256"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 (0xc030)   Forward Secrecy 	256"),
    parseSslLabsRow("TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA (0xc00a)   Forward Secrecy 	256"),
    parseSslLabsRow("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA (0xc009)   Forward Secrecy 	128"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA (0xc013)   Forward Secrecy 	128"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA (0xc014)   Forward Secrecy 	256"),
    parseSslLabsRow("TLS_DHE_RSA_WITH_AES_128_CBC_SHA (0x33)   Forward Secrecy 	128"),
    parseSslLabsRow("TLS_DHE_RSA_WITH_AES_256_CBC_SHA (0x39)   Forward Secrecy 	256"),
    parseSslLabsRow("TLS_RSA_WITH_AES_128_CBC_SHA (0x2f)   WEAK 	128"),
    parseSslLabsRow("TLS_RSA_WITH_AES_256_CBC_SHA (0x35)   WEAK 	256"),
    parseSslLabsRow("TLS_RSA_WITH_3DES_EDE_CBC_SHA (0xa)   WEAK 	112")
  )
)

val chrome72 = Client(
  "Chrome 72",
  enabled = listOf(
    //parseSslLabsRow("TLS_GREASE_2A (0x2a2a)	-"),
    parseSslLabsRow("TLS_AES_128_GCM_SHA256 (0x1301)   Forward Secrecy	128"),
    parseSslLabsRow("TLS_AES_256_GCM_SHA384 (0x1302)   Forward Secrecy	256"),
    parseSslLabsRow("TLS_CHACHA20_POLY1305_SHA256 (0x1303)   Forward Secrecy	256"),
    parseSslLabsRow("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 (0xc02b)   Forward Secrecy	128"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 (0xc02f)   Forward Secrecy	128"),
    parseSslLabsRow("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 (0xc02c)   Forward Secrecy	256"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 (0xc030)   Forward Secrecy	256"),
    parseSslLabsRow(
      "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256 (0xcca9)   Forward Secrecy	256"
    ),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256 (0xcca8)   Forward Secrecy	256"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA (0xc013)   Forward Secrecy	128"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA (0xc014)   Forward Secrecy	256"),
    parseSslLabsRow("TLS_RSA_WITH_AES_128_GCM_SHA256 (0x9c)   WEAK	128"),
    parseSslLabsRow("TLS_RSA_WITH_AES_256_GCM_SHA384 (0x9d)   WEAK	256"),
    parseSslLabsRow("TLS_RSA_WITH_AES_128_CBC_SHA (0x2f)   WEAK	128"),
    parseSslLabsRow("TLS_RSA_WITH_AES_256_CBC_SHA (0x35)   WEAK	256"),
    parseSslLabsRow("TLS_RSA_WITH_3DES_EDE_CBC_SHA (0xa)   WEAK	112")
  )
)

val chrome64 = Client(
  "Chrome 64",
  enabled = listOf(
    //parseSslLabsRow("TLS_GREASE_1A (0x1a1a) -"),
    parseSslLabsRow("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 (0xc02b)   Forward Secrecy 128"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 (0xc02f)   Forward Secrecy 128"),
    parseSslLabsRow("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 (0xc02c)   Forward Secrecy 256"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 (0xc030)   Forward Secrecy 256"),
    parseSslLabsRow("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256 (0xcca9)   Forward Secrecy 256"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256 (0xcca8)   Forward Secrecy 256"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA (0xc013)   Forward Secrecy 128"),
    parseSslLabsRow("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA (0xc014)   Forward Secrecy 256"),
    parseSslLabsRow("TLS_RSA_WITH_AES_128_GCM_SHA256 (0x9c)   WEAK 128"),
    parseSslLabsRow("TLS_RSA_WITH_AES_256_GCM_SHA384 (0x9d)   WEAK 256"),
    parseSslLabsRow("TLS_RSA_WITH_AES_128_CBC_SHA (0x2f)   WEAK 128"),
    parseSslLabsRow("TLS_RSA_WITH_AES_256_CBC_SHA (0x35)   WEAK 256"),
    parseSslLabsRow("TLS_RSA_WITH_3DES_EDE_CBC_SHA (0xa)   WEAK 112")
  )
)

val android5 = parseSslLabsFile("Android5 ", "android5.txt".toPath(), FileSystem.RESOURCES)
val android9 = parseSslLabsFile("Android 9", "android9.txt".toPath(), FileSystem.RESOURCES)
val chrome65 = parseSslLabsFile("Chrome 65", "chrome65.txt".toPath(), FileSystem.RESOURCES)
val chrome70 = parseSslLabsFile("Chrome 70", "chrome70.txt".toPath(), FileSystem.RESOURCES)
val chrome80 = parseSslLabsFile("Chrome 80", "chrome80.txt".toPath(), FileSystem.RESOURCES)
val firefox72 = parseSslLabsFile("Firefox 72", "firefox72.txt".toPath(), FileSystem.RESOURCES)
val java7 = parseSslLabsFile("Java 7", "java7.txt".toPath(), FileSystem.RESOURCES)
val java12 = parseSslLabsFile("Java 12", "java12.txt".toPath(), FileSystem.RESOURCES)
val edge18 = parseSslLabsFile("Edge 18", "edge18.txt".toPath(), FileSystem.RESOURCES)

fun parseSslLabsFile(name: String, file: Path, fileSystem: FileSystem): Client {
  val lines = fileSystem.read(file) {
    this.readUtf8().lines().mapNotNull {
      if (it.isEmpty())
        null
      else
        parseSslLabsRow(it)
    }
  }

  return Client(name, enabled = lines)
}
