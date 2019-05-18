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
package okhttp3.internal.platform

import okhttp3.Protocol
import okhttp3.internal.classForName
import okhttp3.internal.memberFunction
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.net.ssl.SSLSocket
import kotlin.reflect.KClass
import kotlin.reflect.KFunction

/** OpenJDK 8 with `org.mortbay.jetty.alpn:alpn-boot` in the boot class path.  */
class Jdk8WithJettyBootPlatform(
  private val putMethod: KFunction<Void>,
  private val getMethod: KFunction<*>,
  private val removeMethod: KFunction<*>,
  private val clientProviderClass: KClass<out Any>,
  private val serverProviderClass: KClass<out Any>
) : Platform() {
  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>
  ) {
    val names = alpnProtocolNames(protocols)

    val alpnProvider = Proxy.newProxyInstance(Platform::class.java.classLoader,
        arrayOf(clientProviderClass.java, serverProviderClass.java), AlpnProvider(names))
    putMethod.call(null, sslSocket, alpnProvider)
  }

  override fun afterHandshake(sslSocket: SSLSocket) {
    removeMethod.call(null, sslSocket)
  }

  override fun getSelectedProtocol(socket: SSLSocket): String? {
    val provider = Proxy.getInvocationHandler(getMethod.call(null, socket)) as AlpnProvider
    if (!provider.unsupported && provider.selected == null) {
      Platform.get().log(INFO,
          "ALPN callback dropped: HTTP/2 is disabled. " + "Is alpn-boot on the boot class path?",
          null)
      return null
    }
    return if (provider.unsupported) null else provider.selected
  }

  /**
   * Handle the methods of ALPN's ClientProvider and ServerProvider without a compile-time
   * dependency on those interfaces.
   */
  private class AlpnProvider internal constructor(
    /** This peer's supported protocols.  */
    private val protocols: List<String>
  ) : InvocationHandler {
    /** Set when remote peer notifies ALPN is unsupported.  */
    internal var unsupported: Boolean = false
    /** The protocol the server selected.  */
    internal var selected: String? = null

    @Throws(Throwable::class)
    override fun invoke(proxy: Any, method: Method, args: Array<Any>?): Any? {
      val callArgs = args ?: arrayOf<Any?>()
      val methodName = method.name
      val returnType = method.returnType
      if (methodName == "supports" && Boolean::class.javaPrimitiveType == returnType) {
        return true // ALPN is supported.
      } else if (methodName == "unsupported" && Void.TYPE == returnType) {
        this.unsupported = true // Peer doesn't support ALPN.
        return null
      } else if (methodName == "protocols" && callArgs.isEmpty()) {
        return protocols // Client advertises these protocols.
      } else if ((methodName == "selectProtocol" || methodName == "select") &&
          String::class.java == returnType && callArgs.size == 1 && callArgs[0] is List<*>) {
        val peerProtocols = callArgs[0] as List<*>
        // Pick the first known protocol the peer advertises.
        for (i in 0..peerProtocols.size) {
          val protocol = peerProtocols[i] as String
          if (protocols.contains(protocol)) {
            selected = protocol
            return selected
          }
        }
        selected = protocols[0] // On no intersection, try peer's first protocol.
        return selected
      } else if ((methodName == "protocolSelected" || methodName == "selected") && callArgs.size == 1) {
        this.selected = callArgs[0] as String // Server selected this protocol.
        return null
      } else {
        return method.invoke(this, *callArgs)
      }
    }
  }

  companion object {
    @JvmStatic
    fun buildIfSupported(): Platform? {
      val jvmVersion = System.getProperty("java.specification.version", "unknown")
      try {
        // 1.8, 9, 10, 11, 12 etc
        val version = jvmVersion.toInt()
        if (version >= 9) return null
      } catch (nfe: NumberFormatException) {
        // expected on >= JDK 9
      }

      // Find Jetty's ALPN extension for OpenJDK.
      try {
        val alpnClassName = "org.eclipse.jetty.alpn.ALPN"
        val alpnClass = classForName(alpnClassName)
        val providerClass = classForName("$alpnClassName\$Provider")
        val clientProviderClass = classForName("$alpnClassName\$ClientProvider")
        val serverProviderClass = classForName("$alpnClassName\$ServerProvider")
        val putMethod = alpnClass.memberFunction<Void>("put", SSLSocket::class, providerClass)
        val getMethod = alpnClass.memberFunction<Any>("get", SSLSocket::class)
        val removeMethod = alpnClass.memberFunction<Any>("remove", SSLSocket::class)
        return Jdk8WithJettyBootPlatform(
            putMethod, getMethod, removeMethod, clientProviderClass, serverProviderClass)
      } catch (ignored: ClassNotFoundException) {
      } catch (ignored: NoSuchMethodException) {
      }

      return null
    }
  }
}
