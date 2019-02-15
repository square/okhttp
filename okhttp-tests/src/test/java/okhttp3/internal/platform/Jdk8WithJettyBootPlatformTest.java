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

import com.ea.agentloader.AgentLoader;
import org.eclipse.jetty.alpn.openjdk8.server.OpenJDK8ServerALPNProcessor;
import org.junit.Before;
import org.junit.Test;

import static okhttp3.internal.platform.PlatformTest.getPlatform;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

public class Jdk8WithJettyBootPlatformTest {

  private static final boolean isDynamicJettyBootPlatform = getPlatform().equals("jdk-with-jetty-boot-dynamic");
  private static final boolean isJettyBootPlatform = getPlatform().equals("jdk-with-jetty-boot");

  @Before
  public void before() {
    assumeTrue(isJettyBootPlatform || isDynamicJettyBootPlatform);
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
    // only now load the agent
    if (isDynamicJettyBootPlatform) {
      AgentLoader.loadAgentClass(Http2Agent.class.getName(), "");
    }
    new OpenJDK8ServerALPNProcessor().init();

    if (isDynamicJettyBootPlatform) {
      // Just to double check, but doing this afterwards so as to not influence the test
      assertNotNull(Class.forName("org.eclipse.jetty.alpn.ALPN").getClassLoader());
    }
  }
}
