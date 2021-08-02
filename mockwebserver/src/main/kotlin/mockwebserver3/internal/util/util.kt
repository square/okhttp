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
package mockwebserver3.internal.util

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Equivalent of InetAddress.getHostName() but with some tweaks for OkHttp behaviour,
 * assume 127.0.0.1 is localhost and square bracket encode IPv6 addresses.
 */
fun InetAddress.quickHostname(): String {
//  if (isLoopbackAddress) {
//    return "localhost"
//  }

  var hostname = this.hostName

  if (this is Inet6Address && hostname.contains(':')) {
    // hostname is likely some form representing the IPv6 bytes
    // 2001:0db8:85a3:0000:0000:8a2e:0370:7334
    // 2001:db8:85a3::8a2e:370:7334
    // ::1
    hostname = "[$hostname]"
  }

  return hostname.lowercase()
}
