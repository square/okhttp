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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class UtilTest {
  @Test
  public void testAssertionError() {
    NullPointerException nullPointerException = new NullPointerException();
    AssertionError ae = Util.assertionError("npe", nullPointerException);
    assertSame(nullPointerException, ae.getCause());
    assertEquals("npe", ae.getMessage());
  }
}
