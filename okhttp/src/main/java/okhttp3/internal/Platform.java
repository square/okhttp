/*
 * Copyright (C) 2012 Square, Inc.
 * Copyright (C) 2012 The Android Open Source Project
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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Protocol;
import okio.Buffer;

import static okhttp3.internal.Internal.logger;

/**
 * Access to platform-specific features.
 *
 * <h3>Server name indication (SNI)</h3>
 *
 * <p>Supported on Android 2.3+.
 *
 * Supported on OpenJDK 7+
 *
 * <h3>Session Tickets</h3>
 *
 * <p>Supported on Android 2.3+.
 *
 * <h3>Android Traffic Stats (Socket Tagging)</h3>
 *
 * <p>Supported on Android 4.0+.
 *
 * <h3>ALPN (Application Layer Protocol Negotiation)</h3>
 *
 * <p>Supported on Android 5.0+. The APIs were present in Android 4.4, but that implementation was
 * unstable.
 *
 * Supported on OpenJDK 7 and 8 (via the JettyALPN-boot library).
 *
 * Supported on OpenJDK 9 via SSLParameters and SSLSocket features.
 *
 * <h3>Trust Manager Extraction</h3>
 *
 * <p>Supported on Android 2.3+ and OpenJDK 7+. There are no public APIs to recover the trust
 * manager that was used to create an {@link SSLSocketFactory}.
 *
 * <h3>Android Cleartext Permit Detection</h3>
 *
 * <p>Supported on Android 6.0+ via {@code NetworkSecurityPolicy}.
 */
public class Platform {
  private static final Platform PLATFORM = findPlatform();

  public static Platform get() {
    return PLATFORM;
  }

  /** Prefix used on custom headers. */
  public String getPrefix() {
    return "OkHttp";
  }

  public void logW(String warning) {
    System.out.println(warning);
  }

