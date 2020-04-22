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

import java.io.IOException;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import okhttp3.testing.PlatformRule;
import org.junit.Rule;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class Jdk9PlatformTest {
  @Rule public final PlatformRule platform = new PlatformRule();

  @Test
  public void buildsWhenJdk9() {
    platform.assumeJdk9();
    assertThat(Jdk9Platform.Companion.buildIfSupported()).isNotNull();
  }

  @Test
  public void buildsWhenJdk8() {
    platform.assumeJdk8();

    try {
      SSLSocket.class.getMethod("getApplicationProtocol");

      // also present on JDK8 after build 252.
      assertThat(Jdk9Platform.Companion.buildIfSupported()).isNotNull();
    } catch (NoSuchMethodException nsme) {
      assertThat(Jdk9Platform.Companion.buildIfSupported()).isNull();
    }
  }

  @Test
  public void testToStringIsClassname() {
    assertThat(new Jdk9Platform().toString()).isEqualTo("Jdk9Platform");
  }

  @Test
  public void selectedProtocolIsNullWhenSslSocketThrowsExceptionForApplicationProtocol() {
    assertThat(new Jdk9Platform().getSelectedProtocol(
        new SSLSocket() {
          @Override public String getApplicationProtocol() {
            throw new UnsupportedOperationException("Mock exception");
          }

          // Implement abstract methods.
          @Override public String[] getSupportedCipherSuites() {
            return new String[0];
          }

          @Override public String[] getEnabledCipherSuites() {
            return new String[0];
          }

          @Override public void setEnabledCipherSuites(String[] suites) {
          }

          @Override public String[] getSupportedProtocols() {
            return new String[0];
          }

          @Override public String[] getEnabledProtocols() {
            return new String[0];
          }

          @Override public void setEnabledProtocols(String[] protocols) {
          }

          @Override public SSLSession getSession() {
            return null;
          }

          @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
          }

          @Override
          public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
          }

          @Override public void startHandshake() throws IOException {
          }

          @Override public void setUseClientMode(boolean mode) {
          }

          @Override public boolean getUseClientMode() {
            return false;
          }

          @Override public void setNeedClientAuth(boolean need) {
          }

          @Override public boolean getNeedClientAuth() {
            return false;
          }

          @Override public void setWantClientAuth(boolean want) {
          }

          @Override public boolean getWantClientAuth() {
            return false;
          }

          @Override public void setEnableSessionCreation(boolean flag) {
          }

          @Override public boolean getEnableSessionCreation() {
            return false;
          }
        })).isNull();
  }
}
