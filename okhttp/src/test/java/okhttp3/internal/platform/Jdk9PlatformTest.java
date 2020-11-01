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

import javax.net.ssl.SSLSocket;
import okhttp3.DelegatingSSLSocket;
import okhttp3.testing.PlatformRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

public class Jdk9PlatformTest {
  @RegisterExtension public final PlatformRule platform = new PlatformRule();

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
    platform.assumeJdk9();

    DelegatingSSLSocket applicationProtocolUnsupported = new DelegatingSSLSocket(null) {
      @Override public String getApplicationProtocol() {
        throw new UnsupportedOperationException("Mock exception");
      }
    };

    assertThat(new Jdk9Platform().getSelectedProtocol(applicationProtocolUnsupported)).isNull();
  }
}