  public X509TrustManager trustManager(SSLSocketFactory sslSocketFactory) {
    // Attempt to get the trust manager from an OpenJDK socket factory. We attempt this on all
    // platforms in order to support Robolectric, which mixes classes from both Android and the
    // Oracle JDK. Note that we don't support HTTP/2 or other nice features on Robolectric.
    try {
      Class<?> sslContextClass = Class.forName("sun.security.ssl.SSLContextImpl");
      Object context = readFieldOrNull(sslSocketFactory, sslContextClass, "context");
      if (context == null) return null;
      return readFieldOrNull(context, X509TrustManager.class, "trustManager");
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  /**
   * Configure TLS extensions on {@code sslSocket} for {@code route}.
   *
   * @param hostname non-null for client-side handshakes; null for server-side handshakes.
   */
  public void configureTlsExtensions(SSLSocket sslSocket, String hostname,
      List<Protocol> protocols) {
  }

  /**
   * Called after the TLS handshake to release resources allocated by {@link
   * #configureTlsExtensions}.
   */
  public void afterHandshake(SSLSocket sslSocket) {
  }

  /** Returns the negotiated protocol, or null if no protocol was negotiated. */
  public String getSelectedProtocol(SSLSocket socket) {
    return null;
  }

  public void connectSocket(Socket socket, InetSocketAddress address,
      int connectTimeout) throws IOException {
    socket.connect(address, connectTimeout);
  }

  public void log(String message) {
    System.out.println(message);
  }

  public boolean isCleartextTrafficPermitted() {
    return true;
  }

  public static List<String> alpnProtocolNames(List<Protocol> protocols) {
    List<String> names = new ArrayList<>(protocols.size());
    for (int i = 0, size = protocols.size(); i < size; i++) {
      Protocol protocol = protocols.get(i);
      if (protocol == Protocol.HTTP_1_0) continue; // No HTTP/1.0 for ALPN.
      names.add(protocol.toString());
    }
    return names;
  }

  /** Attempt to match the host runtime to a capable Platform implementation. */
  private static Platform findPlatform() {
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

      return new Android(sslParametersClass, setUseSessionTickets, setHostname,
          getAlpnSelectedProtocol, setAlpnProtocols);
    } catch (ClassNotFoundException ignored) {
      // This isn't an Android runtime.
    }


    // Find JDK 9 new methods
    try {
      Method setProtocolMethod =
          SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
      Method getProtocolMethod = SSLSocket.class.getMethod("getApplicationProtocol");

      return new Jdk9Platform(setProtocolMethod, getProtocolMethod);
    } catch (NoSuchMethodException ignored) {
      // pre JDK 9
    }

    // Find Jetty's ALPN extension for OpenJDK.
    try {
      String negoClassName = "org.eclipse.jetty.alpn.ALPN";
      Class<?> negoClass = Class.forName(negoClassName);
      Class<?> providerClass = Class.forName(negoClassName + "$Provider");
      Class<?> clientProviderClass = Class.forName(negoClassName + "$ClientProvider");
      Class<?> serverProviderClass = Class.forName(negoClassName + "$ServerProvider");
      Method putMethod = negoClass.getMethod("put", SSLSocket.class, providerClass);
      Method getMethod = negoClass.getMethod("get", SSLSocket.class);
      Method removeMethod = negoClass.getMethod("remove", SSLSocket.class);
      return new JdkWithJettyBootPlatform(
          putMethod, getMethod, removeMethod, clientProviderClass, serverProviderClass);
    } catch (ClassNotFoundException | NoSuchMethodException ignored) {
    }

    // Probably an Oracle JDK like OpenJDK.
    return new Platform();
  }

  /** Android 2.3 or better. */
  private static class Android extends Platform {
    private static final int MAX_LOG_LENGTH = 4000;

    private final Class<?> sslParametersClass;
    private final OptionalMethod<Socket> setUseSessionTickets;
    private final OptionalMethod<Socket> setHostname;

    // Non-null on Android 5.0+.
    private final OptionalMethod<Socket> getAlpnSelectedProtocol;
    private final OptionalMethod<Socket> setAlpnProtocols;

    public Android(Class<?> sslParametersClass, OptionalMethod<Socket> setUseSessionTickets,
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
      } catch (ClassNotFoundException | NoSuchMethodException e) {
        return super.isCleartextTrafficPermitted();
      } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
        throw new AssertionError();
      }
    }

  }

  /**
   * OpenJDK 9+.
   */
  private static final class Jdk9Platform extends Platform {
    private final Method setProtocolMethod;
    private final Method getProtocolMethod;

    public Jdk9Platform(Method setProtocolMethod, Method getProtocolMethod) {
      this.setProtocolMethod = setProtocolMethod;
      this.getProtocolMethod = getProtocolMethod;
    }

    @Override
    public void configureTlsExtensions(SSLSocket sslSocket, String hostname,
                                       List<Protocol> protocols) {
      try {
        SSLParameters sslParameters = sslSocket.getSSLParameters();

        List<String> names = alpnProtocolNames(protocols);

        setProtocolMethod.invoke(sslParameters,
            new Object[]{names.toArray(new String[names.size()])});

        sslSocket.setSSLParameters(sslParameters);
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new AssertionError();
      }
    }

    @Override
    public String getSelectedProtocol(SSLSocket socket) {
      try {
        String protocol = (String) getProtocolMethod.invoke(socket);

        // SSLSocket.getApplicationProtocol returns "" if application protocols values will not
        // be used. Observed if you didn't specify SSLParameters.setApplicationProtocols
        if (protocol == null || protocol.equals("")) {
          return null;
        }

        return protocol;
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new AssertionError();
      }
    }
  }


  /**
   * OpenJDK 7+ with {@code org.mortbay.jetty.alpn/alpn-boot} in the boot class path.
   */
  private static class JdkWithJettyBootPlatform extends Platform {
    private final Method putMethod;
    private final Method getMethod;
    private final Method removeMethod;
    private final Class<?> clientProviderClass;
    private final Class<?> serverProviderClass;

    public JdkWithJettyBootPlatform(Method putMethod, Method getMethod, Method removeMethod,
        Class<?> clientProviderClass, Class<?> serverProviderClass) {
      this.putMethod = putMethod;
      this.getMethod = getMethod;
      this.removeMethod = removeMethod;
      this.clientProviderClass = clientProviderClass;
      this.serverProviderClass = serverProviderClass;
    }

    @Override public void configureTlsExtensions(
        SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
      List<String> names = alpnProtocolNames(protocols);

      try {
        Object provider = Proxy.newProxyInstance(Platform.class.getClassLoader(),
            new Class[] {clientProviderClass, serverProviderClass}, new JettyNegoProvider(names));
        putMethod.invoke(null, sslSocket, provider);
      } catch (InvocationTargetException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    @Override public void afterHandshake(SSLSocket sslSocket) {
      try {
        removeMethod.invoke(null, sslSocket);
      } catch (IllegalAccessException | InvocationTargetException ignored) {
        throw new AssertionError();
      }
    }

    @Override public String getSelectedProtocol(SSLSocket socket) {
      try {
        JettyNegoProvider provider =
            (JettyNegoProvider) Proxy.getInvocationHandler(getMethod.invoke(null, socket));
        if (!provider.unsupported && provider.selected == null) {
          logger.log(Level.INFO, "ALPN callback dropped: SPDY and HTTP/2 are disabled. "
              + "Is alpn-boot on the boot class path?");
          return null;
        }
        return provider.unsupported ? null : provider.selected;
      } catch (InvocationTargetException | IllegalAccessException e) {
        throw new AssertionError();
      }
    }
  }

  /**
   * Handle the methods of ALPN's ClientProvider and ServerProvider without a compile-time
   * dependency on those interfaces.
   */
  private static class JettyNegoProvider implements InvocationHandler {
    /** This peer's supported protocols. */
    private final List<String> protocols;
    /** Set when remote peer notifies ALPN is unsupported. */
    private boolean unsupported;
    /** The protocol the server selected. */
    private String selected;

    public JettyNegoProvider(List<String> protocols) {
      this.protocols = protocols;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String methodName = method.getName();
      Class<?> returnType = method.getReturnType();
      if (args == null) {
        args = Util.EMPTY_STRING_ARRAY;
      }
      if (methodName.equals("supports") && boolean.class == returnType) {
        return true; // ALPN is supported.
      } else if (methodName.equals("unsupported") && void.class == returnType) {
        this.unsupported = true; // Peer doesn't support ALPN.
        return null;
      } else if (methodName.equals("protocols") && args.length == 0) {
        return protocols; // Client advertises these protocols.
      } else if ((methodName.equals("selectProtocol") || methodName.equals("select"))
          && String.class == returnType && args.length == 1 && args[0] instanceof List) {
        List<String> peerProtocols = (List) args[0];
        // Pick the first known protocol the peer advertises.
        for (int i = 0, size = peerProtocols.size(); i < size; i++) {
          if (protocols.contains(peerProtocols.get(i))) {
            return selected = peerProtocols.get(i);
          }
        }
        return selected = protocols.get(0); // On no intersection, try peer's first protocol.
      } else if ((methodName.equals("protocolSelected") || methodName.equals("selected"))
          && args.length == 1) {
        this.selected = (String) args[0]; // Server selected this protocol.
        return null;
      } else {
        return method.invoke(this, args);
      }
    }
  }

  /**
   * Returns the concatenation of 8-bit, length prefixed protocol names.
   * http://tools.ietf.org/html/draft-agl-tls-nextprotoneg-04#page-4
   */
  static byte[] concatLengthPrefixed(List<Protocol> protocols) {
    Buffer result = new Buffer();
    for (int i = 0, size = protocols.size(); i < size; i++) {
      Protocol protocol = protocols.get(i);
      if (protocol == Protocol.HTTP_1_0) continue; // No HTTP/1.0 for ALPN.
      result.writeByte(protocol.toString().length());
      result.writeUtf8(protocol.toString());
    }
    return result.readByteArray();
  }

  static <T> T readFieldOrNull(Object instance, Class<T> fieldType, String fieldName) {
    for (Class<?> c = instance.getClass(); c != Object.class; c = c.getSuperclass()) {
      try {
        Field field = c.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(instance);
        if (value == null || !fieldType.isInstance(value)) return null;
        return fieldType.cast(value);
      } catch (NoSuchFieldException ignored) {
      } catch (IllegalAccessException e) {
        throw new AssertionError();
      }
    }

    // Didn't find the field we wanted. As a last gasp attempt, try to find the value on a delegate.
    if (!fieldName.equals("delegate")) {
      Object delegate = readFieldOrNull(instance, Object.class, "delegate");
      if (delegate != null) return readFieldOrNull(delegate, fieldType, fieldName);
    }

    return null;
  }
}
