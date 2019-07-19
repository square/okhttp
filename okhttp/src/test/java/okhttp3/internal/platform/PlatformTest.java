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

import okhttp3.testing.PlatformRule;
import org.junit.Rule;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PlatformTest {
  @Rule
  public PlatformRule platform = new PlatformRule();

  @Test public void alwaysBuilds() {
    new Platform();
  }

  /** Guard against the default value changing by accident. */
  @Test public void defaultPrefix() {
    assertThat(new Platform().getPrefix()).isEqualTo("OkHttp");
  }

  public static String getJvmSpecVersion() {
    return System.getProperty("java.specification.version", "unknown");
  }

  @Test
  public void testToStringIsClassname() {
    assertThat(new Platform().toString()).isEqualTo("Platform");
  }
}
