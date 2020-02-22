/*
 * Copyright (C) 2020 Square, Inc.
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

import android.annotation.SuppressLint;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import javax.annotation.Nullable;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import okhttp3.Protocol;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

/** Android 10+. */
class Android11Platform extends AndroidPlatform {
  private final Method setUseSessionTickets;
  private final Method isSupportedSocket;

  Android11Platform(Class<?> sslParametersClass,
      Method setUseSessionTickets,
      Method isSupportedSocket) {
    super(sslParametersClass, null, null, null, null, null);
    this.setUseSessionTickets = setUseSessionTickets;
    this.isSupportedSocket = isSupportedSocket;
  }

  @SuppressLint("NewApi")
  @IgnoreJRERequirement
  @Override public void configureTlsExtensions(
      SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
    enableSessionTickets(sslSocket);

    SSLParameters sslParameters = sslSocket.getSSLParameters();

    // Enable ALPN.
    String[] protocolsArray = Platform.alpnProtocolNames(protocols).toArray(new String[0]);
    sslParameters.setApplicationProtocols(protocolsArray);

    sslSocket.setSSLParameters(sslParameters);
  }

  private void enableSessionTickets(SSLSocket sslSocket) {
    try {
      if (isSupported(sslSocket)) {
        // TODO we should ideally build against Android R public APIs instead of reflection
        setUseSessionTickets.invoke(null, sslSocket, true);
      }
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new AssertionError(e);
    }
  }

  private boolean isSupported(SSLSocket sslSocket)
      throws InvocationTargetException, IllegalAccessException {
    return (boolean) isSupportedSocket.invoke(null, sslSocket);
  }

  @SuppressLint("NewApi")
  @IgnoreJRERequirement
  @Override public @Nullable String getSelectedProtocol(SSLSocket socket) {
    String alpnResult = socket.getApplicationProtocol();

    if (alpnResult == null || alpnResult.isEmpty()) {
      return null;
    }

    return alpnResult;
  }

  public static @Nullable Platform buildIfSupported() {
    // Attempt to find Android 10+ APIs.
    try {
      Class<?> sslParametersClass = Class.forName("com.android.org.conscrypt.SSLParametersImpl");
      Class<?> sslSocketsClass = Class.forName("android.net.ssl.SSLSockets");
      Method setUseSessionTickets = sslSocketsClass.getDeclaredMethod(
          "setUseSessionTickets", SSLSocket.class, boolean.class);
      Method isSupportedSocket = sslSocketsClass.getMethod("isSupportedSocket", SSLSocket.class);
      return new Android11Platform(sslParametersClass, setUseSessionTickets, isSupportedSocket);
    } catch (ReflectiveOperationException ignored) {
      return null; // Not an Android 11+ runtime.
    }
  }
}
