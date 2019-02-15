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

import static okhttp3.internal.platform.PlatformTest.getPlatform;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import org.eclipse.jetty.alpn.openjdk8.server.OpenJDK8ServerALPNProcessor;
import org.junit.Before;
import org.junit.Test;

public class Jdk8WithJettyBootPlatformTest {
  @Before
  public void before() {
    assumeTrue(getPlatform().equals("jdk-with-jetty-boot"));
  }

  @Test
  public void testBuildsWithJettyBoot() {
    assertNotNull(Jdk8WithJettyBootPlatform.buildIfSupported());
  }

  /**
   * For this test, it's imperative that {@code org.eclipse.jetty.alpn:alpn-api} is on the classpath.
   */
  @Test
  public void testJettyDoesNotFailAfterBuilding() throws ClassNotFoundException {
    assertNotNull(Jdk8WithJettyBootPlatform.buildIfSupported());
    new OpenJDK8ServerALPNProcessor().init();

    // Just to double check, but doing this afterwards so as to not influence the test
    assertNotNull(Class.forName("org.eclipse.jetty.alpn.ALPN").getClassLoader());
  }
}
