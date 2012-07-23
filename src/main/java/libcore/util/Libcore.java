package libcore.util;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
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
public class Libcore {
    
    public static void makeTlsTolerant(SSLSocket socket, String socketHost, boolean tlsTolerant) {
        if (!tlsTolerant) {
            socket.setEnabledProtocols(new String [] { "SSLv3" });
            return;
        }

        try {
            Class<?> openSslSocketClass = Class.forName(
                    "org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl");
            if (openSslSocketClass.isInstance(socket)) {
                openSslSocketClass.getMethod("setEnabledCompressionMethods", String[].class)
                        .invoke(socket, new Object[] { new String[]{"ZLIB"}});
                openSslSocketClass.getMethod("setUseSessionTickets", boolean.class)
                        .invoke(socket, true);
                openSslSocketClass.getMethod("setHostname", String.class)
                        .invoke(socket, socketHost);
            }
        } catch (ClassNotFoundException ignored) {
            // TODO: support the RI's socket classes
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }
    
    public static byte[] getNpnSelectedProtocol(SSLSocket socket) {
        // First try Android's APIs.
        try {
            Class<?> openSslSocketClass = Class.forName(
                    "org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl");
            return (byte[]) openSslSocketClass.getMethod("getNpnSelectedProtocol").invoke(socket);
        } catch (ClassNotFoundException ignored) {
            // this isn't Android; fall through to try OpenJDK with Jetty
        } catch (IllegalAccessException e) {
            throw new AssertionError();
        } catch (InvocationTargetException e) {
            throw new AssertionError();
        } catch (NoSuchMethodException e) {
            throw new AssertionError();
        }

        // Next try OpenJDK.
        JettyNpnProvider provider = (JettyNpnProvider) NextProtoNego.get(socket);
        if (!provider.unsupported && provider.selected == null) {
            throw new IllegalStateException("No callback received. Is NPN configured properly?");
        }
        return provider.unsupported
                ? null
                : provider.selected.getBytes(Charsets.US_ASCII);
    }

    public static void setNpnProtocols(SSLSocket socket, byte[] npnProtocols) {
        // First try Android's APIs.
        try {
            Class<?> openSslSocketClass = Class.forName(
                    "org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl");
            openSslSocketClass.getMethod("setNpnProtocols", byte[].class)
                    .invoke(socket, npnProtocols);
        } catch (ClassNotFoundException ignored) {
            // this isn't Android; fall through to try OpenJDK with Jetty
        } catch (IllegalAccessException e) {
            throw new AssertionError();
        } catch (InvocationTargetException e) {
            throw new AssertionError();
        } catch (NoSuchMethodException e) {
            throw new AssertionError();
        }

        // Next try OpenJDK.
        List<String> strings = new ArrayList<String>();
        for (int i = 0; i < npnProtocols.length; ) {
            int length = npnProtocols[i++];
            strings.add(new String(npnProtocols, i, length, Charsets.US_ASCII));
            i += length;
        }
        JettyNpnProvider provider = new JettyNpnProvider();
        provider.protocols = strings;
        NextProtoNego.put(socket, provider);
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
        if (specifiedPort != -1) {
            return specifiedPort;
        }

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
