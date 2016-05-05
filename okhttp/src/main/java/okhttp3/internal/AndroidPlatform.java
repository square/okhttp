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
package okhttp3.internal;

import android.util.Log;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Protocol;

/** Android 2.3 or better. */
class AndroidPlatform extends Platform {
  private static final int MAX_LOG_LENGTH = 4000;

  private final Class<?> sslParametersClass;
  private final OptionalMethod<Socket> setUseSessionTickets;
  private final OptionalMethod<Socket> setHostname;

  // Non-null on Android 5.0+.
  private final OptionalMethod<Socket> getAlpnSelectedProtocol;
  private final OptionalMethod<Socket> setAlpnProtocols;

  public AndroidPlatform(Class<?> sslParametersClass, OptionalMethod<Socket> setUseSessionTickets,
      OptionalMethod<Socket> setHostname, OptionalMethod<Socket> getAlpnSelectedProtocol,
      OptionalMethod<Socket> setAlpnProtocols) {
    this.sslParametersClass = sslParametersClass;
    this.setUseSessionTickets = setUseSessionTickets;
    this.setHostname = setHostname;
    this.getAlpnSelectedProtocol = getAlpnSelectedProtocol;
    this.setAlpnProtocols = setAlpnProtocols;
  }

  @Override public void connectSocket(Socket socket, InetSocketAddress address,
      int connectTimeout) throws IOException {
    try {
      socket.connect(address, connectTimeout);
    } catch (AssertionError e) {
      if (Util.isAndroidGetsocknameError(e)) throw new IOException(e);
      throw e;
    } catch (SecurityException e) {
      // Before android 4.3, socket.connect could throw a SecurityException
      // if opening a socket resulted in an EACCES error.
      IOException ioException = new IOException("Exception in connect");
      ioException.initCause(e);
      throw ioException;
    }
  }

  @Override public X509TrustManager trustManager(SSLSocketFactory sslSocketFactory) {
    Object context = readFieldOrNull(sslSocketFactory, sslParametersClass, "sslParameters");
    if (context == null) {
      // If that didn't work, try the Google Play Services SSL provider before giving up. This
      // must be loaded by the SSLSocketFactory's class loader.
      try {
        Class<?> gmsSslParametersClass = Class.forName(
            "com.google.android.gms.org.conscrypt.SSLParametersImpl", false,
            sslSocketFactory.getClass().getClassLoader());
        context = readFieldOrNull(sslSocketFactory, gmsSslParametersClass, "sslParameters");
      } catch (ClassNotFoundException e) {
        return super.trustManager(sslSocketFactory);
      }
    }

    X509TrustManager x509TrustManager = readFieldOrNull(
        context, X509TrustManager.class, "x509TrustManager");
    if (x509TrustManager != null) return x509TrustManager;

    return readFieldOrNull(context, X509TrustManager.class, "trustManager");
  }

  @Override public void configureTlsExtensions(
      SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
    // Enable SNI and session tickets.
    if (hostname != null) {
      setUseSessionTickets.invokeOptionalWithoutCheckedException(sslSocket, true);
      setHostname.invokeOptionalWithoutCheckedException(sslSocket, hostname);
    }

    // Enable ALPN.
    if (setAlpnProtocols != null && setAlpnProtocols.isSupported(sslSocket)) {
      Object[] parameters = {concatLengthPrefixed(protocols)};
      setAlpnProtocols.invokeWithoutCheckedException(sslSocket, parameters);
    }
  }

  @Override public String getSelectedProtocol(SSLSocket socket) {
    if (getAlpnSelectedProtocol == null) return null;
    if (!getAlpnSelectedProtocol.isSupported(socket)) return null;

    byte[] alpnResult = (byte[]) getAlpnSelectedProtocol.invokeWithoutCheckedException(socket);
    return alpnResult != null ? new String(alpnResult, Util.UTF_8) : null;
  }

  @Override public void log(String message) {
    // Split by line, then ensure each line can fit into Log's maximum length.
    for (int i = 0, length = message.length(); i < length; i++) {
      int newline = message.indexOf('\n', i);
      newline = newline != -1 ? newline : length;
      do {
        int end = Math.min(newline, i + MAX_LOG_LENGTH);
        Log.d("OkHttp", message.substring(i, end));
        i = end;
      } while (i < newline);
    }
  }

  @Override public boolean isCleartextTrafficPermitted() {
    try {
      Class<?> networkPolicyClass = Class.forName("android.security.NetworkSecurityPolicy");
      Method getInstanceMethod = networkPolicyClass.getMethod("getInstance");
      Object networkSecurityPolicy = getInstanceMethod.invoke(null);
      Method isCleartextTrafficPermittedMethod = networkPolicyClass
          .getMethod("isCleartextTrafficPermitted");
      boolean cleartextPermitted = (boolean) isCleartextTrafficPermittedMethod
          .invoke(networkSecurityPolicy);
      return cleartextPermitted;
    } catch (ClassNotFoundException e) {
      return super.isCleartextTrafficPermitted();
    } catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException
        | InvocationTargetException e) {
      throw new AssertionError();
    }
  }

  public static Platform buildIfSupported() {
    // Attempt to find Android 2.3+ APIs.
    try {
      Class<?> sslParametersClass;
      try {
        sslParametersClass = Class.forName("com.android.org.conscrypt.SSLParametersImpl");
      } catch (ClassNotFoundException e) {
        // Older platform before being unbundled.
        sslParametersClass = Class.forName(
            "org.apache.harmony.xnet.provider.jsse.SSLParametersImpl");
      }

      OptionalMethod<Socket> setUseSessionTickets = new OptionalMethod<>(
          null, "setUseSessionTickets", boolean.class);
      OptionalMethod<Socket> setHostname = new OptionalMethod<>(
          null, "setHostname", String.class);
      OptionalMethod<Socket> getAlpnSelectedProtocol = null;
      OptionalMethod<Socket> setAlpnProtocols = null;

      // Attempt to find Android 5.0+ APIs.
      try {
        Class.forName("android.net.Network"); // Arbitrary class added in Android 5.0.
        getAlpnSelectedProtocol = new OptionalMethod<>(byte[].class, "getAlpnSelectedProtocol");
        setAlpnProtocols = new OptionalMethod<>(null, "setAlpnProtocols", byte[].class);
      } catch (ClassNotFoundException ignored) {
      }

      return new AndroidPlatform(sslParametersClass, setUseSessionTickets, setHostname,
          getAlpnSelectedProtocol, setAlpnProtocols);
    } catch (ClassNotFoundException ignored) {
      // This isn't an Android runtime.
    }

    return null;
  }
}
