/*
 * Copyright (C) 2013 The Android Open Source Project
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

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.ArrayList;
import java.util.List;

public final class RecordingAuthenticator extends Authenticator {
  /** base64("username:password") */
  public static final String BASE_64_CREDENTIALS = "dXNlcm5hbWU6cGFzc3dvcmQ=";

  public final List<String> calls = new ArrayList<>();
  public final PasswordAuthentication authentication;

  public RecordingAuthenticator(PasswordAuthentication authentication) {
    this.authentication = authentication;
  }

  public RecordingAuthenticator() {
    this(new PasswordAuthentication("username", "password".toCharArray()));
  }

  @Override protected PasswordAuthentication getPasswordAuthentication() {
    this.calls.add("host=" + getRequestingHost()
        + " port=" + getRequestingPort()
        + " site=" + getRequestingSite().getHostName()
        + " url=" + getRequestingURL()
        + " type=" + getRequestorType()
        + " prompt=" + getRequestingPrompt()
        + " protocol=" + getRequestingProtocol()
        + " scheme=" + getRequestingScheme());
    return authentication;
  }
}
