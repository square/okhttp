/*
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

package libcore.util;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocket;
import org.eclipse.jetty.npn.NextProtoNego;

/**
 * APIs for interacting with Android's core library. This mostly emulates the
 * Android core library for interoperability with other runtimes.
 */
public final class Libcore {

    private Libcore() {
    }

    private static boolean useAndroidTlsApis;
    private static Class<?> openSslSocketClass;
    private static Method setUseSessionTickets;
    private static Method setHostname;
    private static boolean android23TlsOptionsAvailable;
    private static Method setNpnProtocols;
    private static Method getNpnSelectedProtocol;
    private static boolean android41TlsOptionsAvailable;

    static {
        try {
            openSslSocketClass = Class.forName(
                    "org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl");
            useAndroidTlsApis = true;
            setUseSessionTickets = openSslSocketClass.getMethod(
                    "setUseSessionTickets", boolean.class);
            setHostname = openSslSocketClass.getMethod("setHostname", String.class);
            android23TlsOptionsAvailable = true;
            setNpnProtocols = openSslSocketClass.getMethod("setNpnProtocols", byte[].class);
            getNpnSelectedProtocol = openSslSocketClass.getMethod("getNpnSelectedProtocol");
            android41TlsOptionsAvailable = true;
        } catch (ClassNotFoundException ignored) {
            // This isn't an Android runtime.
        } catch (NoSuchMethodException ignored) {
            // This Android runtime is missing some optional TLS options.
        }
    }

    public static void makeTlsTolerant(SSLSocket socket, String uriHost, boolean tlsTolerant) {
        if (!tlsTolerant) {
            socket.setEnabledProtocols(new String[] {"SSLv3"});
            return;
        }

        if (android23TlsOptionsAvailable && openSslSocketClass.isInstance(socket)) {
            // This is Android: use reflection on OpenSslSocketImpl.
            try {
                setUseSessionTickets.invoke(socket, true);
                setHostname.invoke(socket, uriHost);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Returns the negotiated protocol, or null if no protocol was negotiated.
     */
    public static byte[] getNpnSelectedProtocol(SSLSocket socket) {
        if (useAndroidTlsApis) {
            // This is Android: use reflection on OpenSslSocketImpl.
            if (android41TlsOptionsAvailable && openSslSocketClass.isInstance(socket)) {
                try {
                    return (byte[]) getNpnSelectedProtocol.invoke(socket);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
            return null;
        } else {
            // This is OpenJDK: use JettyNpnProvider.
            JettyNpnProvider provider = (JettyNpnProvider) NextProtoNego.get(socket);
            if (!provider.unsupported && provider.selected == null) {
                throw new IllegalStateException(
                        "No callback received. Is NPN configured properly?");
            }
            try {
                return provider.unsupported
                        ? null
                        : provider.selected.getBytes("US-ASCII");
            } catch (UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }
        }
    }

    public static void setNpnProtocols(SSLSocket socket, byte[] npnProtocols) {
        if (useAndroidTlsApis) {
            // This is Android: use reflection on OpenSslSocketImpl.
            if (android41TlsOptionsAvailable && openSslSocketClass.isInstance(socket)) {
                try {
                    setNpnProtocols.invoke(socket, new Object[] {npnProtocols});
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
        } else {
            // This is OpenJDK: use JettyNpnProvider.
            try {
                List<String> strings = new ArrayList<String>();
                for (int i = 0; i < npnProtocols.length;) {
                    int length = npnProtocols[i++];
                    strings.add(new String(npnProtocols, i, length, "US-ASCII"));
                    i += length;
                }
                JettyNpnProvider provider = new JettyNpnProvider();
                provider.protocols = strings;
                NextProtoNego.put(socket, provider);
            } catch (UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static class JettyNpnProvider
            implements NextProtoNego.ClientProvider, NextProtoNego.ServerProvider {
        List<String> protocols;
        boolean unsupported;
        String selected;

        @Override public boolean supports() {
            return true;
        }
        @Override public List<String> protocols() {
            return protocols;
        }
        @Override public void unsupported() {
            this.unsupported = true;
        }
        @Override public void protocolSelected(String selected) {
            this.selected = selected;
        }
        @Override public String selectProtocol(List<String> strings) {
            // TODO: use OpenSSL's algorithm which uses 2 lists
            System.out.println("CLIENT PROTOCOLS: " + protocols + " SERVER PROTOCOLS: " + strings);
            String selected = protocols.get(0);
            protocolSelected(selected);
            return selected;
        }
    }

    public static void deleteIfExists(File file) throws IOException {
        // okhttp-changed: was Libcore.os.remove() in a try/catch block
        file.delete();
    }

    public static void logW(String warning) {
        // okhttp-changed: was System.logw()
        System.out.println(warning);
    }

    public static int getEffectivePort(URI uri) {
        return getEffectivePort(uri.getScheme(), uri.getPort());
    }

    public static int getEffectivePort(URL url) {
        return getEffectivePort(url.getProtocol(), url.getPort());
    }

    private static int getEffectivePort(String scheme, int specifiedPort) {
        return specifiedPort != -1
                ? specifiedPort
                : getDefaultPort(scheme);
    }

    public static int getDefaultPort(String scheme) {
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        } else if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        } else {
            return -1;
        }
    }

    public static void checkOffsetAndCount(int arrayLength, int offset, int count) {
        if ((offset | count) < 0 || offset > arrayLength || arrayLength - offset < count) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    public static void tagSocket(Socket socket) {
    }

    public static void untagSocket(Socket socket) throws SocketException {
    }

    public static URI toUriLenient(URL url) throws URISyntaxException {
        return url.toURI(); // this isn't as good as the built-in toUriLenient
    }

    public static void pokeInt(byte[] dst, int offset, int value, ByteOrder order) {
        if (order == ByteOrder.BIG_ENDIAN) {
            dst[offset++] = (byte) ((value >> 24) & 0xff);
            dst[offset++] = (byte) ((value >> 16) & 0xff);
            dst[offset++] = (byte) ((value >>  8) & 0xff);
            dst[offset  ] = (byte) ((value >>  0) & 0xff);
        } else {
            dst[offset++] = (byte) ((value >>  0) & 0xff);
            dst[offset++] = (byte) ((value >>  8) & 0xff);
            dst[offset++] = (byte) ((value >> 16) & 0xff);
            dst[offset  ] = (byte) ((value >> 24) & 0xff);
        }
    }
}
