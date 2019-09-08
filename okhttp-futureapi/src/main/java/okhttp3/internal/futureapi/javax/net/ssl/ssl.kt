package okhttp3.internal.futureapi.javax.net.ssl

import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

val SSLSocket.applicationProtocolX: String?
  get() = this.applicationProtocol

var SSLParameters.applicationProtocolsX: Array<String>
  get() = this.applicationProtocols
  set(protocols) {
    applicationProtocols = protocols
  }