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
package okhttp3.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class UtilTest {
  @Test public void immutableMap() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("a", "A");
    Map<String, String> immutableCopy = Util.immutableMap(map);
    assertThat(Collections.singletonMap("a", "A")).isEqualTo(immutableCopy);
    map.clear();
    assertThat(Collections.singletonMap("a", "A")).isEqualTo(immutableCopy);
    try {
      immutableCopy.clear();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }
}
