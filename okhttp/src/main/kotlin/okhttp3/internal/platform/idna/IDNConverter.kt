package okhttp3.internal.platform.idna

import java.net.IDN

/**
 * Implementation of IDN conversions using java.net.IDN with 2003 compliance.
 */
open class IDNConverter {
  open fun toASCII(domain: String): String {
    return IDN.toASCII(domain)
  }

  open fun toUnicode(domain: String): String {
    return IDN.toUnicode(domain)
  }
}
