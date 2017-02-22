/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3;

/**
 * Versions of TLS that can be offered when negotiating a secure socket. See {@link
 * javax.net.ssl.SSLSocket#setEnabledProtocols}.
 */
public enum TlsVersion {
  TLS_1_3("TLSv1.3"), // 2016.
  TLS_1_2("TLSv1.2"), // 2008.
  TLS_1_1("TLSv1.1"), // 2006.
  TLS_1_0("TLSv1"),   // 1999.
  SSL_3_0("SSLv3"),   // 1996.
  ;

  final String javaName;

  TlsVersion(String javaName) {
    this.javaName = javaName;
  }

  public static TlsVersion forJavaName(String javaName) {
    switch (javaName) {
      case "TLSv1.3":
        return TLS_1_3;
      case "TLSv1.2":
        return TLS_1_2;
      case "TLSv1.1":
        return TLS_1_1;
      case "TLSv1":
        return TLS_1_0;
      case "SSLv3":
        return SSL_3_0;
    }
    throw new IllegalArgumentException("Unexpected TLS version: " + javaName);
  }

  public String javaName() {
    return javaName;
  }
}
