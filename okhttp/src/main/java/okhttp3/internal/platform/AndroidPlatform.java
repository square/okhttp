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
package okhttp3.internal.platform;

import android.os.Build;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSocket;
import okhttp3.Protocol;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Android 5+. */
class AndroidPlatform extends BaseAndroidPlatform {
  private final Class<?> sslSocketClass;
  private final Method setUseSessionTickets;
  private final Method setHostname;
  private final Method getAlpnSelectedProtocol;
  private final Method setAlpnProtocols;

  AndroidPlatform(Class<?> sslParametersClass, Class<?> sslSocketClass, Method setUseSessionTickets,
      Method setHostname, Method getAlpnSelectedProtocol, Method setAlpnProtocols) {
    super(sslParametersClass);
    this.sslSocketClass = sslSocketClass;
    this.setUseSessionTickets = setUseSessionTickets;
    this.setHostname = setHostname;
    this.getAlpnSelectedProtocol = getAlpnSelectedProtocol;
    this.setAlpnProtocols = setAlpnProtocols;
  }

  @Override public void configureTlsExtensions(
      SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
    if (!sslSocketClass.isInstance(sslSocket)) {
      return; // No TLS extensions if the socket class is custom.
    }
    try {
      // Enable SNI and session tickets.
      if (hostname != null) {
        setUseSessionTickets.invoke(sslSocket, true);
        // This is SSLParameters.setServerNames() in API 24+.
        setHostname.invoke(sslSocket, hostname);
      }

      // Enable ALPN.
      setAlpnProtocols.invoke(sslSocket, concatLengthPrefixed(protocols));
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new AssertionError(e);
    }
  }

  @Override public @Nullable String getSelectedProtocol(SSLSocket socket) {
    if (!sslSocketClass.isInstance(socket)) {
      return null; // No TLS extensions if the socket class is custom.
    }
    try {
      byte[] alpnResult = (byte[]) getAlpnSelectedProtocol.invoke(socket);
      return alpnResult != null ? new String(alpnResult, UTF_8) : null;
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new AssertionError(e);
    }
  }

  public static @Nullable Platform buildIfSupported() {
    // Attempt to find Android 5+ APIs.
    Class<?> sslParametersClass;
    Class<?> sslSocketClass;
    try {
      sslParametersClass = Class.forName("com.android.org.conscrypt.SSLParametersImpl");
      sslSocketClass = Class.forName("com.android.org.conscrypt.OpenSSLSocketImpl");
      Method setUseSessionTickets = sslSocketClass.getDeclaredMethod(
          "setUseSessionTickets", boolean.class);
      Method setHostname = sslSocketClass.getMethod("setHostname", String.class);
      Method getAlpnSelectedProtocol = sslSocketClass.getMethod("getAlpnSelectedProtocol");
      Method setAlpnProtocols = sslSocketClass.getMethod("setAlpnProtocols", byte[].class);

      return new AndroidPlatform(sslParametersClass, sslSocketClass, setUseSessionTickets,
          setHostname, getAlpnSelectedProtocol, setAlpnProtocols);
    } catch (ReflectiveOperationException ignored) {
      if (Build.VERSION.SDK_INT < 21) {
        throw new IllegalStateException(
            "Expected Android API level 21+ but was " + Build.VERSION.SDK_INT);
      }

      return null; // Not an Android runtime.
    }
  }
}
