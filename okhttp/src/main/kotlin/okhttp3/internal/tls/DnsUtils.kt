/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package okhttp3.internal.tls

/**
 * A collection of utilities relating to Domain Name System.
 *
 * https://raw.githubusercontent.com/apache/httpcomponents-client/master/httpclient5/src/main/java/org/apache/hc/client5/http/utils/DnsUtils.java
 */
internal object DnsUtils {
  private fun isIA5(c: Char): Boolean {
    return c < 128.toChar()
  }

  private fun isUpper(c: Char): Boolean {
    return c in 'A'..'Z'
  }

  // https://tools.ietf.org/html/rfc2459#section-4.2.1.11
  fun normalizeIA5String(s: String): String {
    var pos = 0
    var remaining = s.length
    while (remaining > 0) {
      // TODO enable if we agree to enforce here
//      check (isIA5(s[pos])) {
//        "Invalid char ${s[pos]} in hostname $s"
//      }
      if (isUpper(s[pos])) {
        break
      }
      pos++
      remaining--
    }
    return if (remaining > 0) {
      buildString(s.length) {
        append(s, 0, pos)
        while (remaining > 0) {
          val c = s[pos]
          if (isUpper(c)) {
            append((c.toInt() + ('a' - 'A')).toChar())
          } else {
            append(c)
          }
          pos++
          remaining--
        }
      }
    } else {
      s
    }
  }
}