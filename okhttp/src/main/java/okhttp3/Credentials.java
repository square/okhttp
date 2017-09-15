/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3;

import java.nio.charset.Charset;
import okio.ByteString;

import static okhttp3.internal.Util.ISO_8859_1;

/** Factory for HTTP authorization credentials. */
public final class Credentials {
  private Credentials() {
  }

  /** Returns an auth credential for the Basic scheme. */
  public static String basic(String userName, String password) {
    return basic(userName, password, ISO_8859_1);
  }

  public static String basic(String userName, String password, Charset charset) {
    String usernameAndPassword = userName + ":" + password;
    String encoded = ByteString.encodeString(usernameAndPassword, charset).base64();
    return "Basic " + encoded;
  }
}
