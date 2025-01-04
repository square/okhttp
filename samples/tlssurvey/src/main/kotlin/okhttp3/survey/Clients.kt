/*
 * Copyright (C) 2022 Square, Inc.
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

import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.ConnectionSpec
import okhttp3.OkHttp
import okhttp3.survey.types.Client
import okhttp3.survey.types.SuiteId
import okio.FileSystem
import okio.Path.Companion.toPath
import org.conscrypt.Conscrypt

fun currentOkHttp(ianaSuites: IanaSuites): Client {
  return Client(
    userAgent = "OkHttp",
    version = OkHttp.VERSION,
    enabled =
      ConnectionSpec.MODERN_TLS.cipherSuites!!.map {
        ianaSuites.fromJavaName(it.javaName)
      },
    supported =
      ConnectionSpec.COMPATIBLE_TLS.cipherSuites!!.map {
        ianaSuites.fromJavaName(it.javaName)
      },
  )
}

fun historicOkHttp(version: String): Client {
  val enabled =
    FileSystem.RESOURCES.read("okhttp_$version.txt".toPath()) {
      this.readUtf8().lines().filter { it.isNotBlank() }.map {
        SuiteId(id = null, name = it.trim())
      }
    }
  return Client(
    userAgent = "OkHttp",
    version = version,
    enabled = enabled,
  )
}

fun currentVm(ianaSuites: IanaSuites): Client {
  return systemDefault(
    name = System.getProperty("java.vm.name"),
    version = System.getProperty("java.version"),
    ianaSuites = ianaSuites,
  )
}

fun conscrypt(ianaSuites: IanaSuites): Client {
  val version = Conscrypt.version()
  return systemDefault(
    name = "Conscrypt",
    version = "${version.major()}.${version.minor()}",
    ianaSuites = ianaSuites,
  )
}

fun systemDefault(
  name: String,
  version: String,
  ianaSuites: IanaSuites,
): Client {
  val socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
  val sslSocket = socketFactory.createSocket() as SSLSocket

  return Client(
    userAgent = name,
    version = version,
    enabled = sslSocket.enabledCipherSuites.map { ianaSuites.fromJavaName(it) },
    supported = sslSocket.supportedCipherSuites.map { ianaSuites.fromJavaName(it) },
  )
}
