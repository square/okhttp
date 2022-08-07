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
import okhttp3.CipherSuite
import okhttp3.survey.types.Client
import okhttp3.survey.types.Record
import okhttp3.survey.types.SuiteId
import okio.ByteString
import okio.ByteString.Companion.decodeHex

/**
 * Organizes information on SSL cipher suite inclusion and precedence for this spreadsheet.
 * https://docs.google.com/spreadsheets/d/1C3FdZSlCBq_-qrVwG1KDIzNIB3Hyg_rKAcgmSzOsHyQ/edit#gid=0
 */
class CipherSuiteSurvey(
  val clients: List<Client>,
  val ianaSuites: IanaSuites
) {
  /** Example: `0xC0,0x2B TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`.  */
  val SPREADSHEET_ROW_PATTERN = Pattern.compile("0x(\\w\\w\\w\\w)\\s+(\\w+)")

  val RECORDS: MutableMap<SuiteId, Record> = LinkedHashMap()

  init {
    addRecord(RECORDS, "0x1301  TLS_AES_128_GCM_SHA256", "", "")
    addRecord(RECORDS, "0x1302  TLS_AES_256_GCM_SHA384", "", "")
    addRecord(RECORDS, "0x1303  TLS_CHACHA20_POLY1305_SHA256", "", "")
    addRecord(RECORDS, "0x1304  TLS_AES_128_CCM_SHA256", "", "")
    addRecord(RECORDS, "0x1305  TLS_AES_128_CCM_8_SHA256", "", "")
    addRecord(RECORDS, "0xc02b TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "1", "0")
    addRecord(RECORDS, "0xc02f TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "8", "3")
    addRecord(RECORDS, "0xc02c TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "0", "1")
    addRecord(RECORDS, "0xc030 TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "2", "4")
    addRecord(RECORDS, "0xcca9 TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "", "2")
    addRecord(RECORDS, "0xcca8 TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256", "", "5")
    addRecord(RECORDS, "0xcc14 TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "", "")
    addRecord(RECORDS, "0xcc13 TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256", "", "")
    addRecord(RECORDS, "0xc009 TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "35", "6")
    addRecord(RECORDS, "0xc013 TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "36", "8")
    addRecord(RECORDS, "0xc00a TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA", "21", "7")
    addRecord(RECORDS, "0xc014 TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA", "22", "9")
    addRecord(RECORDS, "0x0033 TLS_DHE_RSA_WITH_AES_128_CBC_SHA", "40", "")
    addRecord(RECORDS, "0x0039 TLS_DHE_RSA_WITH_AES_256_CBC_SHA", "26", "")
    addRecord(RECORDS, "0x009c TLS_RSA_WITH_AES_128_GCM_SHA256", "9", "10")
    addRecord(RECORDS, "0x009d TLS_RSA_WITH_AES_256_GCM_SHA384", "3", "11")
    addRecord(RECORDS, "0x002f TLS_RSA_WITH_AES_128_CBC_SHA", "37", "12")
    addRecord(RECORDS, "0x0035 TLS_RSA_WITH_AES_256_CBC_SHA", "23", "13")
    addRecord(RECORDS, "0x000a SSL_RSA_WITH_3DES_EDE_CBC_SHA", "44", "□")
    addRecord(RECORDS, "0x009e TLS_DHE_RSA_WITH_AES_128_GCM_SHA256", "12", "")
    addRecord(RECORDS, "0xc007 TLS_ECDHE_ECDSA_WITH_RC4_128_SHA", "", "")
    addRecord(RECORDS, "0xc011 TLS_ECDHE_RSA_WITH_RC4_128_SHA", "", "")
    addRecord(RECORDS, "0x0032 TLS_DHE_DSS_WITH_AES_128_CBC_SHA", "41", "")
    addRecord(RECORDS, "0x0005 SSL_RSA_WITH_RC4_128_SHA", "", "")
    addRecord(RECORDS, "0x0004 SSL_RSA_WITH_RC4_128_MD5", "", "")
    addRecord(RECORDS, "0x00ff TLS_EMPTY_RENEGOTIATION_INFO_SCSV", "49", "14")
    addRecord(RECORDS, "0x0088 TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA", "", "")
    addRecord(RECORDS, "0x0087 TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA", "", "")
    addRecord(RECORDS, "0xcc15 TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256", "", "")
    addRecord(RECORDS, "0x0038 TLS_DHE_DSS_WITH_AES_256_CBC_SHA", "27", "")
    addRecord(RECORDS, "0xc00f TLS_ECDH_RSA_WITH_AES_256_CBC_SHA", "25", "")
    addRecord(RECORDS, "0xc005 TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA", "24", "")
    addRecord(RECORDS, "0x0084 TLS_RSA_WITH_CAMELLIA_256_CBC_SHA", "", "")
    addRecord(RECORDS, "0x0045 TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA", "", "")
    addRecord(RECORDS, "0x0044 TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA", "", "")
    addRecord(RECORDS, "0xc00c TLS_ECDH_RSA_WITH_RC4_128_SHA", "", "")
    addRecord(RECORDS, "0xc00e TLS_ECDH_RSA_WITH_AES_128_CBC_SHA", "39", "")
    addRecord(RECORDS, "0xc002 TLS_ECDH_ECDSA_WITH_RC4_128_SHA", "", "")
    addRecord(RECORDS, "0xc004 TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA", "38", "")
    addRecord(RECORDS, "0x0096 TLS_RSA_WITH_SEED_CBC_SHA", "", "")
    addRecord(RECORDS, "0x0041 TLS_RSA_WITH_CAMELLIA_128_CBC_SHA", "", "")
    addRecord(RECORDS, "0xc008 TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA", "42", "")
    addRecord(RECORDS, "0xc012 TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA", "43", "")
    addRecord(RECORDS, "0x0016 SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA", "47", "")
    addRecord(RECORDS, "0x0013 SSL_DHE_DSS_WITH_3DES_EDE_CBC_SHA", "48", "")
    addRecord(RECORDS, "0xc00d TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA", "46", "")
    addRecord(RECORDS, "0xc003 TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA", "45", "")
    addRecord(RECORDS, "0xc024 TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384", "14", "□")
    addRecord(RECORDS, "0xc028 TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", "15", "□")
    addRecord(RECORDS, "0x003d TLS_RSA_WITH_AES_256_CBC_SHA256", "16", "□")
    addRecord(RECORDS, "0xc026 TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384", "17", "")
    addRecord(RECORDS, "0xc02a TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384", "18", "")
    addRecord(RECORDS, "0x006b TLS_DHE_RSA_WITH_AES_256_CBC_SHA256", "19", "")
    addRecord(RECORDS, "0x006a TLS_DHE_DSS_WITH_AES_256_CBC_SHA256", "20", "")
    addRecord(RECORDS, "0xc023 TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", "28", "□")
    addRecord(RECORDS, "0xc027 TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "29", "□")
    addRecord(RECORDS, "0x009f TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", "6", "")
    addRecord(RECORDS, "0x003c TLS_RSA_WITH_AES_128_CBC_SHA256", "30", "□")
    addRecord(RECORDS, "0xc025 TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256", "31", "")
    addRecord(RECORDS, "0xc029 TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256", "32", "")
    addRecord(RECORDS, "0x0067 TLS_DHE_RSA_WITH_AES_128_CBC_SHA256", "33", "")
    addRecord(RECORDS, "0x0040 TLS_DHE_DSS_WITH_AES_128_CBC_SHA256", "34", "")
    addRecord(RECORDS, "0xc02e TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384", "4", "")
    addRecord(RECORDS, "0xc032 TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384", "5", "")
    addRecord(RECORDS, "0x00a3 TLS_DHE_DSS_WITH_AES_256_GCM_SHA384", "7", "")
    addRecord(RECORDS, "0xc02d TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256", "10", "")
    addRecord(RECORDS, "0xc031 TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256", "11", "")
    addRecord(RECORDS, "0x00a2 TLS_DHE_DSS_WITH_AES_128_GCM_SHA256", "13", "")
    addRecord(RECORDS, "0x00a7 TLS_DH_anon_WITH_AES_256_GCM_SHA384", "□", "")
    addRecord(RECORDS, "0x00a6 TLS_DH_anon_WITH_AES_128_GCM_SHA256", "□", "")
    addRecord(RECORDS, "0x006d TLS_DH_anon_WITH_AES_256_CBC_SHA256", "□", "")
    addRecord(RECORDS, "0xc019 TLS_ECDH_anon_WITH_AES_256_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x003a TLS_DH_anon_WITH_AES_256_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x006c TLS_DH_anon_WITH_AES_128_CBC_SHA256", "□", "")
    addRecord(RECORDS, "0xc018 TLS_ECDH_anon_WITH_AES_128_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x0034 TLS_DH_anon_WITH_AES_128_CBC_SHA", "□", "")
    addRecord(RECORDS, "0xc016 TLS_ECDH_anon_WITH_RC4_128_SHA", "", "")
    addRecord(RECORDS, "0x0018 SSL_DH_anon_WITH_RC4_128_MD5", "", "")
    addRecord(RECORDS, "0xc017 TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x001b SSL_DH_anon_WITH_3DES_EDE_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x003b TLS_RSA_WITH_NULL_SHA256", "□", "")
    addRecord(RECORDS, "0xc006 TLS_ECDHE_ECDSA_WITH_NULL_SHA", "□", "")
    addRecord(RECORDS, "0xc010 TLS_ECDHE_RSA_WITH_NULL_SHA", "□", "")
    addRecord(RECORDS, "0x0002 SSL_RSA_WITH_NULL_SHA", "□", "")
    addRecord(RECORDS, "0xc001 TLS_ECDH_ECDSA_WITH_NULL_SHA", "□", "")
    addRecord(RECORDS, "0xc00b TLS_ECDH_RSA_WITH_NULL_SHA", "□", "")
    addRecord(RECORDS, "0xc015 TLS_ECDH_anon_WITH_NULL_SHA", "□", "")
    addRecord(RECORDS, "0x0001 SSL_RSA_WITH_NULL_MD5", "□", "")
    addRecord(RECORDS, "0x0009 SSL_RSA_WITH_DES_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x0015 SSL_DHE_RSA_WITH_DES_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x0012 SSL_DHE_DSS_WITH_DES_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x001a SSL_DH_anon_WITH_DES_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x0003 SSL_RSA_EXPORT_WITH_RC4_40_MD5", "", "")
    addRecord(RECORDS, "0x0017 SSL_DH_anon_EXPORT_WITH_RC4_40_MD5", "", "")
    addRecord(RECORDS, "0x0008 SSL_RSA_EXPORT_WITH_DES40_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x0014 SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x0011 SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x0019 SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x0020 TLS_KRB5_WITH_RC4_128_SHA", "", "")
    addRecord(RECORDS, "0x0024 TLS_KRB5_WITH_RC4_128_MD5", "", "")
    addRecord(RECORDS, "0x001f TLS_KRB5_WITH_3DES_EDE_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x0023 TLS_KRB5_WITH_3DES_EDE_CBC_MD5", "□", "")
    addRecord(RECORDS, "0x001e TLS_KRB5_WITH_DES_CBC_SHA", "□", "")
    addRecord(RECORDS, "0x0022 TLS_KRB5_WITH_DES_CBC_MD5", "□", "")
    addRecord(RECORDS, "0x0028 TLS_KRB5_EXPORT_WITH_RC4_40_SHA", "", "")
    addRecord(RECORDS, "0x002b TLS_KRB5_EXPORT_WITH_RC4_40_MD5", "", "")
    addRecord(RECORDS, "0x0026 TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA", "□", "")
    addRecord(RECORDS, "0x0029 TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5", "□", "")
    addRecord(RECORDS, "0xc035 TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA", "", "□")
    addRecord(RECORDS, "0xc036 TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA", "", "□")
    addRecord(RECORDS, "0x5600 TLS_FALLBACK_SCSV", "", "□")
    addRecord(RECORDS, "0x008b TLS_PSK_WITH_3DES_EDE_CBC_SHA", "", "")
    addRecord(RECORDS, "0x008c TLS_PSK_WITH_AES_128_CBC_SHA", "", "□")
    addRecord(RECORDS, "0x008d TLS_PSK_WITH_AES_256_CBC_SHA", "", "□")
    addRecord(RECORDS, "0x008a TLS_PSK_WITH_RC4_128_SHA", "", "")
  }

  fun printCipherSuiteKt() {
    for (suiteId in ianaSuites.suites) {
      val record = RECORDS[suiteId]
      val message = toCodeString(record, suiteId)
      println(message)
    }
  }

  fun printGoogleSheet() {
    print("id")
    print("\tname")
    for (client in clients) {
      print("\t")
      print(client.nameAndVersion)
    }
    println()
    for (suiteId in RECORDS.keys) {
      print("0x")
      print(suiteId.id?.hex() ?: "-")
      print("\t")
      print(suiteId.name)
      for (client in clients) {
        print("\t")
        val index = client.enabled.indexOf(suiteId)
        if (index != -1) {
          print(index)
        } else if (client.disabled.contains(suiteId)) {
          print("□")
        }
      }
      println()
    }
  }

  fun printWarnings() {
    for (client in clients) {
      for (suiteId in client.enabled) {
        if (!RECORDS.containsKey(suiteId)) {
          println("Unexpected suite " + suiteId + " in " + client.nameAndVersion)
        }
      }
      for (suiteId in client.disabled) {
        if (!RECORDS.containsKey(suiteId)) {
          println("Unexpected suite " + suiteId + " in " + client.nameAndVersion)
        }
      }
    }
  }

  private fun toCodeString(record: Record?, suiteId: SuiteId): String {
    return buildString {
      if (record == null || record.java.trim { it <= ' ' }
          .isEmpty() && record.android.trim { it <= ' ' }
          .isEmpty()) {
        append("    // @JvmField val ")
      } else {
        append("    @JvmField val ")
      }
      append(suiteId.name)
      append(" = init(\"")
      append(sanitiseStringName(suiteId.name))
      append("\", ")
      append("0x")
      append(suiteId.id!!.hex())
      append(")")
    }
  }

  private fun sanitiseStringName(name: String): String {
    return CipherSuite.forJavaName(name).javaName
  }

  fun addRecord(records: MutableMap<SuiteId, Record>, s: String, java: String, android: String) {
    val matcher = SPREADSHEET_ROW_PATTERN.matcher(s)
    if (!matcher.matches()) throw IllegalArgumentException(s)
    val id: ByteString = matcher.group(1).decodeHex()
    val suiteId = SuiteId(id, matcher.group(2))
    records[suiteId] = Record(java, android)
  }

  fun showIanaDiff(ianaSuitesOld: IanaSuites, ianaSuitesNew: IanaSuites) {
    val oldMap = ianaSuitesOld.suites.associateBy { it.id }
    val newMap = ianaSuitesNew.suites.associateBy { it.id }

    println("Removed ${ianaSuitesOld.name} -> ${ianaSuitesNew.name}")
    (oldMap - newMap.keys).values.forEach {
      println(it)
    }
    println()

    println("Added ${ianaSuitesOld.name} -> ${ianaSuitesNew.name}")
    (newMap - oldMap.keys).values.forEach {
      println(it)
    }
    println()
  }
}
