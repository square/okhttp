package okhttp3.internal.platform.idna

import android.annotation.SuppressLint
import android.icu.text.IDNA

@SuppressLint("NewApi")
/**
 * Implementation of IDN conversions using android.icu.text.IDNA to achieve 2008 compliance.
 */
class ICUIDNConverter: IDNConverter() {
  var idna = IDNA.getUTS46Instance(
    IDNA.NONTRANSITIONAL_TO_ASCII
      or IDNA.NONTRANSITIONAL_TO_UNICODE
      or IDNA.CHECK_BIDI
      or IDNA.CHECK_CONTEXTJ
      or IDNA.CHECK_CONTEXTO
      or IDNA.USE_STD3_RULES
  )

  override fun toASCII(domain: String): String {
    val output = StringBuilder()
    val info = IDNA.Info()
    idna.nameToASCII(domain, output, info)
    if (info.hasErrors()) {
      throw IllegalArgumentException("nameToASCII error " + info.errors)
    }
    return output.toString()
  }

  override fun toUnicode(domain: String): String {
    val output = StringBuilder()
    val info = IDNA.Info()
    idna.nameToUnicode(domain, output, info)
    if (info.hasErrors()) {
      throw IllegalArgumentException("nameToUnicode error " + info.errors)
    }
    return output.toString()
  }
}
