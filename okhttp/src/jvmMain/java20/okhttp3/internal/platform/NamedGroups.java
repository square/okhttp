/*
 * Copyright (C) 2026 Square, Inc.
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
package okhttp3.internal.platform;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Java 20+ multi-release replacement for the no-op {@code NamedGroups} compiled from
 * {@code NamedGroups.kt}. Packaged under {@code META-INF/versions/20/}, it is loaded in place of the
 * base class when OkHttp runs on a Java 20 or newer runtime, where
 * {@link SSLParameters#setNamedGroups(String[])} is available.
 *
 * <p>The class name and the {@code applyNamedGroups} signature must stay in sync with the Kotlin
 * base implementation so that the call site in {@code ConnectionSpec} links against either variant.
 */
public final class NamedGroups {
  private NamedGroups() {
  }

  public static void applyNamedGroups(SSLSocket sslSocket, String[] namedGroups) {
    SSLParameters sslParameters = sslSocket.getSSLParameters();
    sslParameters.setNamedGroups(namedGroups);
    sslSocket.setSSLParameters(sslParameters);
  }
}
