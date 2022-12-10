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

import java.io.IOException
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
  val supportedSuites = buildList {
    for (suite in ConnectionSpec.COMPATIBLE_TLS.cipherSuites!!) {
      add(ianaSuites.fromJavaName(suite.javaName))
    }
  }
  val enabledSuites = buildList {
    for (suite in ConnectionSpec.MODERN_TLS.cipherSuites!!) {
      add(ianaSuites.fromJavaName(suite.javaName))
    }
  }

  return Client("OkHttp", OkHttp.VERSION, null, enabledSuites, supportedSuites)
}

fun historicOkHttp(version: String): Client {
  val enabled = FileSystem.RESOURCES.read("okhttp_${version.replace(".", "_")}.txt".toPath()) {
    this.readUtf8().lines().filter { it.isNotBlank() }.map {
      SuiteId(null, it.trim())
    }
  }
  return Client("OkHttp", version, null, enabled = enabled)
}

fun currentVm(ianaSuites: IanaSuites): Client {
  return systemDefault(System.getProperty("java.vm.name"), System.getProperty("java.version"), ianaSuites)
}

fun conscrypt(ianaSuites: IanaSuites): Client {
  val version = Conscrypt.version()
  return systemDefault("Conscrypt", "" + version.major() + "." + version.minor(), ianaSuites)
}

fun systemDefault(name: String, version: String, ianaSuites: IanaSuites): Client {
  return try {
    val socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    val sslSocket = socketFactory.createSocket() as SSLSocket
    val supportedSuites = buildList {
      for (suite in sslSocket.supportedCipherSuites) {
        add(ianaSuites.fromJavaName(suite))
      }
    }
    val enabledSuites = buildList {
      for (suite in sslSocket.enabledCipherSuites) {
        add(ianaSuites.fromJavaName(suite))
      }
    }

    Client(name, version, null, enabledSuites, supportedSuites)
  } catch (e: IOException) {
    throw RuntimeException(e)
  }
}
