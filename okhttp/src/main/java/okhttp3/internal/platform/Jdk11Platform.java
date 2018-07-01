package okhttp3.internal.platform;

import java.lang.reflect.Method;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

// http://hg.openjdk.java.net/jdk/jdk11/rev/68fa3d4026ea
// http://openjdk.java.net/jeps/332
public class Jdk11Platform extends Jdk9Platform {
  Jdk11Platform(Method setProtocolMethod, Method getProtocolMethod) {
    super(setProtocolMethod, getProtocolMethod);
  }

  @Override public SSLContext getSSLContext() {
    String jvmVersion = System.getProperty("java.specification.version");
    if ("11".equals(jvmVersion)) {
      try {
        return SSLContext.getInstance("TLSv1.3");
      } catch (NoSuchAlgorithmException e) {
        // fallback to TLS
      }
    }

    try {
      return SSLContext.getInstance("TLS");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("No TLS provider", e);
    }
  }

  public static Jdk9Platform buildIfSupported() {
    String jvmVersion = System.getProperty("java.specification.version");
    if ("11".equals(jvmVersion)) {
      try {
        Method setProtocolMethod =
            SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
        Method getProtocolMethod = SSLSocket.class.getMethod("getApplicationProtocol");

        return new Jdk9Platform(setProtocolMethod, getProtocolMethod);
      } catch (NoSuchMethodException ignored) {
        throw new AssertionError();
      }
    }

    return null;
  }
}
